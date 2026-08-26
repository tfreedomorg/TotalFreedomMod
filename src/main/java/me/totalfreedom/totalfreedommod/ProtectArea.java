package me.totalfreedom.totalfreedommod;

import me.totalfreedom.api.FreedomAPI;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.*;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import me.totalfreedom.totalfreedommod.ProtectArea.ProtectedRegion.CantFindWorldException;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.sql.PersistenceQueue;
import me.totalfreedom.api.sql.adapter.ProtectedAreaRepository;
import me.totalfreedom.totalfreedommod.util.*;

import com.google.common.collect.Maps;
import com.google.gson.reflect.TypeToken;

public class ProtectArea extends FreedomService
{
    private static final long ITEM_SWEEP_RATE = 40L;
    private static final long SHUTDOWN_FLUSH_TIMEOUT_MS = 10L * 1000L;
    private static final Type PROTECTED_AREA_LIST_TYPE = new TypeToken<List<ProtectedRegion>>() {}.getType();

    public static final String DATA_FILENAME = "protectedareas.json";
    public static final String LEGACY_YAML_FILENAME = "protectedareas.yml";
    public static final String LEGACY_DATA_FILENAME = "protectedareas.dat";
    public static final double MAX_RADIUS = 50.0;

    private final Map<UUID, ProtectedRegion> areas = Maps.newHashMap();

    private final PersistenceQueue writes = new PersistenceQueue("protected area");

    private File dataFile;
    private boolean usingSql = false;
    private BukkitTask itemSweepTask;

    public ProtectArea(FreedomAPI plugin)
    {
        super(plugin);
    }

    @Override
    public void onStart()
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
            return;

        dataFile = new File(plugin.getDataFolder(), DATA_FILENAME);

        load();
        plugin.database().whenReady(this::load);

        itemSweepTask = Bukkit.getScheduler().runTaskTimer(
            plugin, FTask.guard("ProtectArea/sweepItems", this::sweepItems), ITEM_SWEEP_RATE, ITEM_SWEEP_RATE);
    }

    /**
     * Populate the area list from SQL where it is available and the JSON snapshot otherwise.
     * Never blocks: the SQL read runs off-thread and is applied back on the main thread.
     */
    public void load()
    {
        if (dataFile == null)
            dataFile = new File(plugin.getDataFolder(), DATA_FILENAME);

        if (plugin.database() != null && plugin.database().isInitialized())
        {
            loadFromSqlAsync();
            return;
        }

        loadFromJsonOrLegacy();
    }

    private void loadFromSqlAsync()
    {
        final ProtectedAreaRepository repo = plugin.database().getProtectedAreaRepository();
        plugin.database().readAsync("ProtectArea/loadFromSql", repo.loadAllAsync(),
                            loaded -> applyLoadedAreas(repo, loaded),
                            () ->
                            {
                                usingSql = false;
                                loadFromJsonOrLegacy();
                            });
    }

    private void applyLoadedAreas(final ProtectedAreaRepository repo, final List<ProtectedRegion> loaded)
    {
        usingSql = true;

        if (loaded.isEmpty() && !dataFile.exists())
        {
            final File legacyFile = new File(plugin.getDataFolder(), LEGACY_DATA_FILENAME);
            if (legacyFile.exists())
                migrateLegacyData(legacyFile);

            return;
        }

        areas.clear();
        loaded.forEach(region -> areas.put(region.getUuid(), region));
        FLog.info(String.format("Loaded %d protected area(s) from SQL database.", areas.size()));

        reconcileFromJsonIfNewer(repo);
    }

    private void loadFromJsonOrLegacy()
    {
        if (!dataFile.exists())
        {
            File legacyFile = new File(plugin.getDataFolder(), LEGACY_DATA_FILENAME);
            if (legacyFile.exists())
                migrateLegacyData(legacyFile);

            return;
        }

        loadFromJson();
    }

    private void loadFromJson()
    {
        areas.clear();
        try
        {
            readJsonAreas().forEach(region -> areas.put(region.getUuid(), region));
        }
        catch (IOException ex)
        {
            FLog.error("Failed to read " + DATA_FILENAME + ": " + ex.getMessage());
        }
        FLog.info("Loaded " + areas.size() + " protected area(s).");
    }

    private List<ProtectedRegion> readJsonAreas() throws IOException
    {
        try (FileReader reader = new FileReader(dataFile))
        {
            List<ProtectedRegion> loaded = JsonUtil.GSON.fromJson(reader, PROTECTED_AREA_LIST_TYPE);
            return loaded != null ? loaded : new ArrayList<>();
        }
    }

    /**
     * If protectedareas.json was written more recently than the database's last update, re-import
     * it into SQL. The comparison and the re-import both ride the write queue off the main thread.
     */
    private void reconcileFromJsonIfNewer(final ProtectedAreaRepository repo)
    {
        if (!dataFile.exists())
        {
            // Nothing to reconcile against, but SQL now has rows that no snapshot covers.
            writes.enqueue(writeJsonAsync());
            return;
        }

        final List<ProtectedRegion> jsonAreas;
        try
        {
            jsonAreas = readJsonAreas();
        }
        catch (IOException ex)
        {
            FLog.warn(String.format("Failed to read %s: %s", DATA_FILENAME, ex.getMessage()));
            return;
        }

        if (jsonAreas.isEmpty())
        {
            writes.enqueue(writeJsonAsync());
            return;
        }

        final long fileModified = dataFile.lastModified();

        writes.enqueue(Mono.fromCallable(() ->
              {
                  final Long sqlUpdatedAt = repo.getMaxUpdatedAt();
                  return FUtil.isSnapshotNewer(fileModified, sqlUpdatedAt);
              })
              .subscribeOn(Schedulers.boundedElastic())
              .filter(Boolean::booleanValue)
              .flatMap(ignored ->
              {
                FLog.info(String.format("%s is newer than the database; rebuilding it from the file's %d protected area(s).",
                                        DATA_FILENAME, jsonAreas.size()));
                final Set<UUID> keep = jsonAreas.stream()
                                                .map(ProtectedRegion::getUuid)
                                                .collect(Collectors.toSet());
                return Flux.fromIterable(jsonAreas)
                           .concatMap(repo::save)
                           .then(repo.loadAllAsync())
                           .flatMapMany(Flux::fromIterable)
                           .map(ProtectedRegion::getUuid)
                           .filter(uuid -> !keep.contains(uuid))
                           .concatMap(repo::deleteAsync)
                           .then(Mono.<Void>fromRunnable(() -> plugin.database().sync("ProtectArea/applyReconciled",
                                                         () ->
                                                         {
                                                            areas.clear();
                                                            jsonAreas.forEach(region -> areas.put(region.getUuid(), region));
                                                         })));
              })
              .onErrorResume(ex ->
              {
                  FLog.warn(String.format("Failed to reconcile %s into the database: %s",
                               DATA_FILENAME, ex.getMessage()));
                  return Mono.empty();
              })
              .then());
    }

    @SuppressWarnings("unchecked")
    private void migrateLegacyData(File legacyFile)
    {
        FLog.info("Migrating protected areas from legacy .dat format...");
        try (FileInputStream fis = new FileInputStream(legacyFile);
             ObjectInputStream ois = new ObjectInputStream(fis))
        {
            HashMap<String, SerializableProtectedRegion> legacyAreas =
                (HashMap<String, SerializableProtectedRegion>) ois.readObject();

            areas.clear();
            for (Map.Entry<String, SerializableProtectedRegion> entry : legacyAreas.entrySet())
            {
                final UUID uuid = UUID.fromString(entry.getKey());
                SerializableProtectedRegion legacy = entry.getValue();
                areas.put(uuid, new ProtectedRegion(
                    uuid,
                    UUID.randomUUID().toString(),
                    (int) (legacy.x - legacy.radius), (int) (legacy.y - legacy.radius), (int) (legacy.z - legacy.radius),
                    (int) (legacy.x + legacy.radius), (int) (legacy.y + legacy.radius), (int) (legacy.z + legacy.radius),
                    legacy.worldUUID.toString()
                ));
            }

            save();

            File oldFile = new File(legacyFile.getParent(), LEGACY_DATA_FILENAME + ".old");
            if (legacyFile.renameTo(oldFile))
                FLog.info("Migration complete. Legacy file renamed to " + LEGACY_DATA_FILENAME + ".old");
            else
                FLog.warn("Migration complete but could not rename legacy file.");

        }
        catch (Exception ex)
        {
            FLog.error("Failed to migrate legacy protected areas data: " + ex.getMessage());
            FLog.error(ex);
        }
    }

    @Deprecated
    private void loadFromYaml(File file)
    {
        areas.clear();

        if (!file.exists())
            return;

        try
        {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection areasSection = config.getConfigurationSection("areas");

            if (areasSection == null)
                return;

            for (String id : areasSection.getKeys(false))
            {
                ConfigurationSection areaSection = areasSection.getConfigurationSection(id);
                if (areaSection == null)
                    continue;

                UUID uuid = UUID.fromString(id);
                String name = areaSection.getString("name");
                int minX = areaSection.getInt("min_x");
                int minY = areaSection.getInt("min_y");
                int minZ = areaSection.getInt("min_z");
                int maxX = areaSection.getInt("max_x");
                int maxY = areaSection.getInt("max_y");
                int maxZ = areaSection.getInt("max_z");
                String worldUUID = areaSection.getString("world");

                try
                {
                    areas.put(uuid, new ProtectedRegion(uuid, name, minX, minY, minZ, maxX, maxY, maxZ, worldUUID));
                }
                catch (CantFindWorldException ex)
                {
                    FLog.warn(ex.getMessage());
                }
            }
        }
        catch (Exception ex)
        {
            FLog.error("Failed to load protected areas: " + ex.getMessage());
            FLog.error(ex);
        }
    }

    @Override
    public void onStop()
    {
        if (itemSweepTask != null)
        {
            itemSweepTask.cancel();
            itemSweepTask = null;
        }

        // Let the queue drain first, then flush: a queued write landing after the flush would
        // restore a stale snapshot.
        save();
        awaitPendingWrites(SHUTDOWN_FLUSH_TIMEOUT_MS);
    }

    /**
     * Queue a write of every protected area to SQL, followed by a refresh of the
     * protectedareas.json snapshot. Falls back to a JSON-only write when SQL is unavailable.
     * Safe from a command handler: the SQL round trips run off the main thread.
     */
    public void save()
    {
        if (!usingSql || plugin.database() == null || !plugin.database().isInitialized())
        {
            writes.enqueue(writeJsonAsync());
            return;
        }

        final ProtectedAreaRepository repo = plugin.database().getProtectedAreaRepository();
        final List<ProtectedRegion> snapshot = new ArrayList<>(areas.values());

        writes.enqueue(Flux.fromIterable(snapshot)
                .concatMap(region -> repo.save(region)
                        .onErrorResume(ex ->
                        {
                            FLog.error(String.format("Could not save protected area %s to SQL: %s",
                                    region.getName(), ex.getMessage()));
                            return Mono.empty();
                        }))
                .then(writeJsonAsync()));
    }

    /**
     * Wait for queued protected-area writes to land, up to {@code timeoutMs}.
     */
    public void awaitPendingWrites(long timeoutMs)
    {
        writes.await(timeoutMs);
    }

    private Mono<Void> writeJsonAsync()
    {
        final List<ProtectedRegion> snapshot = new ArrayList<>(areas.values());
        return Mono.<Void>fromRunnable(() -> writeJson(snapshot))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void writeJson(final List<ProtectedRegion> snapshot)
    {
        if (dataFile == null)
            dataFile = new File(plugin.getDataFolder(), DATA_FILENAME);

        try (FileWriter writer = new FileWriter(dataFile))
        {
            JsonUtil.GSON.toJson(snapshot, PROTECTED_AREA_LIST_TYPE, writer);
        }
        catch (IOException ex)
        {
            FLog.error("Failed to save protected areas: " + ex.getMessage());
            FLog.error(ex);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onBlockBreak(BlockBreakEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
            return;

        final Player player = event.getPlayer();
        if (plugin.admins().isAdmin(player))
            return;

        final Location location = event.getBlock().getLocation();

        if (isInProtectedArea(location))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onBlockPlace(BlockPlaceEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
            return;

        final Player player = event.getPlayer();
        if (plugin.admins().isAdmin(player))
            return;

        final Location location = event.getBlock().getLocation();

        if (isInProtectedArea(location))
            event.setCancelled(true);
    }

    // Entity explosions (TNT, Creepers, Withers, etc.)
    @EventHandler(priority = EventPriority.NORMAL)
    public void onEntityExplode(EntityExplodeEvent event)
    {
        if (ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
            event.blockList().removeIf(block -> isInProtectedArea(block.getLocation()));
    }

    // Block explosions (beds in nether, respawn anchors)
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBlockExplode(BlockExplodeEvent event)
    {
        if (ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
            event.blockList().removeIf(block -> isInProtectedArea(block.getLocation()));
    }

    // Enderman picking up blocks, falling blocks, etc.
    @EventHandler(priority = EventPriority.NORMAL)
    public void onEntityChangeBlock(EntityChangeBlockEvent event)
    {
        if (ConfigEntry.PROTECTAREA_ENABLED.getBoolean()
                && isInProtectedArea(event.getBlock().getLocation()))
            event.setCancelled(true);
    }

    // Water/lava bucket placement
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBucketEmpty(PlayerBucketEmptyEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
            return;

        final Player player = event.getPlayer();
        if (plugin.admins().isAdmin(player))
            return;

        if (isInProtectedArea(event.getBlock().getLocation()))
            event.setCancelled(true);
    }

    // Water/lava bucket removal
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBucketFill(PlayerBucketFillEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
            return;

        final Player player = event.getPlayer();
        if (plugin.admins().isAdmin(player))
            return;

        if (isInProtectedArea(event.getBlock().getLocation()))
            event.setCancelled(true);
    }

    // Fire starting
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBlockIgnite(BlockIgniteEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
            return;

        final Player player = event.getPlayer();
        if (player != null && plugin.admins().isAdmin(player))
            return;

        if (isInProtectedArea(event.getBlock().getLocation()))
            event.setCancelled(true);
    }

    // Fire spread
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBlockSpread(BlockSpreadEvent event)
    {
        if (ConfigEntry.PROTECTAREA_ENABLED.getBoolean()
                && (event.getSource().getType() == org.bukkit.Material.FIRE)
                && isInProtectedArea(event.getBlock().getLocation()))
                    event.setCancelled(true);
    }

    // Blocks burning
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBlockBurn(BlockBurnEvent event)
    {
        if (ConfigEntry.PROTECTAREA_ENABLED.getBoolean()
                && isInProtectedArea(event.getBlock().getLocation()))
                    event.setCancelled(true);
    }

    // Water/lava flow
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBlockFromTo(BlockFromToEvent event)
    {
        if (ConfigEntry.PROTECTAREA_ENABLED.getBoolean() 
                && !isInProtectedArea(event.getBlock().getLocation()) 
                && isInProtectedArea(event.getToBlock().getLocation()))
                    event.setCancelled(true);
    }

    // Piston extend
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPistonExtend(BlockPistonExtendEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
            return;

        for (Block block : event.getBlocks())
        {
            if (isInProtectedArea(block.getLocation()))
            {
                event.setCancelled(true);
                return;
            }
        }
    }

    // Piston retract
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPistonRetract(BlockPistonRetractEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
            return;

        for (Block block : event.getBlocks())
            if (isInProtectedArea(block.getLocation()))
            {
                event.setCancelled(true);
                return;
            }
    }

    // Placing paintings, item frames, etc.
    @EventHandler(priority = EventPriority.NORMAL)
    public void onHangingPlace(HangingPlaceEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
            return;

        final Player player = event.getPlayer();
        if (player != null && plugin.admins().isAdmin(player))
            return;

        if (isInProtectedArea(event.getEntity().getLocation()))
            event.setCancelled(true);
    }

    // Breaking paintings, item frames by entity
    @EventHandler(priority = EventPriority.NORMAL)
    public void onHangingBreak(HangingBreakByEntityEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
            return;

        Entity remover = event.getRemover();
        if (remover instanceof Player)
        {
            Player player = (Player) remover;
            if (plugin.admins().isAdmin(player))
                return;
        }

        if (isInProtectedArea(event.getEntity().getLocation()))
            event.setCancelled(true);
    }

    // Vehicle destruction (minecarts, boats)
    @EventHandler(priority = EventPriority.NORMAL)
    public void onVehicleDestroy(VehicleDestroyEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
            return;

        Entity attacker = event.getAttacker();
        if (attacker instanceof Player)
        {
            Player player = (Player) attacker;
            if (plugin.admins().isAdmin(player))
                return;
        }

        if (isInProtectedArea(event.getVehicle().getLocation()))
            event.setCancelled(true);
    }

    // Block fade (ice melting, snow melting, etc.) - protect structure integrity
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBlockFade(BlockFadeEvent event)
    {
        if (ConfigEntry.PROTECTAREA_ENABLED.getBoolean()
                && isInProtectedArea(event.getBlock().getLocation()))
                    event.setCancelled(true);
    }

    // Sign text editing (Minecraft 1.20+ allows editing signs after placement)
    @EventHandler(priority = EventPriority.NORMAL)
    public void onSignChange(SignChangeEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
            return;

        final Player player = event.getPlayer();
        if (plugin.admins().isAdmin(player))
            return;

        if (isInProtectedArea(event.getBlock().getLocation()))
            event.setCancelled(true);
    }

    // Player interact (crop trampling, etc.)
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteract(PlayerInteractEvent event)
    {
        final Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        if (block == null)
            return;

        final Location location = block.getLocation();
        if (!shouldBlockInteraction(player, location))
            return;

        if (event.getAction() == org.bukkit.event.block.Action.PHYSICAL)
        {
            event.setCancelled(true);
            return;
        }

        // block right-click interactions
		if ((event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) 
                && event.getItem() != null)
                    event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event)
    {
        final Player player = event.getPlayer();
        final Location location = event.getRightClicked().getLocation();
        
        if (shouldBlockInteraction(player, location))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean()
                || !ConfigEntry.PROTECTAREA_PROTECT_PLAYERS.getBoolean()
                || !(event.getEntity() instanceof Player))
            return;

        if (isInProtectedArea(event.getEntity().getLocation()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean()
                || !ConfigEntry.PROTECTAREA_BLOCK_POTIONS.getBoolean())
                    return;

        for (LivingEntity affected : event.getAffectedEntities())
            if (affected instanceof Player && isInProtectedArea(affected.getLocation()))
                event.setIntensity(affected, 0.0D);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLingeringPotionSplash(LingeringPotionSplashEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean() 
                || !ConfigEntry.PROTECTAREA_BLOCK_POTIONS.getBoolean())
                    return;

        if (isInProtectedArea(event.getEntity().getLocation()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAreaEffectCloudApply(AreaEffectCloudApplyEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean() 
                || !ConfigEntry.PROTECTAREA_BLOCK_POTIONS.getBoolean())
                    return;

        event.getAffectedEntities()
             .removeIf(entity -> 
                       entity instanceof Player && isInProtectedArea(entity.getLocation())
                    );
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean() 
                || !ConfigEntry.PROTECTAREA_BLOCK_ITEMS.getBoolean()
                || (event.getEntity() instanceof Player player 
                        && plugin.admins().isAdmin(player)))
                    return;

        if (isInProtectedArea(event.getItem().getLocation()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryPickupItem(InventoryPickupItemEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean() 
                || !ConfigEntry.PROTECTAREA_BLOCK_ITEMS.getBoolean())
                    return;

        if (isInProtectedArea(event.getItem().getLocation()))
            event.setCancelled(true);
    }

    private boolean shouldBlockInteraction(Player player, Location location)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
            return false;

        if (player != null && plugin.admins().isAdmin(player))
            return false;

        return isInProtectedArea(location);
    }

    public boolean isInProtectedArea(final Location modifyLocation)
    {
        return areas
            .values()
            .stream()
            .anyMatch(area -> area.within(modifyLocation));
    }

    public boolean doesRegionOverlapWithProtectedArea(final Location min, final Location max, final World world)
    {
        return areas
            .values()
            .stream()
            .anyMatch(area -> area.within(min, max, world));
    }

    public ProtectedRegion addProtectedArea(final String name, final Location min, final Location max, final World world)
    {
        if (areas.values().stream().filter(area -> area.getName().equals(name)).count() != 0)
            return null;
        final UUID uuid = UUID.randomUUID();
        final ProtectedRegion region = new ProtectedRegion(uuid, name, min, max, world);
        areas.put(uuid, region);
        save();
        return region;
    }

    public ProtectedRegion updateProtectedRegion(final ProtectedRegion region, final Location min, final Location max, final World world)
    {
        region.setMinimumPoint(min);
        region.setMaximumPoint(max);
        region.setWorld(world);
        save();
        return region;
    }

    public ProtectedRegion getProtectedRegion(final String name)
    {
        for (final ProtectedRegion region : areas.values())
            if (region.getName().equals(name))
                return region;
        return null;
    }

    public List<String> getProtectedAreaNames()
    {
        return areas
            .values()
            .stream()
            .map(ProtectedRegion::getName)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    public void removeProtectedArea(final ProtectedRegion region)
    {
        areas.remove(region.getUuid());
        save();
    }

    public void clearProtectedAreas()
    {
        clearProtectedAreas(true);
    }

    public void clearProtectedAreas(boolean createSpawnpointProtectedAreas)
    {
        areas.clear();
        save();
    }

    private void sweepItems()
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean() || !ConfigEntry.PROTECTAREA_BLOCK_ITEMS.getBoolean())
            return;

        // Remove all items inside protected areas
        areas
            .values()
            .stream()
            .forEach(area -> {
                try
                {
                    area
                        .getWorld()
                        .getEntities()
                        .stream()
                        .filter(entity -> entity != null && entity instanceof Item && area.within(entity.getLocation()))
                        .forEach(Entity::remove);
                }
                catch (CantFindWorldException _) {}
            });
    }

    public static class ProtectedRegion
    {
        private UUID uuid;
        private String name;
        private Vector min;
        private Vector max;
        private UUID worldUUID;
        private transient World world;

        public ProtectedRegion(final UUID uuid, final String name, final Location min, final Location max, final World world)
        {
            this.uuid = uuid;
            this.name = name;
            setMinimumPoint(min);
            setMaximumPoint(max);
            this.worldUUID = world.getUID();
        }

        public ProtectedRegion(final UUID uuid,
            final String name,
            final int minX,
            final int minY,
            final int minZ,
            final int maxX,
            final int maxY,
            final int maxZ,
            final String worldUUID) throws CantFindWorldException
        {
            this.uuid = uuid;
            this.name = name;
            try
            {
                // World is not deserialized directly here because
                // worlds are loaded dynamically. Save the UUID
                // and fetch the world from Bukkit later.
                this.worldUUID = UUID.fromString(worldUUID);
            }
            catch (IllegalArgumentException _)
            {
                throw new CantFindWorldException("Protected region has an invalid UUID for its world: " + worldUUID);
            }
            this.min = new Vector(minX, minY, minZ);
            this.max = new Vector(maxX, maxY, maxZ);
        }

        public UUID getUuid()
        {
            return uuid;
        }

        public String getName()
        {
            return name;
        }

        /**
         * Raw world reference, usable for persistence without requiring the world to
         * currently be loaded in Bukkit (unlike {@link #getWorld()}).
         */
        public UUID getWorldUUID()
        {
            return worldUUID;
        }

        /**
         * Raw minimum corner, usable for persistence without requiring the world to
         * currently be loaded in Bukkit (unlike {@link #getMinimumPoint()}).
         */
        public Vector getMinVector()
        {
            return min;
        }

        /**
         * Raw maximum corner, usable for persistence without requiring the world to
         * currently be loaded in Bukkit (unlike {@link #getMaximumPoint()}).
         */
        public Vector getMaxVector()
        {
            return max;
        }

        public World getWorld() throws CantFindWorldException
        {
            if (this.world != null)
                return this.world;
            this.world = Bukkit.getWorld(worldUUID);
            if (this.world == null)
                throw new CantFindWorldException("Can't find world with UUID: " + worldUUID);
            return this.world;
        }

        public void setWorld(final World world)
        {
            this.worldUUID = world.getUID();
        }

        public Location getMinimumPoint() throws CantFindWorldException
        {
            return new Location(getWorld(), min.getBlockX(), min.getBlockY(), min.getBlockZ());
        }

        public Location getMaximumPoint() throws CantFindWorldException
        {
            return new Location(getWorld(), max.getBlockX(), max.getBlockY(), max.getBlockZ());
        }

        public void setMinimumPoint(final Location min)
        {
            this.min = new Vector(min.getBlockX(), min.getBlockY(), min.getBlockZ());
        }

        public void setMaximumPoint(final Location max)
        {
            this.max = new Vector(max.getBlockX(), max.getBlockY(), max.getBlockZ());
        }

        @Override
        public String toString()
        {
            final World world = Bukkit.getWorld(worldUUID);
            final String worldContent = world != null ? world.getName() : worldUUID.toString();

            return String.format("'%s' is a protected region in the '%s' world spanning from (%s, %s, %s) to (%s, %s, %s).",
                name,
                worldContent,
                min.getBlockX(),
                min.getBlockY(),
                min.getBlockZ(),
                max.getBlockX(),
                max.getBlockY(),
                max.getBlockZ());
        } 

        /**
         * Checks if a location is within the protected region.
         * 
         * @param loc the location to check
         * @return whether the location is in the protected region
         */
        public boolean within(final Location loc)
        {
            if (!worldUUID.equals(loc.getWorld().getUID()))
                return false;
            return loc.getY() <= max.getY() &&
                loc.getY() >= min.getY() &&
                loc.getX() <= max.getX() &&
                loc.getX() >= min.getX() &&
                loc.getZ() <= max.getZ() &&
                loc.getZ() >= min.getZ();
        }

        public boolean within(final Location min, final Location max, final World world)
        {
            if (!this.worldUUID.equals(world.getUID()))
                return false;

            // This logic is really hairy, but it's derived from here:
            // https://math.stackexchange.com/a/2651718

            return this.min.getBlockX() <= max.getBlockX()
                && min.getBlockX() <= this.max.getBlockX()
                && this.min.getBlockY() <= max.getBlockY()
                && min.getBlockY() <= this.max.getBlockY()
                && this.min.getBlockZ() <= max.getBlockZ()
                && min.getBlockZ() <= this.max.getBlockZ();
        }

        public static class CantFindWorldException extends Exception
        {
            private static final long serialVersionUID = 1L;

            public CantFindWorldException(String string)
            {
                super(string);
            }
        }
    }

    // Legacy class for reading old .dat files during migration
    private static class SerializableProtectedRegion implements Serializable
    {
        private static final long serialVersionUID = 213123517828282L;
        final double x, y, z;
        final double radius;
        final String worldName;
        final UUID worldUUID;

        private SerializableProtectedRegion()
        {
            this.x = 0;
            this.y = 0;
            this.z = 0;
            this.radius = 0;
            this.worldName = null;
            this.worldUUID = null;
        }
    }

}

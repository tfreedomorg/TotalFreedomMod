package me.totalfreedom.totalfreedommod;

import com.google.common.collect.Maps;

import lombok.Getter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import me.totalfreedom.totalfreedommod.ProtectArea.ProtectedRegion.CantFindWorldException;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FTask;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.LingeringPotionSplashEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

public class ProtectArea extends FreedomService
{

    public static final String DATA_FILENAME = "protectedareas.yml";
    public static final String LEGACY_DATA_FILENAME = "protectedareas.dat";
    public static final double MAX_RADIUS = 50.0;
    // How often (in ticks) to sweep loose items out of protected areas.
    private static final long ITEM_SWEEP_RATE = 40L;
    //
    private final Map<UUID, ProtectedRegion> areas = Maps.newConcurrentMap();
    private BukkitTask itemSweepTask;

    public ProtectArea(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
        {
            return;
        }

        File ymlFile = new File(plugin.getDataFolder(), DATA_FILENAME);
        File legacyFile = new File(plugin.getDataFolder(), LEGACY_DATA_FILENAME);

        if (legacyFile.exists() && !ymlFile.exists())
        {
            migrateLegacyData(legacyFile, ymlFile);
        }

        loadFromYaml(ymlFile);

        itemSweepTask = Bukkit.getScheduler().runTaskTimer(
            plugin, FTask.guard("ProtectArea/sweepItems", this::sweepItems), ITEM_SWEEP_RATE, ITEM_SWEEP_RATE);
    }

    @SuppressWarnings("unchecked")
    private void migrateLegacyData(File legacyFile, File ymlFile)
    {
        FLog.info("Migrating protected areas from legacy .dat format to .yml format...");
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
            {
                FLog.info("Migration complete. Legacy file renamed to " + LEGACY_DATA_FILENAME + ".old");
            }
            else
            {
                FLog.warning("Migration complete but could not rename legacy file.");
            }
        }
        catch (Exception ex)
        {
            FLog.severe("Failed to migrate legacy protected areas data: " + ex.getMessage());
            FLog.severe(ex);
        }
    }

    private void loadFromYaml(File file)
    {
        areas.clear();
        
        if (!file.exists())
        {
            return;
        }

        try
        {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection areasSection = config.getConfigurationSection("areas");
            
            if (areasSection == null)
            {
                return;
            }

            for (String id : areasSection.getKeys(false))
            {
                ConfigurationSection areaSection = areasSection.getConfigurationSection(id);
                if (areaSection == null)
                {
                    continue;
                }

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
                    FLog.warning(ex.getMessage());
                }
            }
        }
        catch (Exception ex)
        {
            FLog.severe("Failed to load protected areas: " + ex.getMessage());
            FLog.severe(ex);
        }
    }

    @Override
    protected void onStop()
    {
        if (itemSweepTask != null)
        {
            itemSweepTask.cancel();
            itemSweepTask = null;
        }
        save();
    }

    public void save()
    {
        try
        {
            YamlConfiguration config = new YamlConfiguration();
            ConfigurationSection areasSection = config.createSection("areas");

            for (Map.Entry<UUID, ProtectedRegion> entry : areas.entrySet())
            {
                ConfigurationSection areaSection = areasSection.createSection(entry.getKey().toString());
                ProtectedRegion region = entry.getValue();
                
                areaSection.set("name", region.getName());
                try
                {
                    areaSection.set("min_x", region.getMinimumPoint().getBlockX());
                    areaSection.set("min_y", region.getMinimumPoint().getBlockY());
                    areaSection.set("min_z", region.getMinimumPoint().getBlockZ());
                    areaSection.set("max_x", region.getMaximumPoint().getBlockX());
                    areaSection.set("max_y", region.getMaximumPoint().getBlockY());
                    areaSection.set("max_z", region.getMaximumPoint().getBlockZ());
                    areaSection.set("world", region.getWorld().getUID().toString());
                }
                catch (CantFindWorldException ex)
                {
                    FLog.warning(String.format("Failed to save protected area '%s' (%s) because the UUID of the world it's in was invalid",
                        region.getName(),
                        region.getUuid()));
                }
            }

            config.save(new File(plugin.getDataFolder(), DATA_FILENAME));
        }
        catch (IOException ex)
        {
            FLog.severe("Failed to save protected areas: " + ex.getMessage());
            FLog.severe(ex);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onBlockBreak(BlockBreakEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
        {
            return;
        }

        final Player player = event.getPlayer();
        if (plugin.al.isAdmin(player))
        {
            return;
        }

        final Location location = event.getBlock().getLocation();

        if (isInProtectedArea(location))
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onBlockPlace(BlockPlaceEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
        {
            return;
        }

        final Player player = event.getPlayer();
        if (plugin.al.isAdmin(player))
        {
            return;
        }

        final Location location = event.getBlock().getLocation();

        if (isInProtectedArea(location))
        {
            event.setCancelled(true);
        }
    }

    // Entity explosions (TNT, Creepers, Withers, etc.)
    @EventHandler(priority = EventPriority.NORMAL)
    public void onEntityExplode(EntityExplodeEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
        {
            return;
        }

        event.blockList().removeIf(block -> isInProtectedArea(block.getLocation()));
    }

    // Block explosions (beds in nether, respawn anchors)
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBlockExplode(BlockExplodeEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
        {
            return;
        }

        event.blockList().removeIf(block -> isInProtectedArea(block.getLocation()));
    }

    // Enderman picking up blocks, falling blocks, etc.
    @EventHandler(priority = EventPriority.NORMAL)
    public void onEntityChangeBlock(EntityChangeBlockEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
        {
            return;
        }

        if (isInProtectedArea(event.getBlock().getLocation()))
        {
            event.setCancelled(true);
        }
    }

    // Water/lava bucket placement
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBucketEmpty(PlayerBucketEmptyEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
        {
            return;
        }

        final Player player = event.getPlayer();
        if (plugin.al.isAdmin(player))
        {
            return;
        }

        if (isInProtectedArea(event.getBlock().getLocation()))
        {
            event.setCancelled(true);
        }
    }

    // Water/lava bucket removal
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBucketFill(PlayerBucketFillEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
        {
            return;
        }

        final Player player = event.getPlayer();
        if (plugin.al.isAdmin(player))
        {
            return;
        }

        if (isInProtectedArea(event.getBlock().getLocation()))
        {
            event.setCancelled(true);
        }
    }

    // Fire starting
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBlockIgnite(BlockIgniteEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
        {
            return;
        }

        final Player player = event.getPlayer();
        if (player != null && plugin.al.isAdmin(player))
        {
            return;
        }

        if (isInProtectedArea(event.getBlock().getLocation()))
        {
            event.setCancelled(true);
        }
    }

    // Spread into a protected area: fire, grass, mycelium, vines, and sculk
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBlockSpread(BlockSpreadEvent event)
    {
        if (ConfigEntry.PROTECTAREA_ENABLED.getBoolean()
                && isInProtectedArea(event.getBlock().getLocation()))
        {
            event.setCancelled(true);
        }
    }

    // Trees and other multi-block structures in the area
    @EventHandler(priority = EventPriority.NORMAL)
    public void onStructureGrow(StructureGrowEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
        {
            return;
        }

        final Player player = event.getPlayer();
        if (player != null && plugin.al.isAdmin(player))
        {
            return;
        }

        event.getBlocks().removeIf(state -> isInProtectedArea(state.getLocation()));
    }

    // Single-block growth: crops, sugar cane, cactus, bamboo
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBlockGrow(BlockGrowEvent event)
    {
        if (ConfigEntry.PROTECTAREA_ENABLED.getBoolean()
                && isInProtectedArea(event.getBlock().getLocation()))
        {
            event.setCancelled(true);
        }
    }

    // Blocks forming: snow layers, ice, concrete, frost walker trails, snow golem tracks
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBlockForm(BlockFormEvent event)
    {
        if (ConfigEntry.PROTECTAREA_ENABLED.getBoolean()
                && isInProtectedArea(event.getBlock().getLocation()))
        {
            event.setCancelled(true);
        }
    }

    // Blocks burning
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBlockBurn(BlockBurnEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
        {
            return;
        }

        if (isInProtectedArea(event.getBlock().getLocation()))
        {
            event.setCancelled(true);
        }
    }

    // Water/lava flow
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBlockFromTo(BlockFromToEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
        {
            return;
        }

        // Check if liquid is flowing INTO a protected area from outside
        if (!isInProtectedArea(event.getBlock().getLocation()) && isInProtectedArea(event.getToBlock().getLocation()))
        {
            event.setCancelled(true);
        }
    }

    // Piston extend
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPistonExtend(BlockPistonExtendEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
        {
            return;
        }

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
        {
            return;
        }

        for (Block block : event.getBlocks())
        {
            if (isInProtectedArea(block.getLocation()))
            {
                event.setCancelled(true);
                return;
            }
        }
    }

    // Placing paintings, item frames, etc.
    @EventHandler(priority = EventPriority.NORMAL)
    public void onHangingPlace(HangingPlaceEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
        {
            return;
        }

        final Player player = event.getPlayer();
        if (player != null && plugin.al.isAdmin(player))
        {
            return;
        }

        if (isInProtectedArea(event.getEntity().getLocation()))
        {
            event.setCancelled(true);
        }
    }

    // Breaking paintings, item frames by entity
    @EventHandler(priority = EventPriority.NORMAL)
    public void onHangingBreak(HangingBreakByEntityEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
        {
            return;
        }

        Entity remover = event.getRemover();
        if (remover instanceof Player)
        {
            Player player = (Player) remover;
            if (plugin.al.isAdmin(player))
            {
                return;
            }
        }

        if (isInProtectedArea(event.getEntity().getLocation()))
        {
            event.setCancelled(true);
        }
    }

    // Vehicle destruction (minecarts, boats)
    @EventHandler(priority = EventPriority.NORMAL)
    public void onVehicleDestroy(VehicleDestroyEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
        {
            return;
        }

        Entity attacker = event.getAttacker();
        if (attacker instanceof Player)
        {
            Player player = (Player) attacker;
            if (plugin.al.isAdmin(player))
            {
                return;
            }
        }

        if (isInProtectedArea(event.getVehicle().getLocation()))
        {
            event.setCancelled(true);
        }
    }

    // Block fade (ice melting, snow melting, etc.) - protect structure integrity
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBlockFade(BlockFadeEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
        {
            return;
        }

        if (isInProtectedArea(event.getBlock().getLocation()))
        {
            event.setCancelled(true);
        }
    }

    // Sign text editing (Minecraft 1.20+ allows editing signs after placement)
    @EventHandler(priority = EventPriority.NORMAL)
    public void onSignChange(SignChangeEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
        {
            return;
        }

        final Player player = event.getPlayer();
        if (plugin.al.isAdmin(player))
        {
            return;
        }

        if (isInProtectedArea(event.getBlock().getLocation()))
        {
            event.setCancelled(true);
        }
    }

    // Player interact (crop trampling, etc.)
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteract(PlayerInteractEvent event)
    {
        final Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        if (block == null)
        {
            return;
        }

        final Location location = block.getLocation();
        if (!shouldBlockInteraction(player, location))
        {
            return;
        }

        if (event.getAction() == org.bukkit.event.block.Action.PHYSICAL)
        {
            event.setCancelled(true);
            return;
        }

        // block right-click interactions
		if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK)
        {
            if (event.getItem() != null)
            {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event)
    {
        final Player player = event.getPlayer();
        final Location location = event.getRightClicked().getLocation();
        
        if (shouldBlockInteraction(player, location))
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
        {
            return;
        }

        if (!ConfigEntry.PROTECTAREA_PROTECT_PLAYERS.getBoolean())
        {
            return;
        }

        if (!(event.getEntity() instanceof Player))
        {
            return;
        }

        if (isInProtectedArea(event.getEntity().getLocation()))
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
        {
            return;
        }

        if (!ConfigEntry.PROTECTAREA_BLOCK_POTIONS.getBoolean())
        {
            return;
        }

        for (LivingEntity affected : event.getAffectedEntities())
        {
            if (affected instanceof Player && isInProtectedArea(affected.getLocation()))
            {
                event.setIntensity(affected, 0.0D);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLingeringPotionSplash(LingeringPotionSplashEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
        {
            return;
        }

        if (!ConfigEntry.PROTECTAREA_BLOCK_POTIONS.getBoolean())
        {
            return;
        }

        if (isInProtectedArea(event.getEntity().getLocation()))
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAreaEffectCloudApply(AreaEffectCloudApplyEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
        {
            return;
        }

        if (!ConfigEntry.PROTECTAREA_BLOCK_POTIONS.getBoolean())
        {
            return;
        }

        event.getAffectedEntities().removeIf(
                entity -> entity instanceof Player && isInProtectedArea(entity.getLocation()));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean() || !ConfigEntry.PROTECTAREA_BLOCK_ITEMS.getBoolean())
        {
            return;
        }

        if (event.getEntity() instanceof Player player && plugin.al.isAdmin(player))
        {
            return;
        }

        if (isInProtectedArea(event.getItem().getLocation()))
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryPickupItem(InventoryPickupItemEvent event)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean() || !ConfigEntry.PROTECTAREA_BLOCK_ITEMS.getBoolean())
        {
            return;
        }

        if (isInProtectedArea(event.getItem().getLocation()))
        {
            event.setCancelled(true);
        }
    }

    private boolean shouldBlockInteraction(Player player, Location location)
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
        {
            return false;
        }

        if (player != null && plugin.al.isAdmin(player))
        {
            return false;
        }

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

    /**
     * Bounds of every protected area in {@code world}, one {minX, minY, minZ, maxX, maxY, maxZ} row per area.
     * The WorldEdit hook resolves this once per operation instead of checking the live area map for every
     * block it writes.
     */
    public int[][] getBoundsIn(final World world)
    {
        final UUID worldId = world.getUID();

        return areas.values()
                    .stream()
                    .filter(area -> area.isIn(worldId))
                    .map(ProtectedRegion::bounds)
                    .toArray(int[][]::new);
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
        @Getter
        private UUID uuid;
        @Getter
        private String name;
        private Vector min;
        private Vector max;
        private UUID worldUUID;
        private World world;

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

        /**
         * Raw bounds as {minX, minY, minZ, maxX, maxY, maxZ}. Snapshotted for callers that test
         * many positions in a tight loop and must not touch the live region from another thread.
         */
        public int[] bounds()
        {
            return new int[]
            {
                min.getBlockX(), min.getBlockY(), min.getBlockZ(),
                max.getBlockX(), max.getBlockY(), max.getBlockZ()
            };
        }

        public boolean isIn(final UUID worldId)
        {
            return this.worldUUID.equals(worldId);
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

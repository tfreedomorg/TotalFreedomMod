package me.totalfreedom.totalfreedommod.blocking;

import java.util.*;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Location;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SpawnEggMeta;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;

public class MobBlocker extends FreedomService
{

    private final Map<UUID, Long> spawnEggCooldowns = new HashMap<>();
    private final Map<Location, Long> blockSpawnCooldowns = new HashMap<>();
    private final Set<Key> disabledMobs = new HashSet<>();
    private final Map<Key, Integer> globalEntityLimits = new HashMap<>();
    private final Map<Key, Integer> chunkEntityLimits = new HashMap<>();

    private static final long SPAWN_EGG_COOLDOWN_MS = 1000L;
    private static final long SPAWNER_COOLDOWN_MS = 1000L;

    private boolean isSpawnEgg(ItemStack item)
    {
        return item != null && item.getItemMeta() instanceof SpawnEggMeta;
    }

    private boolean handleSpawnEggCooldown(Player player, ItemStack item)
    {
        if (plugin.al.isAdmin(player))
            return false;

        if (!isSpawnEgg(item))
        {
            return false;
        }

        long now = System.currentTimeMillis();
        long lastUse = spawnEggCooldowns.getOrDefault(player.getUniqueId(), 0L);

        if (now - lastUse < SPAWN_EGG_COOLDOWN_MS)
        {
            player.sendMessage(Component.text("You can only use one spawn egg per second.", NamedTextColor.RED));
            return true;
        }

        spawnEggCooldowns.put(player.getUniqueId(), now);
        return false;
    }

    private boolean isBlockOnCooldown(Block block, long cooldownMs)
    {
        Location location = block.getLocation().toBlockLocation();

        long now = System.currentTimeMillis();
        long lastUse = blockSpawnCooldowns.getOrDefault(location, 0L);

        if (now - lastUse < cooldownMs)
        {
            return true;
        }

        blockSpawnCooldowns.put(location, now);
        return false;
    }

    public MobBlocker(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        loadTypes(ConfigEntry.MOB_LIMITER_DISABLED_MOBS.getStringList(), disabledMobs, "blocked mob");
        loadLimits(ConfigEntry.MOB_LIMITER_GLOBAL_LIMITS.getStringList(), globalEntityLimits, "global");
        loadLimits(ConfigEntry.MOB_LIMITER_CHUNK_LIMITS.getStringList(), chunkEntityLimits, "per-chunk");
        FLog.info("[MobLimiter] Loaded " + disabledMobs.size() + " blocked mob type(s), "
                + globalEntityLimits.size() + " global entity limit(s), and "
                + chunkEntityLimits.size() + " per-chunk entity limit(s).");
    }

    @Override
    protected void onStop()
    {
        disabledMobs.clear();
        globalEntityLimits.clear();
        chunkEntityLimits.clear();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onCreatureSpawn(CreatureSpawnEvent event)
    {
        if (!ConfigEntry.MOB_LIMITER_ENABLED.getBoolean())
        {
            return;
        }

        final Entity spawned = event.getEntity();
        if (disabledMobs.contains(spawned.getType().key()))
        {
            event.setCancelled(true);
            return;
        }

        int mobLimiterMax = ConfigEntry.MOB_LIMITER_MAX.getInteger();

        if (mobLimiterMax <= 0)
        {
            return;
        }

        long count = event.getLocation().getWorld().getEntities().stream()
                .filter(entity -> entity != spawned)
                .filter(entity -> entity instanceof LivingEntity && !(entity instanceof HumanEntity))
                .count();

        if (count >= mobLimiterMax)
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event)
    {
        if (!ConfigEntry.MOB_LIMITER_ENABLED.getBoolean())
        {
            return;
        }

        final Entity spawned = event.getEntity();
        final Key type = spawned.getType().key();
        final Integer globalLimit = globalEntityLimits.get(type);
        final Integer chunkLimit = chunkEntityLimits.get(type);
        if (globalLimit == null && chunkLimit == null)
        {
            return;
        }

        final World world = event.getLocation().getWorld();
        if (globalLimit != null && countType(world.getEntities(), spawned, type) >= globalLimit)
        {
            event.setCancelled(true);
            return;
        }

        if (chunkLimit != null
                && countType(Arrays.asList(event.getLocation().getChunk().getEntities()), spawned, type) >= chunkLimit)
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawnEggUse(PlayerInteractEvent event)
    {
        Action action = event.getAction();

        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR)
        {
            return;
        }

        if (handleSpawnEggCooldown(event.getPlayer(), event.getItem()))
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawnEggEntity(PlayerInteractEntityEvent event)
    {
        Player player = event.getPlayer();
        ItemStack itemstack = player.getInventory().getItem(event.getHand());

        if (handleSpawnEggCooldown(player, itemstack))
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDispenseSpawnEgg(BlockDispenseEvent event)
    {
        if (!isSpawnEgg(event.getItem()))
        {
            return;
        }

        if (isBlockOnCooldown(event.getBlock(), SPAWN_EGG_COOLDOWN_MS))
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawnerSpawn(SpawnerSpawnEvent event)
    {
        Block spawnerBlock = event.getSpawner().getBlock();

        if (isBlockOnCooldown(spawnerBlock, SPAWNER_COOLDOWN_MS))
        {
            event.setCancelled(true);
        }
    }

    public void blockMobType(EntityType type)
    {
        if (disabledMobs.add(type.key()))
        {
            ConfigEntry.MOB_LIMITER_DISABLED_MOBS.setList(disabledMobs.stream()
                    .map(Key::asString)
                    .sorted()
                    .toList());
            plugin.config.save();
        }
    }

    public void allowMobType(EntityType type)
    {
        if (disabledMobs.remove(type.key()))
        {
            ConfigEntry.MOB_LIMITER_DISABLED_MOBS.setList(disabledMobs.stream()
                    .map(Key::asString)
                    .sorted()
                    .toList());
            plugin.config.save();
        }
    }

    public boolean isMobTypeBlocked(EntityType type)
    {
        return disabledMobs.contains(type.key());
    }

    public List<Key> getBlockedMobTypes()
    {
        return disabledMobs.stream().sorted().toList();
    }

    public void setEntityLimit(EntityType type, boolean perChunk, int limit)
    {
        final Map<Key, Integer> limits = perChunk ? chunkEntityLimits : globalEntityLimits;
        limits.put(type.key(), limit);
        saveEntityLimits(perChunk);
    }

    public boolean removeEntityLimit(EntityType type, boolean perChunk)
    {
        final Map<Key, Integer> limits = perChunk ? chunkEntityLimits : globalEntityLimits;
        if (limits.remove(type.key()) == null)
        {
            return false;
        }
        saveEntityLimits(perChunk);
        return true;
    }

    public Map<Key, Integer> getEntityLimits(boolean perChunk)
    {
        return Map.copyOf(perChunk ? chunkEntityLimits : globalEntityLimits);
    }

    private static long countType(Collection<Entity> entities, Entity spawned, Key type)
    {
        return entities.stream()
                .filter(entity -> entity != spawned)
                .filter(entity -> entity.getType().key().equals(type))
                .count();
    }

    private void loadTypes(List<String> configuredTypes, Set<Key> destination, String description)
    {
        final Registry<EntityType> entityTypes = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENTITY_TYPE);
        configuredTypes.forEach(value ->
        {
            if (!Key.parseable(value))
            {
                FLog.warning("[MobLimiter] Ignoring invalid " + description + " type key: " + value);
                return;
            }
            final Key key = Key.key(value);
            if (entityTypes.get(key) == null)
            {
                FLog.warning("[MobLimiter] Ignoring unknown " + description + " type: " + value);
                return;
            }
            destination.add(key);
        });
    }

    private void loadLimits(List<String> configuredLimits, Map<Key, Integer> destination, String scope)
    {
        final Registry<EntityType> entityTypes = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENTITY_TYPE);
        configuredLimits.forEach(value ->
        {
            final String[] parts = value.split("=", 2);
            if (parts.length != 2 || !Key.parseable(parts[0]))
            {
                FLog.warning("[MobLimiter] Ignoring invalid " + scope + " entity limit: " + value);
                return;
            }

            final Key key = Key.key(parts[0]);
            if (entityTypes.get(key) == null)
            {
                FLog.warning("[MobLimiter] Ignoring unknown " + scope + " entity type: " + parts[0]);
                return;
            }

            try
            {
                final int limit = Integer.parseInt(parts[1]);
                if (limit <= 0)
                {
                    FLog.warning("[MobLimiter] Ignoring non-positive " + scope + " entity limit: " + value);
                    return;
                }
                destination.put(key, limit);
            }
            catch (NumberFormatException ex)
            {
                FLog.warning("[MobLimiter] Ignoring invalid " + scope + " entity limit: " + value);
            }
        });
    }

    private void saveEntityLimits(boolean perChunk)
    {
        final Map<Key, Integer> limits = perChunk ? chunkEntityLimits : globalEntityLimits;
        final ConfigEntry configEntry = perChunk ? ConfigEntry.MOB_LIMITER_CHUNK_LIMITS : ConfigEntry.MOB_LIMITER_GLOBAL_LIMITS;
        configEntry.setList(limits.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey().asString() + "=" + entry.getValue())
                .toList());
        plugin.config.save();
    }
}
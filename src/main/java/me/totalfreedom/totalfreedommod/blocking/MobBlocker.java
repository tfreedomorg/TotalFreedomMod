package me.totalfreedom.totalfreedommod.blocking;

import java.util.*;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
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
    private final List<Key> disabledMobs = new ArrayList<>();

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
        // Load mob type blacklist
        if (ConfigEntry.MOB_LIMITER_ENABLED.getBoolean())
        {
            disabledMobs.addAll(ConfigEntry.MOB_LIMITER_DISABLED_MOBS.getStringList().stream()
                    .filter(Key::parseable)
                    .map(Key::key)
                    .toList());

            FLog.info("[MobLimiter] " + disabledMobs.size() + " disabled mob types loaded.");
        }
    }

    @Override
    protected void onStop()
    {
        // Clear mob type blacklist
        disabledMobs.clear();
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

        long count = event.getLocation().getWorld().getLivingEntities().stream()
                .filter(entity -> !(entity instanceof HumanEntity))
                .count();

        if (count > mobLimiterMax)
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
        disabledMobs.add(type.key());
    }

    public void allowMobType(EntityType type)
    {
        disabledMobs.remove(type.key());
    }

    public boolean isMobTypeBlocked(EntityType type)
    {
        return disabledMobs.contains(type.key());
    }

    public List<Key> getBlockedMobTypes()
    {
        return disabledMobs;
    }
}
package me.totalfreedom.totalfreedommod;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;

public class SpawnManager extends FreedomService
{
    public SpawnManager(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
    }

    @Override
    protected void onStop()
    {
    }

    public Location getSpawnLocation()
    {
        World world = Bukkit.getWorld(ConfigEntry.SPAWN_WORLD.getString());
        if (world == null)
        {
            world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            if (world == null)
            {
                FLog.warning("Could not resolve configured spawn world.");
                return null;
            }

            FLog.warning("Configured spawn world not found; using " + world.getName() + " instead.");
        }

        return new Location(
                world,
                ConfigEntry.SPAWN_X.getDouble(),
                ConfigEntry.SPAWN_Y.getDouble(),
                ConfigEntry.SPAWN_Z.getDouble(),
                ConfigEntry.SPAWN_YAW.getDouble().floatValue(),
                ConfigEntry.SPAWN_PITCH.getDouble().floatValue());
    }

    public boolean sendToSpawn(Player player)
    {
        Location spawn = getSpawnLocation();
        if (spawn == null)
        {
            player.sendMessage("Spawn location is not available.");
            return false;
        }

        return player.teleport(spawn);
    }

    public void setSpawnLocation(Location location)
    {
        ConfigEntry.SPAWN_WORLD.setString(location.getWorld().getName());
        ConfigEntry.SPAWN_X.setDouble(location.getX());
        ConfigEntry.SPAWN_Y.setDouble(location.getY());
        ConfigEntry.SPAWN_Z.setDouble(location.getZ());
        ConfigEntry.SPAWN_YAW.setDouble((double) location.getYaw());
        ConfigEntry.SPAWN_PITCH.setDouble((double) location.getPitch());
        plugin.config.save();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        JoinSpawnPolicy policy = JoinSpawnPolicy.fromConfig(ConfigEntry.SPAWN_SEND_ON_JOIN.getString());
        if (policy == JoinSpawnPolicy.NEVER)
        {
            return;
        }

        boolean isFirstJoin = plugin.pl.getData(event.getPlayer().getName()) == null;
        if (policy == JoinSpawnPolicy.FIRST_JOIN && !isFirstJoin)
        {
            return;
        }

        Player player = event.getPlayer();
        server.getScheduler().runTask(plugin, () ->
        {
            if (player.isOnline())
            {
                sendToSpawn(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event)
    {
        if (!ConfigEntry.SPAWN_SEND_ON_RESPAWN.getBoolean())
        {
            return;
        }

        Location spawn = getSpawnLocation();
        if (spawn != null)
        {
            event.setRespawnLocation(spawn);
        }
    }

    private enum JoinSpawnPolicy
    {
        ALWAYS,
        FIRST_JOIN,
        NEVER;

        private static JoinSpawnPolicy fromConfig(String value)
        {
            if (value == null)
            {
                return FIRST_JOIN;
            }

            try
            {
                return JoinSpawnPolicy.valueOf(value.trim().toUpperCase());
            }
            catch (IllegalArgumentException ex)
            {
                FLog.warning("Invalid spawn.send_player.on_join value \"" + value + "\"; using first_join.");
                return FIRST_JOIN;
            }
        }
    }
}

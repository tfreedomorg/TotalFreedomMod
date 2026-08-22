package me.totalfreedom.totalfreedommod.world;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;

import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.player.FPlayer;

import static me.totalfreedom.totalfreedommod.util.FUtil.playerMsg;

public class WorldManager extends FreedomService
{

    public Flatlands flatlands;
    public AdminWorld adminworld;

    public WorldManager(TotalFreedomMod plugin)
    {
        super(plugin);

        this.flatlands = new Flatlands(plugin);
        this.adminworld = new AdminWorld(plugin);
    }

    @Override
    protected void onStart()
    {
        Bukkit.getScheduler().runTask(plugin, () ->
        {
            flatlands.getWorld();
            adminworld.getWorld();

        // Disable weather
            if (ConfigEntry.DISABLE_WEATHER.getBoolean())
            {
                for (World world : server.getWorlds())
                {
                    world.setThundering(false);
                    world.setStorm(false);
                    world.setThunderDuration(0);
                    world.setWeatherDuration(0);
                }
            }
        });
    }

    @Override
    protected void onStop()
    {
        World fl = Bukkit.getWorld(flatlands.getName());
        if (fl != null) fl.save();
        World aw = Bukkit.getWorld(adminworld.getName());
        if (aw != null) aw.save();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event)
    {
        final Player player = event.getPlayer();
        final FPlayer fPlayer = plugin.pl.getPlayer(player);

        if (!plugin.al.isAdmin(player) && fPlayer.getFreezeData().isFrozen())
        {
            return; // Don't process adminworld validation
        }

        adminworld.validateMovement(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event)
    {
        if (!event.hasChangedPosition())
        {
            return;
        }

        adminworld.validateMovement(event);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onThunderChange(ThunderChangeEvent event)
    {
        try
        {
            if (event.getWorld().equals(adminworld.getWorld()) && adminworld.getWeatherMode() != WorldWeather.OFF)
            {
                return;
            }
        }
        catch (Exception ex)
        {
        }

        if (ConfigEntry.DISABLE_WEATHER.getBoolean() && event.toThunderState())
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onWeatherChange(WeatherChangeEvent event)
    {
        try
        {
            if (event.getWorld().equals(adminworld.getWorld()) && adminworld.getWeatherMode() != WorldWeather.OFF)
            {
                return;
            }
        }
        catch (Exception ex)
        {
        }

        if (ConfigEntry.DISABLE_WEATHER.getBoolean() && event.toWeatherState())
        {
            event.setCancelled(true);
        }
    }

    public void gotoWorld(Player player, String targetWorld)
    {
        if (player == null)
        {
            return;
        }

        if (player.getWorld().getName().equalsIgnoreCase(targetWorld))
        {
            playerMsg(player, "Going to main world.", NamedTextColor.GRAY);
            player.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
            return;
        }

        for (World world : Bukkit.getWorlds())
        {
            if (world.getName().equalsIgnoreCase(targetWorld))
            {
                playerMsg(player, "Going to world: " + targetWorld, NamedTextColor.GRAY);
                player.teleport(world.getSpawnLocation());
                return;
            }
        }

        playerMsg(player, "World " + targetWorld + " not found.", NamedTextColor.GRAY);
    }

}

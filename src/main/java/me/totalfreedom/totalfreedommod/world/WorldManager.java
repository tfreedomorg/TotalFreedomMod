package me.totalfreedom.totalfreedommod.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;

import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.player.FPlayer;

import static me.totalfreedom.totalfreedommod.util.FUtil.playerMsg;

/**
 * The registry of {@link CustomWorld}s TFM manages, keyed by world name. A wrapper is created and
 * cached on first {@link #get}, so any world with a profile (or none at all, for the movement/weather
 * hooks below) can be addressed generically instead of needing its own hardcoded field.
 * <p>
 * Every world with a profile on disk is eagerly created at startup; there is no separate config
 * flag for any of them, flatlands and an admin world included. Wanting one gone means deleting or
 * renaming its {@code worlds/<name>.json}, not flipping a switch.
 */
public class WorldManager extends FreedomService
{
    private final Map<String, CustomWorld> managed = new HashMap<>();

    public WorldManager(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    /** The {@link CustomWorld} wrapper for a world name, creating and caching one on first use. */
    public CustomWorld get(String worldName)
    {
        return managed.computeIfAbsent(worldName, name -> new CustomWorld(plugin, name));
    }

    /** Every world name worth suggesting for a world-targeting command: every managed profile, plus every world currently loaded. */
    public List<String> worldNames()
    {
        final TreeSet<String> names = new TreeSet<>(plugin.gs.available());
        Bukkit.getWorlds().forEach(world -> names.add(world.getName()));
        return new ArrayList<>(names);
    }

    /**
     * Drops the cached access check on every world touched so far. An admin or title grant/revoke
     * can change who passes {@code access.permission()} on any of them, not just one hardcoded
     * world, so this sweeps all of {@link #managed} rather than naming one.
     */
    public void invalidateAccessCaches()
    {
        managed.values().forEach(CustomWorld::wipeAccessCache);
    }

    @Override
    protected void onStart()
    {
        Bukkit.getScheduler().runTask(plugin, () ->
        {
            // Every world with a profile on disk is a world this server runs; there is no separate
            // enable flag. Deleting or renaming worlds/<name>.json is what turns one off.
            plugin.gs.available().forEach(name ->
            {
                final World world = get(name).getWorld();

                if (world != null && isWeatherDisabled(name))
                {
                    world.setThundering(false);
                    world.setStorm(false);
                    world.setThunderDuration(0);
                    world.setWeatherDuration(0);
                }
            });
        });
    }

    /** Whether {@code worldName}'s own profile turns its weather off. False for a world with no profile. */
    private boolean isWeatherDisabled(final String worldName)
    {
        return plugin.gs.profile(worldName).map(profile -> profile.world().weatherDisabled()).orElse(false);
    }

    @Override
    protected void onStop()
    {
        managed.values().forEach(customWorld ->
        {
            World world = Bukkit.getWorld(customWorld.getName());
            if (world != null) world.save();
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event)
    {
        final Player player = event.getPlayer();
        final FPlayer fPlayer = plugin.pl.getPlayer(player);

        if (!plugin.al.isAdmin(player) && fPlayer.getFreezeData().isFrozen())
        {
            return; // Don't process managed-world access validation
        }

        validateDestination(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event)
    {
        if (!event.hasChangedPosition())
        {
            return;
        }

        validateDestination(event);
    }

    /**
     * Runs the destination world's own {@link CustomWorld#validateMovement}, whatever that world
     * turns out to be. A no-op for a world with no {@code access} section, and for one with no
     * profile at all, checked here so a world TFM doesn't manage never gets a {@link CustomWorld}
     * wrapper (and the generation attempt that comes with one) in the first place.
     */
    private void validateDestination(final PlayerMoveEvent event)
    {
        final World destination = event.getTo().getWorld();
        if (destination == null || !plugin.gs.available().contains(destination.getName()))
        {
            return;
        }

        get(destination.getName()).validateMovement(event);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onThunderChange(ThunderChangeEvent event)
    {
        try
        {
            if (get(event.getWorld().getName()).getWeatherMode() != WorldWeather.OFF)
            {
                return;
            }
        }
        catch (Exception ex)
        {
        }

        if (isWeatherDisabled(event.getWorld().getName()) && event.toThunderState())
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        managed.values().forEach(customWorld -> customWorld.forgetPlayer(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onWeatherChange(WeatherChangeEvent event)
    {
        try
        {
            if (get(event.getWorld().getName()).getWeatherMode() != WorldWeather.OFF)
            {
                return;
            }
        }
        catch (Exception ex)
        {
        }

        if (isWeatherDisabled(event.getWorld().getName()) && event.toWeatherState())
        {
            event.setCancelled(true);
        }
    }

    /**
     * Sends a player to any world by name: back to the main world if they are already in the target,
     * a managed world's own spawn (subject to its access check) if the name has a profile, or any
     * other currently loaded world's spawn otherwise.
     */
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

        if (plugin.gs.available().contains(targetWorld))
        {
            final CustomWorld customWorld = get(targetWorld);

            if (!customWorld.canAccessWorld(player))
            {
                playerMsg(player, "You don't have permission to access that world.", NamedTextColor.RED);
                return;
            }

            playerMsg(player, "Going to world: " + targetWorld, NamedTextColor.GRAY);
            customWorld.sendToWorld(player);
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

package me.totalfreedom.totalfreedommod.world;

import java.util.*;
import java.util.Map.Entry;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;

public final class AdminWorld extends CustomWorld
{

    private static final long CACHE_CLEAR_FREQUENCY = 30L * 1000L; //30 seconds, milliseconds

    /**
     * The permission that opens the admin world. Held by staff through their rank and by trusted
     * players through a title, which is the whole reason entry is a permission check rather than an
     * admin check.
     */
    private static final String ADMIN_WORLD_NODE = "tfm.world.adminworld";
    private static final long TP_COOLDOWN_TIME = 500L; //0.5 seconds, milliseconds
    private static final String GENERATION_PARAMETERS = ConfigEntry.FLATLANDS_GENERATE_PARAMS.getString();
    //
    private final Map<Player, Long> teleportCooldown = new HashMap<>();
    private final Map<CommandSender, Boolean> accessCache = new HashMap<>();
    //
    private Long cacheLastCleared = null;
    private Map<Player, Player> guestList = new HashMap<>(); // Guest, Supervisor
    private WorldWeather weather = WorldWeather.OFF;
    private WorldTime time = WorldTime.INHERIT;

    public AdminWorld(TotalFreedomMod plugin)
    {
        super(plugin, "adminworld", "Admin World");
    }

    @Override
    public void sendToWorld(Player player)
    {
        if (!canAccessWorld(player))
        {
            return;
        }

        super.sendToWorld(player);
    }

    @Override
    protected World generateWorld()
    {
        final WorldCreator worldCreator = new WorldCreator(getName());
        worldCreator.generateStructures(false);
        worldCreator.type(WorldType.NORMAL);
        worldCreator.environment(World.Environment.NORMAL);
        worldCreator.generator(new CleanroomChunkGenerator(GENERATION_PARAMETERS));

        final World world = Bukkit.getServer().createWorld(worldCreator);

        world.setSpawnFlags(false, false);
        world.setSpawnLocation(0, 50, 0);

        return world;
    }

    public boolean addGuest(Player guest, Player supervisor)
    {
        if (guest == supervisor || plugin.al.isAdmin(guest))
        {
            return false;
        }

        if (plugin.al.isAdmin(supervisor))
        {
            guestList.put(guest, supervisor);
            wipeAccessCache();
            return true;
        }

        return false;
    }

    public boolean hasGuests()
    {
        return !guestList.isEmpty();
    }


    public boolean removeGuest(Player guest)
    {
        final boolean success = guestList.remove(guest) != null;
        if (success)
        {
            wipeAccessCache();
        }
        return success;
    }

    public Player removeGuest(String partialName)
    {
        partialName = partialName.toLowerCase();
        final Iterator<Player> it = guestList.keySet().iterator();

        while (it.hasNext())
        {
            final Player player = it.next();
            if (player.getName().toLowerCase().contains(partialName))
            {
                removeGuest(player);
                return player;
            }
        }

        return null;
    }

    public String guestListToString()
    {
        final List<String> output = new ArrayList<>();
        for (Entry<Player, Player> entry : guestList.entrySet())
        {
            final Player player = entry.getKey();
            final Player supervisor = entry.getValue();
            output.add(player.getName() + " (Supervisor: " + supervisor.getName() + ")");
        }
        return String.join(", ", output);
    }

    public void purgeGuestList()
    {
        guestList.clear();
        wipeAccessCache();
    }

    public boolean validateMovement(PlayerMoveEvent event)
    {
        World world;
        try
        {
            world = getWorld();
        }
        catch (Exception ex)
        {
            return true;
        }

        if (world == null || !event.getTo().getWorld().equals(world))
        {
            return true;
        }

        final Player player = event.getPlayer();
        if (canAccessWorld(player))
        {
            return true;
        }

        Long lastTP = teleportCooldown.get(player);

        long currentTimeMillis = System.currentTimeMillis();
        if (lastTP == null || lastTP + TP_COOLDOWN_TIME <= currentTimeMillis)
        {
            teleportCooldown.put(player, currentTimeMillis);
            FLog.info(player.getName() + " attempted to access the AdminWorld.");
            event.setTo(Bukkit.getWorlds().get(0).getSpawnLocation());
        }
        return false;
    }

    public void wipeAccessCache()
    {
        cacheLastCleared = System.currentTimeMillis();
        accessCache.clear();
    }

    public boolean canAccessWorld(final Player player)
    {
        long currentTimeMillis = System.currentTimeMillis();
        if (cacheLastCleared == null || cacheLastCleared.longValue() + CACHE_CLEAR_FREQUENCY <= currentTimeMillis)
        {
            cacheLastCleared = currentTimeMillis;
            accessCache.clear();
        }

        Boolean cached = accessCache.get(player);
        if (cached == null)
        {
            // Asked as a permission rather than as "is this an admin", so that a title can open the
            // world to someone trusted without promoting them. Admins still pass on their rank;
            // a titled player passes on the title's own grant and gains nothing else by it.
            boolean canAccess = plugin.rm.hasPermission(player, ADMIN_WORLD_NODE);
            if (!canAccess)
            {
                Player supervisor = guestList.get(player);
                canAccess = supervisor != null && supervisor.isOnline() && plugin.al.isAdmin(supervisor);
                if (!canAccess)
                {
                    guestList.remove(player);
                }
            }
            cached = canAccess;
            accessCache.put(player, cached);
        }
        return cached;
    }

    public WorldWeather getWeatherMode()
    {
        return weather;
    }

    public void setWeatherMode(final WorldWeather weatherMode)
    {
        this.weather = weatherMode;

        try
        {
            weatherMode.setWorldToWeather(getWorld());
        }
        catch (Exception ex)
        {
        }
    }

    public WorldTime getTimeOfDay()
    {
        return time;
    }

    public void setTimeOfDay(final WorldTime timeOfDay)
    {
        this.time = timeOfDay;

        try
        {
            timeOfDay.setWorldToTime(getWorld());
        }
        catch (Exception ex)
        {
        }
    }

}

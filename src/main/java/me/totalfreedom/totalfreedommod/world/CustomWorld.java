package me.totalfreedom.totalfreedommod.world;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.apache.commons.io.FileUtils;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.framework.PluginComponent;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.world.adapter.ProfileChunkGenerator;
import me.totalfreedom.totalfreedommod.world.profile.WorldSettings;

/**
 * A world TFM creates and manages itself, generated from {@code worlds/<name>.json} as a 
 * profile through {@link GenerationService}. Caches the {@link World} once generated and rebuilds it
 * if Bukkit ever drops it from {@link Bukkit#getWorlds()}.
 * <p>
 * A world whose profile declares {@link WorldSettings.Access} gets a guest list, permission-gated
 * entry, and its own weather/time controls on top. 
 * A world without one is open to everyone and none of that state does anything.
 * <p>
 * A welcome sign is planted at whatever the world's own spawn location turns out to be.
 */
public final class CustomWorld extends PluginComponent<TotalFreedomMod>
{
    private static final long ACCESS_CACHE_CLEAR_FREQUENCY = 30L * 1000L; // 30 seconds, milliseconds
    private static final long TP_COOLDOWN_TIME = 500L; // 0.5 seconds, milliseconds
    private static final String PROFILE_MARKER_FILENAME = "tfm-profile.json";

    private final String name;
    private final String displayName;
    //
    private World world;
    //
    private final Map<Player, Long> teleportCooldown = new HashMap<>();
    private final Map<CommandSender, Boolean> accessCache = new HashMap<>();
    private final Map<Player, Player> guestList = new HashMap<>(); // Guest, Supervisor
    private Long accessCacheLastCleared = null;
    private WorldWeather weather = WorldWeather.OFF;
    private WorldTime time = WorldTime.INHERIT;

    public CustomWorld(TotalFreedomMod plugin, String name, String displayName)
    {
        super(plugin);
        this.name = name;
        this.displayName = displayName;
    }

    public CustomWorld(TotalFreedomMod plugin, String name)
    {
        this(plugin, name, name);
    }

    public String getName()
    {
        return this.name;
    }

    public String getDisplayName()
    {
        return this.displayName;
    }

    public World getWorld()
    {
        if (world != null && Bukkit.getWorlds().contains(world))
        {
            return world;
        }

        world = generateWorld();

        if (world == null)
        {
            FLog.warning("Could not load world: " + name);
            return null;
        }

        placeWelcomeSign(world);
        plugin.gr.enforceGameRuleDefaultsForWorld(world);

        return world;
    }

    /**
     * Wipes this world's folder first if flagged, archives it if its profile changed since it was
     * last generated, then builds it from its own generation profile.
     */
    private World generateWorld()
    {
        wipeIfFlagged();

        final Optional<GenerationProfile> profile = plugin.gs.profile(this.name);

        if (profile.isEmpty())
        {
            FLog.severe("No generation profile for world \"" + name + "\"; it will not be created.");
            return null;
        }

        final Optional<String> fingerprint = plugin.gs.generationFingerprint(this.name);
        fingerprint.ifPresent(this::archiveIfProfileChanged);

        final WorldSettings settings = profile.get().world();
        final WorldCreator worldCreator = WorldCreator.ofKey(NamespacedKey.minecraft(this.name));
        worldCreator.environment(settings.environment());
        worldCreator.generateStructures(settings.generateStructures());
        worldCreator.generator(new ProfileChunkGenerator(profile.get()));
        settings.seed().ifPresent(seed -> worldCreator.seed(seed.longValue()));

        final World createdWorld = Bukkit.getServer().createWorld(worldCreator);

        if (createdWorld == null)
            return null;

        fingerprint.ifPresent(this::writeProfileMarker);

        return createdWorld;
    }

    /**
     * A world's generation settings (shape/palette/features/environment/generateStructures/seed/
     * vanilla) are treated as constant once generated; a behavioral flag like weatherDisabled or
     * blocking.monsters is not, since those are read live wherever they matter and can change
     * freely. If this world already has data on disk carrying a marker from a different generation
     * fingerprint than {@code currentFingerprint}, moves that data aside to {@code <name>_OLD} (or a
     * numbered variant if that is taken too) rather than growing new chunks against old ones
     * generated under different settings, which seams visibly at the border. Data with no marker at
     * all is treated the same as a mismatch, since it predates this fingerprint and cannot be
     * assumed to match it.
     */
    private void archiveIfProfileChanged(final String currentFingerprint)
    {
        final File worldFolder = worldFolder();
        if (!worldFolder.isDirectory())
            return;

        final File marker = new File(worldFolder, PROFILE_MARKER_FILENAME);

        if (marker.isFile())
        {
            try
            {
                if (Files.readString(marker.toPath()).equals(currentFingerprint))
                    return;
            }
            catch (final IOException ex)
            {
                FLog.warning("Could not read the profile marker for world \"" + name + "\", leaving its data as is: " + ex.getMessage());
                return;
            }
        }

        archiveStaleWorld(worldFolder);
    }

    /**
     * Where this world's data actually lives on disk. A profile-driven world is created through
     * {@link WorldCreator#ofKey} with a {@code minecraft} namespace, so Paper stores it as a
     * dimension nested under the primary world rather than as a sibling folder.
     */
    private File worldFolder()
    {
        final File primary = Bukkit.getWorlds().get(0).getWorldFolder();
        return new File(primary, "dimensions/minecraft/" + this.name);
    }

    private void archiveStaleWorld(final File worldFolder)
    {
        File target = new File(this.name + "_OLD");
        int suffix = 2;
        while (target.exists())
        {
            target = new File(this.name + "_OLD_" + suffix);
            suffix++;
        }

        try
        {
            Files.move(worldFolder.toPath(), target.toPath());
            FLog.info("World \"" + name + "\"'s profile changed since it was last generated; archived its old data to \"" + target.getName() + "\".");
        }
        catch (final IOException ex)
        {
            FLog.severe("Could not archive stale data for world \"" + name + "\" (tried to move it to \"" + target.getName() + "\"): "
                    + ex.getMessage() + ". It will be regenerated in place, which may show seams at existing chunk borders.");
        }
    }

    private void writeProfileMarker(final String currentFingerprint)
    {
        try
        {
            Files.writeString(new File(worldFolder(), PROFILE_MARKER_FILENAME).toPath(), currentFingerprint);
        }
        catch (final IOException ex)
        {
            FLog.warning("Could not write the profile marker for world \"" + name + "\": " + ex.getMessage());
        }
    }

    private void wipeIfFlagged()
    {
        final String flagKey = "do_wipe_" + this.name;
        boolean doWipe = false;
        try
        {
            doWipe = plugin.sf.getSavedFlag(flagKey);
        }
        catch (Exception ex)
        {
        }

        if (!doWipe)
            return;

        if (Bukkit.getServer().getWorld(this.name) != null)
        {
            FLog.severe("Can't wipe " + this.name + ", it is already loaded.");
            return;
        }

        FLog.info("Wiping " + this.name + ".");
        plugin.sf.setSavedFlag(flagKey, false);
        FileUtils.deleteQuietly(worldFolder());
    }

    private void placeWelcomeSign(final World world)
    {
        final Block welcomeSignBlock = world.getSpawnLocation().getBlock();
        welcomeSignBlock.setType(Material.OAK_SIGN);

        final org.bukkit.block.data.type.Sign signData = (org.bukkit.block.data.type.Sign) Material.OAK_SIGN.createBlockData();
        signData.setRotation(BlockFace.NORTH);
        welcomeSignBlock.setBlockData(signData);

        final Sign welcomeSign = (Sign) welcomeSignBlock.getState();

        final Component[] lines = {
                Component.text(this.displayName, NamedTextColor.GREEN),
                Component.text("---", NamedTextColor.DARK_GRAY),
                Component.text("Spawn Point", NamedTextColor.YELLOW),
                Component.text("---", NamedTextColor.DARK_GRAY)
        };

        final SignSide front = welcomeSign.getSide(Side.FRONT);
        final SignSide back = welcomeSign.getSide(Side.BACK);

        for (int i = 0; i < lines.length; i++)
        {
            front.line(i, lines[i]);
            back.line(i, lines[i]);
        }

        welcomeSign.update();
    }

    public void sendToWorld(Player player)
    {
        if (!canAccessWorld(player))
        {
            return;
        }

        try
        {
            player.teleport(getWorld().getSpawnLocation());
        }
        catch (Exception ex)
        {
            player.sendMessage(ex.getMessage());
        }
    }

    /**
     * True for anyone if this world's profile has no {@link WorldSettings.Access} section; otherwise
     * gated on {@code access.permission()}, with a guest bypassing it while their supervising admin
     * is online and still an admin.
     */
    public boolean canAccessWorld(final Player player)
    {
        final Optional<WorldSettings.Access> access = plugin.gs.profile(this.name).flatMap(p -> p.world().access());

        if (access.isEmpty())
            return true;

        long currentTimeMillis = System.currentTimeMillis();
        if (accessCacheLastCleared == null || accessCacheLastCleared.longValue() + ACCESS_CACHE_CLEAR_FREQUENCY <= currentTimeMillis)
        {
            accessCacheLastCleared = currentTimeMillis;
            accessCache.clear();
        }

        Boolean cached = accessCache.get(player);
        if (cached == null)
        {
            boolean canAccess = plugin.rm.hasPermission(player, access.get().permission());
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
            FLog.info(player.getName() + " attempted to access " + this.name + ".");
            event.setTo(Bukkit.getWorlds().get(0).getSpawnLocation());
        }
        return false;
    }

    public void wipeAccessCache()
    {
        accessCacheLastCleared = System.currentTimeMillis();
        accessCache.clear();
    }

    public void forgetPlayer(final Player player)
    {
        teleportCooldown.remove(player);
        accessCache.remove(player);
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

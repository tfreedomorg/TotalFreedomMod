package me.totalfreedom.totalfreedommod.lockup;

import java.util.*;
import java.io.*;
import java.lang.reflect.Type;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.api.FreedomAPI;
import me.totalfreedom.totalfreedommod.player.FPlayer;
import me.totalfreedom.totalfreedommod.util.*;

import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

/**
 * Holds standing lockups and keeps them applied.
 * <p>
 * A lockup is keyed by address rather than by player, which is what makes it
 * worth anything against a spambot: everyone online from that address is caught 
 * at once, and anyone who joins from it later walks straight back into it.
 * Lockups persist to lockups.json, so a restart resumes them with whatever
 * time they had left; one whose 24 hours ran out while the server was down is 
 * dropped on load rather than re-served.
 */
public class LockupManager extends FreedomService
{
    private static final long INVENTORY_INTERVAL_TICKS = 5L;
    private static final Type LOCKUP_MAP_TYPE = new TypeToken<Map<String, LockupEntry>>() {}.getType();
    private static final long SWEEP_INTERVAL_TICKS = 20L;
    private static final PotionEffect BLINDNESS = new PotionEffect(PotionEffectType.BLINDNESS, 
        PotionEffect.INFINITE_DURATION, 0, false, false, false);
    public static final long LOCKUP_DURATION_MS = TimeUnit.HOURS.toMillis(24L);

    private final Map<String, LockupEntry> lockups = new ConcurrentHashMap<>(); // key: ip
    private final File configFile;

    private BukkitTask sweepTask;

    public LockupManager(FreedomAPI plugin)
    {
        super(plugin);
        this.configFile = new File(plugin.getDataFolder(), "lockups.json");
    }

    @Override
    public void onStart()
    {
        load();
        server.getOnlinePlayers()
              .stream()
              .map(Player.class::cast)
              .filter(this::isLockedUp)
              .forEach(this::applyEffects);

        sweepTask = server.getScheduler().runTaskTimer(plugin,
                FTask.guard("LockupManager/sweep", this::sweep),
                SWEEP_INTERVAL_TICKS, SWEEP_INTERVAL_TICKS);
    }

    @Override
    public void onStop()
    {
        FUtil.cancel(sweepTask);
        sweepTask = null;

        server.getOnlinePlayers()
              .stream()
              .map(plugin.players()::getPlayer)
              .forEach(this::stopInventoryLoop);

        save();
        lockups.clear();
    }

    public boolean isLockedUp(Player player)
    {
        return !plugin.admins().isAdmin(player) && heldOn(plugin.players().getPlayer(player).getIp());
    }

    /**
     * The same test from a netty thread. Chat events fire off the main thread where
     * PlayerList's and AdminList's maps are not safe to touch, so both go through their
     * Sync variants.
     */
    private boolean isLockedUpAsync(Player player)
    {
        return !plugin.admins().isAdminSync(player) && heldOn(plugin.players().getPlayerSync(player).getIp());
    }

    private boolean heldOn(String ip)
    {
        final LockupEntry entry = lockups.get(ip);
        return entry != null && !entry.isExpired(System.currentTimeMillis());
    }

    public List<LockupEntry> getLockups()
    {
        return List.copyOf(lockups.values());
    }

    public List<Player> lockup(CommandSender admin, Player target)
    {
        final String ip = plugin.players().getPlayer(target).getIp();

        lockups.put(ip, new LockupEntry(ip, admin.getName(), target.getName(),
                    System.currentTimeMillis() + LOCKUP_DURATION_MS));
        save();

        final List<Player> held = playersOn(ip).stream()
                                               .filter(player -> !plugin.admins().isAdmin(player))
                                               .toList();

        held.forEach(this::applyEffects);
        return held;
    }

    public List<Player> release(String ip)
    {
        if (lockups.remove(ip) == null)
            return List.of();

        final List<Player> freed = playersOn(ip);
        freed.forEach(this::clearEffects);
        save();
        return freed;
    }

    public int purge()
    {
        final List<String> ips = List.copyOf(lockups.keySet());
        ips.forEach(this::release);
        return ips.size();
    }

    public Optional<LockupEntry> findByTarget(String target)
    {
        final LockupEntry byIp = lockups.get(target);
        if (byIp != null)
            return Optional.of(byIp);

        final Player online = server.getPlayerExact(target);
        if (online != null)
        {
            final LockupEntry byAddress = lockups.get(plugin.players().getPlayer(online).getIp());
            if (byAddress != null)
                return Optional.of(byAddress);
        }

        return lockups.values()
                      .stream()
                      .filter(entry -> entry.username().equalsIgnoreCase(target))
                      .findFirst();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        final Player player = event.getPlayer();

        if (isLockedUp(player))
            applyEffects(player);
    }

    /**
     * Command blocking is enforced here rather than through {@link FPlayer#setCommandsBlocked}
     * because that flag persists to the player's row, and unlike mute and freeze never expires
     * on its own. A restart mid-lockup would otherwise leave the target with blocked commands
     * and no lockup left to lift it.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event)
    {
        if (isLockedUp(event.getPlayer()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncChat(AsyncChatEvent event)
    {
        if (isLockedUpAsync(event.getPlayer()))
            event.setCancelled(true);
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLegacyAsyncChat(AsyncPlayerChatEvent event)
    {
        if (isLockedUpAsync(event.getPlayer()))
            event.setCancelled(true);
    }

    private void sweep()
    {
        if (lockups.isEmpty())
            return;

        final long now = System.currentTimeMillis();

        lockups.values()
               .stream()
               .filter(entry -> entry.isExpired(now))
               .toList()
               .forEach(this::expire);

        server.getOnlinePlayers()
              .stream()
              .map(Player.class::cast)
              .filter(this::isLockedUp)
              .forEach(this::applyEffects);
    }

    private void expire(LockupEntry entry)
    {
        release(entry.ip());

        FUtil.adminAction("TotalFreedom",
                String.format("Lifting the lockup on %s, 24 hours elapsed",
                entry.username()), false);
    }

    private void applyEffects(Player player)
    {
        final FPlayer fPlayer = plugin.players().getPlayer(player);

        if (!fPlayer.getFreezeData().isFrozen())
            fPlayer.getFreezeData().setFrozen(true);

        if (!player.hasPotionEffect(PotionEffectType.BLINDNESS))
            player.addPotionEffect(BLINDNESS);

        startInventoryLoop(player, fPlayer);
    }

    private void clearEffects(Player player)
    {
        final FPlayer fPlayer = plugin.players().getPlayer(player);

        fPlayer.getFreezeData().setFrozen(false);
        player.removePotionEffect(PotionEffectType.BLINDNESS);

        stopInventoryLoop(fPlayer);
    }

    private void startInventoryLoop(Player player, FPlayer fPlayer)
    {
        if (fPlayer.getLockupScheduleID() != null)
            return;

        fPlayer.setLockupScheduleId(server.getScheduler().runTaskTimer(plugin, 
                FTask.guard("LockupManager/inventory", () -> reopenInventory(player, fPlayer)),
                0L, INVENTORY_INTERVAL_TICKS));
    }

    private void reopenInventory(Player player, FPlayer fPlayer)
    {
        if (!player.isOnline())
        {
            stopInventoryLoop(fPlayer);
            return;
        }

        player.openInventory(player.getInventory());
    }

    private void stopInventoryLoop(FPlayer fPlayer)
    {
        FUtil.cancel(fPlayer.getLockupScheduleID());
        fPlayer.setLockupScheduleId(null);
    }

    private List<Player> playersOn(String ip)
    {
        return server.getOnlinePlayers()
                     .stream()
                     .map(Player.class::cast)
                     .filter(player -> ip.equals(plugin.players().getPlayer(player).getIp()))
                     .toList();
    }

    private void load()
    {
        lockups.clear();

        if (!configFile.exists())
            return;

        final long now = System.currentTimeMillis();

        try (FileReader reader = new FileReader(configFile))
        {
            final Map<String, LockupEntry> loaded = JsonUtil.GSON.fromJson(reader, LOCKUP_MAP_TYPE);

            if (loaded == null)
                return;

            loaded.values()
                  .stream()
                  .filter(entry -> entry != null && entry.ip() != null)
                  .filter(entry -> !entry.isExpired(now))
                  .forEach(entry -> lockups.put(entry.ip(), entry));
        }
        catch (IOException | JsonParseException ex)
        {
            FLog.error(String.format("Could not read lockups.json: %s", ex.getMessage()));
        }

        FLog.info(String.format("Loaded %d lockup(s).", lockups.size()));
    }

    private void save()
    {
        final Map<String, LockupEntry> snapshot = Map.copyOf(lockups);

        if (!plugin.isEnabled())
        {
            writeJson(snapshot);
            return;
        }

        server.getScheduler().runTaskAsynchronously(plugin,
                FTask.guard("LockupManager/save", () -> writeJson(snapshot)));
    }

    private synchronized void writeJson(Map<String, LockupEntry> snapshot)
    {
        try (FileWriter writer = new FileWriter(configFile))
        {
            JsonUtil.GSON.toJson(snapshot, LOCKUP_MAP_TYPE, writer);
        }
        catch (IOException ex)
        {
            FLog.error(String.format("Could not save lockups.json: %s", ex.getMessage()));
        }
    }
}
package me.totalfreedom.totalfreedommod.banish;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;

import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;

import me.totalfreedom.api.FreedomAPI;
import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;
import me.totalfreedom.totalfreedommod.util.JsonUtil;

public class BanishManager extends FreedomService
{
    private static final Type BANISH_MAP_TYPE = new TypeToken<Map<String, BanishEntry>>() {}.getType();
    private static final int MAX_PACKET_EVENTS_ATTEMPTS = 15;
    private static final long PACKET_EVENTS_RETRY_TICKS = 40L;
    private static final long ENFORCEMENT_INTERVAL_TICKS = 20L;
    private static final long MAX_BANISH_DURATION_MS = TimeUnit.HOURS.toMillis(24);
    private static final String BANISH_MESSAGE = "You are banished from this server.";

    private final Map<String, BanishEntry> banishments = new ConcurrentHashMap<>();
    private final Set<UUID> restrictedPlayers = ConcurrentHashMap.newKeySet();
    private final File configFile;

    private Object registeredListener;
    private BukkitTask packetEventsRetryTask;
    private BukkitTask enforcementTask;
    private volatile boolean packetListenerActive;
    private volatile boolean stopped;

    public BanishManager(FreedomAPI plugin)
    {
        super(plugin);
        this.configFile = new File(plugin.getDataFolder(), "banishments.json");
    }

    @Override
    public void onStart()
    {
        stopped = false;
        load();
        attemptPacketEventsRegistration(0);
        enforcementTask = server.getScheduler().runTaskTimer(plugin, this::enforceOnlinePlayers,
                ENFORCEMENT_INTERVAL_TICKS, ENFORCEMENT_INTERVAL_TICKS);
        enforceOnlinePlayers();
    }

    @Override
    public void onStop()
    {
        stopped = true;
        FUtil.cancel(packetEventsRetryTask);
        FUtil.cancel(enforcementTask);
        packetEventsRetryTask = null;
        enforcementTask = null;
        save();

        if (registeredListener != null)
        {
            try
            {
                PacketEvents.getAPI().getEventManager()
                        .unregisterListener((PacketListenerCommon) registeredListener);
            }
            catch (RuntimeException | LinkageError ex)
            {
                FLog.warn("[BanishManager] Could not unregister the PacketEvents listener: " + ex.getMessage());
            }

            registeredListener = null;
            packetListenerActive = false;
        }
    }

    public boolean isPacketListenerActive()
    {
        return packetListenerActive;
    }

    public Collection<BanishEntry> getBanishments()
    {
        return List.copyOf(banishments.values());
    }

    public List<Player> banish(Player target, String issuedBy, String reason, long expiresAt)
    {
        if (!packetListenerActive)
            throw new IllegalStateException("PacketEvents is not active. Banish was not applied.");

        final long now = System.currentTimeMillis();
        if (expiresAt <= now || expiresAt - now > MAX_BANISH_DURATION_MS)
            throw new IllegalStateException("Banishments must expire within 24 hours.");

        final String ip = plugin.players().getData(target).getIp();
        if (ip == null || ip.isBlank())
            throw new IllegalStateException("Could not determine the player's connection address.");

        if (banishments.containsKey(ip))
            throw new IllegalStateException("That player is already banished.");

        final BanishEntry entry = new BanishEntry(ip, target.getName(), issuedBy, reason,
                System.currentTimeMillis(), expiresAt);
        banishments.put(ip, entry);
        if (!save())
        {
            banishments.remove(ip, entry);
            throw new IllegalStateException("Could not persist the banishment. No restriction was applied.");
        }

        final List<Player> affected = playersOn(ip);
        affected.forEach(this::applyBanishment);
        return affected;
    }

    public Optional<BanishEntry> release(String target)
    {
        final Optional<BanishEntry> entry = findByTarget(target);
        if (entry.isEmpty())
            return Optional.empty();

        final BanishEntry banishment = entry.get();
        if (!banishments.remove(banishment.ip(), banishment))
            return Optional.empty();

        if (!save())
        {
            banishments.put(banishment.ip(), banishment);
            throw new IllegalStateException("Could not persist the release. The banishment remains active.");
        }

        restorePlayers(banishment);

        return Optional.of(banishment);
    }

    public Optional<BanishEntry> findByTarget(String target)
    {
        final BanishEntry byIp = banishments.get(target);
        if (byIp != null)
            return Optional.of(byIp);

        final Player online = server.getPlayerExact(target);
        if (online != null)
        {
            final BanishEntry byAddress = banishments.get(plugin.players().getData(online).getIp());
            if (byAddress != null)
                return Optional.of(byAddress);
        }

        return banishments.values()
                .stream()
                .filter(entry -> entry.username().equalsIgnoreCase(target))
                .findFirst();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        final Player player = event.getPlayer();
        if (plugin.ranks().isAdmin(player))
            return;

        final BanishEntry entry = banishments.get(plugin.players().getData(player).getIp());
        if (entry == null)
            return;

        if (!packetListenerActive)
        {
            player.kick(Component.text(BANISH_MESSAGE));
            return;
        }

        applyBanishment(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        restrictedPlayers.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event)
    {
        if (isRestricted(event.getPlayer()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event)
    {
        if (isRestricted(event.getPlayer()))
            event.setCancelled(true);
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLegacyAsyncChat(AsyncPlayerChatEvent event)
    {
        if (isRestricted(event.getPlayer()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event)
    {
        if (isRestricted(event.getPlayer()) && event.getNewGameMode() != GameMode.SPECTATOR)
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event)
    {
        if (isRestricted(event.getPlayer()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event)
    {
        if (isRestricted(event.getPlayer()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event)
    {
        if (isRestricted(event.getPlayer()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event)
    {
        if (isRestricted(event.getPlayer()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerItemHeld(PlayerItemHeldEvent event)
    {
        if (isRestricted(event.getPlayer()))
            event.setCancelled(true);
    }

    private void attemptPacketEventsRegistration(int attempt)
    {
        if (stopped || registeredListener != null)
            return;

        Plugin packetEvents = server.getPluginManager().getPlugin("packetevents");
        if (packetEvents == null)
            packetEvents = server.getPluginManager().getPlugin("PacketEvents");

        if (packetEvents != null && packetEvents.isEnabled())
        {
            try
            {
                registeredListener = PacketEvents.getAPI().getEventManager()
                        .registerListener(new BanishPacketListener());
                packetListenerActive = true;
                enforceOnlinePlayers();
                FLog.info("[BanishManager] Packet-level spectator restrictions enabled.");
            }
            catch (RuntimeException | LinkageError ex)
            {
                packetListenerActive = false;
                FLog.error("[BanishManager] Could not register the PacketEvents listener: " + ex.getMessage());
                FLog.error(ex);
                retryPacketEventsRegistration(attempt);
            }
            return;
        }

        if (attempt >= MAX_PACKET_EVENTS_ATTEMPTS)
        {
            FLog.warn("[BanishManager] PacketEvents is not enabled. Existing banished players will be disconnected and new banishments are unavailable.");
            kickRestrictedPlayers();
            return;
        }

        retryPacketEventsRegistration(attempt);
    }

    private void retryPacketEventsRegistration(int attempt)
    {
        if (attempt >= MAX_PACKET_EVENTS_ATTEMPTS)
        {
            FLog.warn("[BanishManager] Could not activate PacketEvents. Existing banished players will be disconnected and new banishments are unavailable.");
            kickRestrictedPlayers();
            return;
        }

        packetEventsRetryTask = server.getScheduler().runTaskLater(plugin,
                () -> attemptPacketEventsRegistration(attempt + 1), PACKET_EVENTS_RETRY_TICKS);
    }

    private boolean isRestricted(Player player)
    {
        return restrictedPlayers.contains(player.getUniqueId());
    }

    private void enforceOnlinePlayers()
    {
        expireBanishments();

        for (Player player : server.getOnlinePlayers())
        {
            if (plugin.ranks().isAdmin(player))
            {
                restrictedPlayers.remove(player.getUniqueId());
                continue;
            }

            final BanishEntry entry = banishments.get(plugin.players().getData(player).getIp());
            if (entry == null)
                continue;

            if (!packetListenerActive)
            {
                player.kick(Component.text(BANISH_MESSAGE));
                continue;
            }

            applyBanishment(player);
        }
    }

    private void applyBanishment(Player player)
    {
        restrictedPlayers.add(player.getUniqueId());
        if (player.getGameMode() != GameMode.SPECTATOR)
            player.setGameMode(GameMode.SPECTATOR);
    }

    private void kickRestrictedPlayers()
    {
        for (Player player : server.getOnlinePlayers())
        {
            if (!plugin.ranks().isAdmin(player)
                    && banishments.containsKey(plugin.players().getData(player).getIp()))
                player.kick(Component.text(BANISH_MESSAGE));
        }
    }

    private void expireBanishments()
    {
        final long now = System.currentTimeMillis();
        final List<BanishEntry> expired = banishments.values()
                .stream()
                .filter(entry -> entry.expiresAt() > 0 && entry.expiresAt() <= now)
                .toList();

        if (expired.isEmpty())
            return;

        expired.forEach(entry -> banishments.remove(entry.ip(), entry));
        if (!save())
        {
            return;
        }

        expired.forEach(this::restorePlayers);
    }

    private void restorePlayers(BanishEntry entry)
    {
        playersOn(entry.ip()).forEach(player ->
        {
            restrictedPlayers.remove(player.getUniqueId());
            if (player.getGameMode() == GameMode.SPECTATOR)
                player.setGameMode(GameMode.CREATIVE);
        });
    }

    private List<Player> playersOn(String ip)
    {
        return server.getOnlinePlayers()
                .stream()
                .map(Player.class::cast)
                .filter(player -> !plugin.ranks().isAdmin(player))
                .filter(player -> ip.equals(plugin.players().getData(player).getIp()))
                .toList();
    }

    private void load()
    {
        banishments.clear();
        if (!configFile.exists())
            return;

        try (FileReader reader = new FileReader(configFile))
        {
            final Map<String, BanishEntry> loaded = JsonUtil.GSON.fromJson(reader, BANISH_MAP_TYPE);
            if (loaded == null)
                return;

            final long now = System.currentTimeMillis();
            loaded.forEach((ip, entry) ->
            {
                if (ip != null && !ip.isBlank() && entry != null && ip.equals(entry.ip()))
                {
                    if (entry.createdAt() <= 0)
                    {
                        FLog.warn("[BanishManager] Discarding a banishment with no valid creation time.");
                        return;
                    }

                    final long maxExpiry = entry.createdAt() > Long.MAX_VALUE - MAX_BANISH_DURATION_MS
                            ? Long.MAX_VALUE
                            : entry.createdAt() + MAX_BANISH_DURATION_MS;
                    final long expiresAt = Math.min(
                            entry.expiresAt() <= 0 ? maxExpiry : entry.expiresAt(),
                            Math.min(maxExpiry, now + MAX_BANISH_DURATION_MS));
                    banishments.put(ip, new BanishEntry(entry.ip(), entry.username(), entry.issuedBy(),
                            entry.reason(), entry.createdAt(), expiresAt));
                }
            });
            if (!save())
                FLog.warn("[BanishManager] Could not persist banishment expiry migration.");
        }
        catch (IOException | JsonParseException ex)
        {
            FLog.error("[BanishManager] Could not read banishments.json: " + ex.getMessage());
        }

        FLog.info(String.format("Loaded %d banishment(s).", banishments.size()));
    }

    private boolean save()
    {
        try (FileWriter writer = new FileWriter(configFile))
        {
            JsonUtil.GSON.toJson(Map.copyOf(banishments), BANISH_MAP_TYPE, writer);
            return true;
        }
        catch (IOException ex)
        {
            FLog.error("[BanishManager] Could not save banishments.json: " + ex.getMessage());
            return false;
        }
    }

    private static boolean isAllowedSpectatorPacket(PacketTypeCommon type)
    {
        // Default deny keeps gameplay actions blocked as new client packet types are added.
        return type == PacketType.Play.Client.TELEPORT_CONFIRM
                || type == PacketType.Play.Client.CLIENT_STATUS
                || type == PacketType.Play.Client.CLIENT_SETTINGS
                || type == PacketType.Play.Client.KEEP_ALIVE
                || type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
                || type == PacketType.Play.Client.PLAYER_ROTATION
                || type == PacketType.Play.Client.PLAYER_FLYING
                || type == PacketType.Play.Client.PLAYER_INPUT
                || type == PacketType.Play.Client.PLAYER_LOADED
                || type == PacketType.Play.Client.CLIENT_TICK_END
                || type == PacketType.Play.Client.CHUNK_BATCH_ACK
                || type == PacketType.Play.Client.CONFIGURATION_ACK
                || type == PacketType.Play.Client.PONG
                || type == PacketType.Play.Client.CHAT_ACK
                || type == PacketType.Play.Client.SPECTATE
                || type == PacketType.Play.Client.SPECTATE_ENTITY;
    }

    private final class BanishPacketListener extends PacketListenerAbstract
    {
        private BanishPacketListener()
        {
            super(PacketListenerPriority.HIGHEST);
        }

        @Override
        public void onPacketReceive(PacketReceiveEvent event)
        {
            final UUID uuid = event.getUser().getUUID();
            if (uuid == null || !restrictedPlayers.contains(uuid))
                return;

            if (!isAllowedSpectatorPacket(event.getPacketType()))
                event.setCancelled(true);
        }
    }
}

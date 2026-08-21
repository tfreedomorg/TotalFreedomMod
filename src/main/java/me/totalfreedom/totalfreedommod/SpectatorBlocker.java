package me.totalfreedom.totalfreedommod;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;

import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FTask;

public class SpectatorBlocker extends FreedomService
{

    private static final long RETRY_INTERVAL_TICKS = 40L;
    private static final int MAX_ATTEMPTS = 15;

    private final Set<UUID> restrictedSpectators = ConcurrentHashMap.newKeySet();

    private Object registeredListener;
    private BukkitTask refreshTask;
    private volatile boolean stopped;

    public SpectatorBlocker(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        stopped = false;
        attemptRegister(0);
    }

    private void attemptRegister(int attempt)
    {
        if (stopped || registeredListener != null)
        {
            return;
        }

        Plugin packetEvents = server.getPluginManager().getPlugin("packetevents");

        if (packetEvents == null)
        {
            packetEvents = server.getPluginManager().getPlugin("PacketEvents");
        }

        if (packetEvents != null && packetEvents.isEnabled())
        {
            register();
            return;
        }

        if (attempt >= MAX_ATTEMPTS)
        {
            FLog.warning("[SpectatorBlocker] PacketEvents is not enabled. Spectator teleports will still be cancelled, but the teleport menu will not be grayed out.");
            return;
        }

        server.getScheduler().runTaskLater(plugin, FTask.guard("SpectatorBlocker/attemptRegister", () -> attemptRegister(attempt + 1)), RETRY_INTERVAL_TICKS);
    }

    private void register()
    {
        try
        {
            registeredListener = PacketEvents.getAPI().getEventManager()
                    .registerListener(new SpectatorPlayerInfoListener());

            refreshTask = server.getScheduler().runTaskTimer(plugin,
                FTask.guard("SpectatorBlocker/refreshAll", () ->
                {
                    for (Player player : server.getOnlinePlayers())
                    {
                        refreshPlayer(player);
                    }
                }), 1L, 20L);

            FLog.info("[SpectatorBlocker] PacketEvents spectator menu guard enabled.");
        }
        catch (Throwable ex)
        {
            FLog.severe("[SpectatorBlocker] Could not register the PacketEvents listener.");
            FLog.severe(ex);
        }
    }

    @Override
    protected void onStop()
    {
        stopped = true;

        if (refreshTask != null)
        {
            refreshTask.cancel();
            refreshTask = null;
        }

        for (UUID uuid : restrictedSpectators)
        {
            final Player player = server.getPlayer(uuid);

            if (player != null && player.isOnline())
            {
                sendPlayerGameModes(player, false);
            }
        }

        restrictedSpectators.clear();

        if (registeredListener != null)
        {
            try
            {
                PacketEvents.getAPI().getEventManager()
                        .unregisterListener((PacketListenerCommon) registeredListener);
            }
            catch (Throwable ex)
            {
                FLog.warning("[SpectatorBlocker] Could not unregister the PacketEvents listener: " + ex.getMessage());
            }

            registeredListener = null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerGameModeChange(PlayerGameModeChangeEvent event)
    {
        final Player player = event.getPlayer();

        server.getScheduler().runTask(plugin, FTask.guard("SpectatorBlocker/refreshOnGameMode", () -> refreshPlayer(player)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        final Player player = event.getPlayer();

        server.getScheduler().runTaskLater(plugin, FTask.guard("SpectatorBlocker/refreshOnJoin", () -> refreshPlayer(player)), 1L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        restrictedSpectators.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event)
    {
        if (!ConfigEntry.BLOCK_SPECTATOR_TELEPORT.getBoolean())
        {
            return;
        }

        if (event.getCause() != PlayerTeleportEvent.TeleportCause.SPECTATE)
        {
            return;
        }

        final Player player = event.getPlayer();

        if (plugin.al.isAdmin(player))
        {
            return;
        }

        event.setCancelled(true);

        player.sendMessage(Component.text(
                "Spectator teleporting is restricted to admins.",
                NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerStartSpectatingEntity(PlayerStartSpectatingEntityEvent event)
    {
        if (!ConfigEntry.BLOCK_SPECTATOR_TELEPORT.getBoolean())
        {
            return;
        }

        final Player player = event.getPlayer();
        final Entity target = event.getNewSpectatorTarget();

        if (!(target instanceof Player) || target.equals(player))
        {
            return;
        }

        if (plugin.al.isAdmin(player))
        {
            return;
        }

        event.setCancelled(true);

        player.sendMessage(Component.text(
                "Spectating other players is restricted to admins.",
                NamedTextColor.RED));
    }

    private void refreshPlayer(Player player)
    {
        if (registeredListener == null || player == null || !player.isOnline())
        {
            return;
        }

        final boolean shouldRestrict =
                ConfigEntry.BLOCK_SPECTATOR_TELEPORT.getBoolean()
                        && player.getGameMode() == GameMode.SPECTATOR
                        && !plugin.al.isAdmin(player);

        final boolean currentlyRestricted =
                restrictedSpectators.contains(player.getUniqueId());

        if (shouldRestrict == currentlyRestricted)
        {
            return;
        }

        if (shouldRestrict)
        {
            restrictedSpectators.add(player.getUniqueId());
            sendPlayerGameModes(player, true);
        }
        else
        {
            restrictedSpectators.remove(player.getUniqueId());
            sendPlayerGameModes(player, false);
        }
    }

    private void sendPlayerGameModes(Player viewer, boolean hideTargets)
    {
        if (registeredListener == null || viewer == null || !viewer.isOnline())
        {
            return;
        }

        final List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> entries =
                new ArrayList<>();

        for (Player target : server.getOnlinePlayers())
        {
            final WrapperPlayServerPlayerInfoUpdate.PlayerInfo playerInfo =
                    new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(target.getUniqueId());

            playerInfo.setGameMode(hideTargets
                    ? com.github.retrooper.packetevents.protocol.player.GameMode.SPECTATOR
                    : getPacketGameMode(target.getGameMode()));

            entries.add(playerInfo);
        }

        if (entries.isEmpty())
        {
            return;
        }

        final WrapperPlayServerPlayerInfoUpdate packet =
                new WrapperPlayServerPlayerInfoUpdate(
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
                        entries);

        PacketEvents.getAPI().getPlayerManager()
                .sendPacketSilently(viewer, packet);
    }

    private com.github.retrooper.packetevents.protocol.player.GameMode getPacketGameMode(GameMode gameMode)
    {
        return switch (gameMode)
        {
            case SURVIVAL -> com.github.retrooper.packetevents.protocol.player.GameMode.SURVIVAL;
            case CREATIVE -> com.github.retrooper.packetevents.protocol.player.GameMode.CREATIVE;
            case ADVENTURE -> com.github.retrooper.packetevents.protocol.player.GameMode.ADVENTURE;
            case SPECTATOR -> com.github.retrooper.packetevents.protocol.player.GameMode.SPECTATOR;
        };
    }

    private final class SpectatorPlayerInfoListener extends PacketListenerAbstract
    {

        private SpectatorPlayerInfoListener()
        {
            super(PacketListenerPriority.HIGH);
        }

        @Override
        public void onPacketSend(PacketSendEvent event)
        {
            if (event.getPacketType() != PacketType.Play.Server.PLAYER_INFO_UPDATE)
            {
                return;
            }

            final UUID viewerUuid = event.getUser().getUUID();

            if (viewerUuid == null || !restrictedSpectators.contains(viewerUuid))
            {
                return;
            }

            try
            {
                final WrapperPlayServerPlayerInfoUpdate packet =
                        new WrapperPlayServerPlayerInfoUpdate(event);

                if (!packet.getActions().contains(
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE))
                {
                    return;
                }

                for (WrapperPlayServerPlayerInfoUpdate.PlayerInfo playerInfo
                        : packet.getEntries())
                {
                    playerInfo.setGameMode(
                            com.github.retrooper.packetevents.protocol.player.GameMode.SPECTATOR);
                }

                event.markForReEncode(true);
            }
            catch (Throwable ignored)
            {
            }
        }
    }
}
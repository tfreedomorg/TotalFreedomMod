package me.totalfreedom.totalfreedommod.blocking.packet;

import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.blocking.entity.EntityMetaPacketGuard;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;

public class CrashPacketService extends FreedomService
{

    private static final long RETRY_INTERVAL_TICKS = 40L;
    private static final int MAX_ATTEMPTS = 15;

    private Object registeredListener;
    private PacketSpamLimiter spamLimiter;
    private MovementGuard movementGuard;
    private volatile boolean stopped;

    public CrashPacketService(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        stopped = false;

        Snapshot snapshot = Snapshot.read();
        if (!snapshot.anyHookEnabled())
        {
            return;
        }

        attemptRegister(snapshot, 0);
    }

    private void attemptRegister(Snapshot snapshot, int attempt)
    {
        if (stopped || registeredListener != null)
        {
            return;
        }

        Plugin pe = server.getPluginManager().getPlugin("packetevents");
        if (pe == null)
        {
            pe = server.getPluginManager().getPlugin("PacketEvents");
        }

        if (pe != null && pe.isEnabled())
        {
            try
            {
                register(snapshot);
            }
            catch (Throwable t)
            {
                FLog.severe("[CrashPacketService] Failed to register PacketEvents listener: " + t.getMessage());
                FLog.severe(t);
                registeredListener = null;
                spamLimiter = null;
            }
            return;
        }

        if (attempt >= MAX_ATTEMPTS)
        {
            FLog.warning("[CrashPacketService] PacketEvents not present after waiting; crash packet "
                    + "guards and inbound rate limiting are disabled.");
            return;
        }

        server.getScheduler().runTaskLater(plugin, () -> attemptRegister(snapshot, attempt + 1), RETRY_INTERVAL_TICKS);
    }

    private void register(Snapshot snapshot)
    {
        if (snapshot.rateLimit)
        {
            spamLimiter = new PacketSpamLimiter(snapshot.maxInteractions, snapshot.maxCommands, snapshot.maxMovement,
                    snapshot.maxHeldSwitches, snapshot.maxSuggestions, snapshot.maxGameModeSwitches);
        }

        if (snapshot.movementGuardEnabled)
        {
            movementGuard = new MovementGuard(snapshot.maxHorizontalDelta, snapshot.maxOversizedMovesPerSecond,
                    snapshot.maxBlocksPerSecond);
        }

        CommandDepthGuard commandDepthGuard = snapshot.commandDepthGuard
                ? new CommandDepthGuard(snapshot.maxCommandDepth)
                : null;

        registeredListener = PacketEvents.getAPI().getEventManager()
                .registerListener(new CrashPacketListener(plugin, snapshot.itemGuard, snapshot.entityMetadataGuard,
                        snapshot.entityLimits, spamLimiter, movementGuard, commandDepthGuard,
                        snapshot.signGuard, snapshot.signChunkGuard, snapshot.blockAllSignPackets,
                        snapshot.spawnerGuard, snapshot.spawnerChunkGuard,
                        snapshot.containerGuard, snapshot.containerChunkGuard));

        FLog.info("[CrashPacketService] PacketEvents hooks active"
                + (snapshot.itemGuard ? " [itemGuard]" : "")
                + (snapshot.entityMetadataGuard ? " [entityMetadataGuard]" : "")
                + (snapshot.rateLimit ? " [rateLimit]" : "")
                + (snapshot.movementGuardEnabled ? " [movementGuard]" : "")
                + (snapshot.emptyChatGuard ? " [emptyChatGuard]" : "")
                + (snapshot.signGuard ? " [signGuard]" : "")
                + (snapshot.signChunkGuard ? " [signChunkGuard]" : "")
                + (snapshot.blockAllSignPackets ? " [blockAllSignPackets]" : "")
                + (snapshot.spawnerGuard ? " [spawnerGuard]" : "")
                + (snapshot.spawnerChunkGuard ? " [spawnerChunkGuard]" : "")
                + (snapshot.containerGuard ? " [containerGuard]" : "")
                + (snapshot.containerChunkGuard ? " [containerChunkGuard]" : "")
                + (snapshot.commandDepthGuard ? " [commandDepthGuard]" : "")
                + ".");
    }

    private record Snapshot(
            boolean itemGuard,
            boolean entityMetadataGuard,
            EntityMetaPacketGuard.Limits entityLimits,
            boolean rateLimit,
            boolean movementGuardEnabled,
            boolean emptyChatGuard,
            boolean signGuard,
            boolean signChunkGuard,
            boolean blockAllSignPackets,
            boolean spawnerGuard,
            boolean spawnerChunkGuard,
            boolean containerGuard,
            boolean containerChunkGuard,
            int maxInteractions,
            int maxCommands,
            int maxMovement,
            int maxHeldSwitches,
            int maxSuggestions,
            int maxGameModeSwitches,
            int maxHorizontalDelta,
            int maxOversizedMovesPerSecond,
            int maxBlocksPerSecond,
            boolean commandDepthGuard,
            int maxCommandDepth)
    {
        private static Snapshot read()
        {
            boolean entityMetadataGuard = Boolean.TRUE.equals(ConfigEntry.CRASH_ENTITIES_PREVENT.getBoolean());
            return new Snapshot(
                    Boolean.TRUE.equals(ConfigEntry.CRASH_ITEMS_PACKET_GUARD.getBoolean()),
                    entityMetadataGuard,
                    entityMetadataGuard ? readEntityLimits() : null,
                    Boolean.TRUE.equals(ConfigEntry.CRASH_ITEMS_PACKET_RATE_LIMIT.getBoolean()),
                    Boolean.TRUE.equals(ConfigEntry.MOVE_GUARD_ENABLED.getBoolean()),
                    ConfigEntry.ANTISPAM_ENABLED.getBoolean(true),
                    Boolean.TRUE.equals(ConfigEntry.CRASH_SIGNS_PACKET_GUARD.getBoolean()),
                    Boolean.TRUE.equals(ConfigEntry.CRASH_SIGNS_CHUNK_GUARD.getBoolean()),
                    Boolean.FALSE.equals(ConfigEntry.ALLOW_SIGN_PLACE.getBoolean()),
                    Boolean.TRUE.equals(ConfigEntry.CRASH_SPAWNERS_PACKET_GUARD.getBoolean()),
                    Boolean.TRUE.equals(ConfigEntry.CRASH_SPAWNERS_CHUNK_GUARD.getBoolean()),
                    Boolean.TRUE.equals(ConfigEntry.CRASH_CONTAINERS_PACKET_GUARD.getBoolean()),
                    Boolean.TRUE.equals(ConfigEntry.CRASH_CONTAINERS_CHUNK_GUARD.getBoolean()),
                    ConfigEntry.CRASH_ITEMS_MAX_INTERACTIONS_PER_SECOND.getInteger(),
                    ConfigEntry.CRASH_ITEMS_MAX_COMMANDS_PER_SECOND.getInteger(),
                    ConfigEntry.CRASH_ITEMS_MAX_MOVEMENT_PER_SECOND.getInteger(),
                    intOr(ConfigEntry.CRASH_ITEMS_MAX_HOTBAR_SLOTS_PER_SECOND.getInteger(), 25),
                    intOr(ConfigEntry.CRASH_ITEMS_MAX_COMMAND_SUGGESTIONS_PER_SECOND.getInteger(), 10),
                    intOr(ConfigEntry.CRASH_ITEMS_MAX_GAMEMODE_SWITCHES_PER_SECOND.getInteger(), 8),
                    intOr(ConfigEntry.MOVE_GUARD_SPEED_MAX_HORIZONTAL_DELTA.getInteger(), 128),
                    intOr(ConfigEntry.MOVE_GUARD_SPEED_MAX_TELEPORTS_PER_SECOND.getInteger(), 5),
                    intOr(ConfigEntry.MOVE_GUARD_SPEED_MAX_BLOCKS_PER_SECOND.getInteger(), 100),
                    Boolean.TRUE.equals(ConfigEntry.CRASH_ITEMS_COMMAND_DEPTH_GUARD.getBoolean()),
                    intOr(ConfigEntry.CRASH_ITEMS_MAX_COMMAND_DEPTH.getInteger(), 30));
        }

        private static int intOr(Integer value, int fallback)
        {
            return value == null ? fallback : value;
        }

        private static EntityMetaPacketGuard.Limits readEntityLimits()
        {
            int maxNameLength = intOr(ConfigEntry.CRASH_ENTITIES_MAX_NAME_LENGTH.getInteger(), 64);
            int maxNodes = ConfigEntry.maxComponentNodes();
            Double maxScale = ConfigEntry.CRASH_ENTITIES_MAX_SCALE.getDouble();
            return new EntityMetaPacketGuard.Limits(maxNameLength, maxNodes, maxScale == null ? 4.0 : maxScale);
        }

        private boolean anyHookEnabled()
        {
            return itemGuard || entityMetadataGuard || rateLimit || movementGuardEnabled || emptyChatGuard
                    || signGuard || signChunkGuard
                    || blockAllSignPackets || spawnerGuard || spawnerChunkGuard || containerGuard || containerChunkGuard
                    || commandDepthGuard;
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        final java.util.UUID id = event.getPlayer().getUniqueId();
        if (spamLimiter != null)
        {
            spamLimiter.forget(id);
        }
        if (movementGuard != null)
        {
            movementGuard.forget(id);
        }
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event)
    {
        if (movementGuard != null)
        {
            movementGuard.forget(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event)
    {
        if (movementGuard != null)
        {
            movementGuard.forget(event.getPlayer().getUniqueId());
        }
    }

    @Override
    protected void onStop()
    {
        stopped = true;

        if (spamLimiter != null)
        {
            spamLimiter.clear();
            spamLimiter = null;
        }

        if (movementGuard != null)
        {
            movementGuard.clear();
            movementGuard = null;
        }

        if (registeredListener == null)
        {
            return;
        }
        try
        {
            PacketEvents.getAPI().getEventManager().unregisterListener((PacketListenerCommon) registeredListener);
        }
        catch (Throwable t)
        {
            FLog.warning("[CrashPacketService] Failed to unregister PacketEvents listener: " + t.getMessage());
        }
        finally
        {
            registeredListener = null;
        }
    }
}

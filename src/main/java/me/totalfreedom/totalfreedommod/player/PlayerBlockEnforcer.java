package me.totalfreedom.totalfreedommod.player;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.chat.ChatType;
import com.github.retrooper.packetevents.protocol.chat.message.ChatMessage;
import com.github.retrooper.packetevents.protocol.chat.message.ChatMessage_v1_16;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.score.FixedScoreFormat;
import com.github.retrooper.packetevents.protocol.score.ScoreFormat;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChatMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisguisedChat;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.scoreboard.numbers.FixedFormat;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import io.papermc.paper.scoreboard.numbers.StyledFormat;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.admin.Admin;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.framework.FatalServiceStartupException;
import me.totalfreedom.totalfreedommod.util.AdventureUtil;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;
import me.totalfreedom.totalfreedommod.util.FTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Event.Result;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Team;

/**
 * Applies the directed player block graph to live server behavior.
 */
public class PlayerBlockEnforcer extends FreedomService
{
    private static final Duration SEPARATION_GRACE = Duration.ofSeconds(10);
    private static final Duration TEMPORARY_REVEAL = Duration.ofSeconds(5);
    private static final long TEMPORARY_REVEAL_TICKS = 100L;
    private static final long STATE_CLEANUP_TICKS = 20L;
    private static final long IDENTITY_REFRESH_TICKS = 20L;
    private static final double TEMPORARY_REVEAL_RADIUS = 10.0;
    private static final double TEMPORARY_REVEAL_RADIUS_SQUARED =
            TEMPORARY_REVEAL_RADIUS * TEMPORARY_REVEAL_RADIUS;
    private static final String IDENTITY_MANAGEMENT_MARKER = "tfm:block-management";
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

    private final Set<VisibilityPair> hiddenPairs = new HashSet<>();
    private final Map<VisibilityPair, BukkitTask> revealTasks = new HashMap<>();
    private final Map<UUID, Set<String>> observedOnlineIdentities = new HashMap<>();
    private final PlayerBlockRuntimeState runtimeState = new PlayerBlockRuntimeState(
            System::nanoTime,
            SEPARATION_GRACE,
            TEMPORARY_REVEAL);
    private BukkitTask adminRefreshTask;
    private BukkitTask identityRefreshTask;
    private BukkitTask stateCleanupTask;
    private PacketListenerCommon blockedIdentityPacketListener;
    private boolean serviceReloadInProgress;
    private volatile Set<UUID> immunePlayerUuids = Set.of();
    private volatile Map<UUID, Set<String>> playerIdentitySnapshots = Map.of();
    private volatile Map<UUID, BlockedIdentityFilter> blockedIdentityFilters = Map.of();
    private volatile boolean packetFailureLogged;

    public PlayerBlockEnforcer(final TotalFreedomMod plugin)
    {
        super(plugin);
    }

    /**
     * Checks a directed block while treating active admins as immune block targets.
     * This method only reads immutable/concurrent state and is safe from async chat handlers.
     */
    public boolean isBlocked(final UUID blocker, final UUID blocked)
    {
        return !immunePlayerUuids.contains(blocked) && plugin.pbl.hasBlocked(blocker, blocked);
    }

    public boolean areSeparated(final UUID first, final UUID second)
    {
        return isBlocked(first, second)
                || isBlocked(second, first)
                || (!isImmune(first)
                        && !isImmune(second)
                        && runtimeState.isInSeparationGrace(first, second));
    }

    /**
     * Starts symmetric direct-contact separation after a normal unblock.
     */
    public void beginSeparationGrace(final UUID first, final UUID second)
    {
        requireMainThread();
        if (isImmune(first) || isImmune(second))
        {
            runtimeState.clearSeparationGrace(first, second);
            return;
        }
        runtimeState.beginSeparationGrace(first, second);
    }

    /**
     * Removes post-unblock separation, normally for a staff force-unblock.
     */
    public void clearSeparationGrace(final UUID first, final UUID second)
    {
        requireMainThread();
        runtimeState.clearSeparationGrace(first, second);
    }

    /**
     * Redacts identities blocked by one viewer without flattening component styling.
     * This only reads immutable snapshots and is safe on chat and packet threads.
     */
    public Component redactBlockedIdentities(final UUID viewer, final Component component)
    {
        Objects.requireNonNull(viewer, "viewer");
        final BlockedIdentityFilter filter = blockedIdentityFilters.get(viewer);
        return filter == null ? component : filter.redact(component);
    }

    /**
     * Sends an explicit block-management response without hiding the identities that the player
     * asked to manage. The marker is removed from the packet before it reaches the client.
     */
    public void sendIdentityManagementMessage(final Player player, final Component component)
    {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(component, "component");
        player.sendMessage(component.insertion(IDENTITY_MANAGEMENT_MARKER));
    }

    /**
     * Incrementally adds one target to a viewer's redaction matcher.
     */
    public void addBlockedIdentity(final UUID viewer, final UUID target)
    {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(target, "target");
        requireMainThread();
        if (server.getPlayer(viewer) == null || !isBlocked(viewer, target))
            return;

        final Map<UUID, Set<String>> identities = refreshPlayerIdentitySnapshot(target);
        BlockedIdentityFilter filter = blockedIdentityFilters.get(viewer);
        if (filter == null)
        {
            filter = new BlockedIdentityFilter();
            filter.replaceTarget(target, getKnownPlayerIdentities(target, identities));
            final Map<UUID, BlockedIdentityFilter> refreshedFilters = new HashMap<>(blockedIdentityFilters);
            refreshedFilters.put(viewer, filter);
            blockedIdentityFilters = Map.copyOf(refreshedFilters);
            return;
        }
        filter.replaceTarget(target, getKnownPlayerIdentities(target, identities));
    }

    /**
     * Publishes a display-name change before the player can send another command message.
     */
    public void refreshPlayerIdentity(final Player player)
    {
        Objects.requireNonNull(player, "player");
        if (!Bukkit.isPrimaryThread())
        {
            server.getScheduler().runTask(
                    plugin,
                    FTask.guard(
                            "PlayerBlockEnforcer/refreshPlayerIdentity",
                            () -> refreshPlayerIdentity(player)));
            return;
        }
        reconcileCurrentIdentityOwnership(player);
        if (plugin.pbl.isBlockedByAnyone(player.getUniqueId()))
            refreshPlayerIdentitySnapshot(player.getUniqueId());
    }

    /**
     * Incrementally removes one target from a viewer's redaction matcher.
     */
    public void removeBlockedIdentity(final UUID viewer, final UUID target)
    {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(target, "target");
        requireMainThread();
        final BlockedIdentityFilter filter = blockedIdentityFilters.get(viewer);
        if (filter != null)
        {
            filter.removeTarget(target);
            if (filter.isEmpty())
                removeBlockedIdentityFilter(viewer, filter);
        }
        pruneOfflineIdentitySnapshot(target);
    }

    /**
     * Removes all cached identity aliases for one viewer after clearing their list.
     */
    public void clearBlockedIdentities(final UUID viewer, final Set<UUID> removedTargets)
    {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(removedTargets, "removedTargets");
        requireMainThread();
        final BlockedIdentityFilter filter = blockedIdentityFilters.get(viewer);
        if (filter != null)
            removeBlockedIdentityFilter(viewer, filter);

        final Set<UUID> removableTargets = removedTargets.stream()
                .filter(target -> server.getPlayer(target) == null)
                .filter(target -> !plugin.pbl.isBlockedByAnyone(target))
                .collect(Collectors.toUnmodifiableSet());
        if (!removableTargets.isEmpty())
        {
            final Map<UUID, Set<String>> retainedIdentities = new HashMap<>(playerIdentitySnapshots);
            if (retainedIdentities.keySet().removeAll(removableTargets))
                playerIdentitySnapshots = Map.copyOf(retainedIdentities);
        }
    }

    public boolean isImmune(final UUID player)
    {
        Objects.requireNonNull(player, "player");
        return immunePlayerUuids.contains(player);
    }

    public boolean hasBlockedAnyone(final UUID player)
    {
        Objects.requireNonNull(player, "player");
        return plugin.pbl.hasBlockedAnyone(player);
    }

    public boolean isBlockedByAnyone(final UUID player)
    {
        Objects.requireNonNull(player, "player");
        return plugin.pbl.isBlockedByAnyone(player);
    }

    /**
     * Re-evaluates one directed visibility relationship after a block command changes it.
     */
    public void refreshVisibility(final Player viewer, final Player target)
    {
        if (!Bukkit.isPrimaryThread())
        {
            server.getScheduler().runTask(
                    plugin,
                    FTask.guard("PlayerBlockEnforcer/refreshVisibility", () -> refreshVisibility(viewer, target)));
            return;
        }

        refreshAdminSnapshot();
        if (!isBlocked(viewer.getUniqueId(), target.getUniqueId()))
        {
            clearTemporaryReveal(new VisibilityPair(viewer.getUniqueId(), target.getUniqueId()));
        }
        applyVisibility(viewer, target);
    }

    /**
     * Re-evaluates every relationship involving a player, normally after they join.
     */
    public void refreshVisibility(final Player player)
    {
        if (!Bukkit.isPrimaryThread())
        {
            server.getScheduler().runTask(
                    plugin,
                    FTask.guard("PlayerBlockEnforcer/refreshPlayerVisibility", () -> refreshVisibility(player)));
            return;
        }

        refreshAdminSnapshot();
        refreshPlayerIdentityRelationships(player);
        server.getOnlinePlayers()
                .stream()
                .filter(other -> !other.getUniqueId().equals(player.getUniqueId()))
                .forEach(other ->
                {
                    applyVisibility(player, other);
                    applyVisibility(other, player);
                });
    }

    @Override
    protected void onStart()
    {
        refreshAdminSnapshot();
        refreshIdentitySnapshots();
        refreshAllVisibility();
        registerBlockedIdentityPacketListener();
        adminRefreshTask = server.getScheduler().runTaskTimer(
                plugin,
                FTask.guard("PlayerBlockEnforcer/refreshAdmins", this::refreshAdminSnapshot),
                1L,
                1L);
        identityRefreshTask = server.getScheduler().runTaskTimer(
                plugin,
                FTask.guard("PlayerBlockEnforcer/refreshIdentities", this::refreshIdentitySnapshotsIfChanged),
                IDENTITY_REFRESH_TICKS,
                IDENTITY_REFRESH_TICKS);
        stateCleanupTask = server.getScheduler().runTaskTimer(
                plugin,
                FTask.guard("PlayerBlockEnforcer/pruneState", runtimeState::pruneExpired),
                STATE_CLEANUP_TICKS,
                STATE_CLEANUP_TICKS);
    }

    @Override
    protected void onStop()
    {
        if (adminRefreshTask != null)
        {
            adminRefreshTask.cancel();
            adminRefreshTask = null;
        }

        if (stateCleanupTask != null)
        {
            stateCleanupTask.cancel();
            stateCleanupTask = null;
        }

        if (identityRefreshTask != null)
        {
            identityRefreshTask.cancel();
            identityRefreshTask = null;
        }

        if (serviceReloadInProgress)
            return;

        revealTasks.values().forEach(BukkitTask::cancel);
        revealTasks.clear();
        unregisterBlockedIdentityPacketListener();

        hiddenPairs.forEach(pair ->
        {
            final Player viewer = server.getPlayer(pair.viewer());
            final Player target = server.getPlayer(pair.target());

            if (viewer != null && target != null)
            {
                viewer.showPlayer(plugin, target);
                syncTeamMembership(viewer, target, true);
                syncScoreEntries(viewer, target, true);
            }
        });
        hiddenPairs.clear();
        runtimeState.clear();
        playerIdentitySnapshots = Map.of();
        blockedIdentityFilters = Map.of();
        observedOnlineIdentities.clear();
        packetFailureLogged = false;
    }

    public void beginServiceReload()
    {
        requireMainThread();
        serviceReloadInProgress = true;
    }

    public void endServiceReload()
    {
        requireMainThread();
        serviceReloadInProgress = false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAsyncChat(final AsyncChatEvent event)
    {
        final UUID sender = event.getPlayer().getUniqueId();
        event.viewers().removeIf(viewer -> viewer instanceof Player player
                && isBlocked(player.getUniqueId(), sender));

        final ChatRenderer renderer = event.renderer();
        event.renderer((source, sourceDisplayName, message, viewer) ->
        {
            final Component rendered = renderer.render(source, sourceDisplayName, message, viewer);
            return viewer instanceof final Player player
                    ? redactBlockedIdentities(player.getUniqueId(), rendered)
                    : rendered;
        });
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLegacyAsyncChat(final AsyncPlayerChatEvent event)
    {
        final UUID sender = event.getPlayer().getUniqueId();
        event.getRecipients().removeIf(player -> isBlocked(player.getUniqueId(), sender));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerJoin(final PlayerJoinEvent event)
    {
        final Player player = event.getPlayer();
        refreshVisibility(player);
        server.getScheduler().runTaskLater(
                plugin,
                FTask.guard("PlayerBlockEnforcer/refreshOnJoin", () -> refreshVisibility(player)),
                1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(final PlayerQuitEvent event)
    {
        final UUID player = event.getPlayer().getUniqueId();
        final Set<String> releasedIdentities = observedOnlineIdentities.remove(player);
        if (releasedIdentities != null && !releasedIdentities.isEmpty())
        {
            server.getScheduler().runTask(
                    plugin,
                    FTask.guard(
                            "PlayerBlockEnforcer/restoreReleasedIdentities",
                            () -> restoreReleasedIdentityAliases(releasedIdentities)));
        }
        revealTasks.entrySet().removeIf(entry ->
        {
            final VisibilityPair pair = entry.getKey();
            if (!pair.viewer().equals(player) && !pair.target().equals(player))
                return false;

            entry.getValue().cancel();
            return true;
        });
        runtimeState.clearTemporaryRevealsFor(player);
        hiddenPairs.removeIf(pair -> pair.viewer().equals(player) || pair.target().equals(player));
        final BlockedIdentityFilter filter = blockedIdentityFilters.get(player);
        if (filter != null)
            removeBlockedIdentityFilter(player, filter);
        if (!plugin.pbl.isBlockedByAnyone(player))
        {
            final Map<UUID, Set<String>> refreshedIdentities = new HashMap<>(playerIdentitySnapshots);
            refreshedIdentities.remove(player);
            playerIdentitySnapshots = Map.copyOf(refreshedIdentities);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event)
    {
        revealToNearbyBlockers(event.getPlayer(), event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(final BlockPlaceEvent event)
    {
        revealToNearbyBlockers(event.getPlayer(), event.getBlockPlaced().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(final PlayerInteractEvent event)
    {
        if (event.getClickedBlock() == null || event.useInteractedBlock() == Result.DENY)
            return;

        revealToNearbyBlockers(event.getPlayer(), event.getClickedBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(final PlayerBucketEmptyEvent event)
    {
        revealToNearbyBlockers(event.getPlayer(), event.getBlockClicked().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(final PlayerBucketFillEvent event)
    {
        revealToNearbyBlockers(event.getPlayer(), event.getBlockClicked().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerContactCommand(final PlayerCommandPreprocessEvent event)
    {
        final TeleportPair pair = resolveDirectTeleport(event.getPlayer(), event.getMessage());
        final boolean blockedTeleport = pair != null
                && !plugin.al.isAdmin(event.getPlayer())
                && isTeleportBlocked(pair);
        final boolean blockedVanillaContact = isBlockedVanillaContactCommand(
                event.getPlayer(),
                event.getMessage());
        if (!blockedTeleport && !blockedVanillaContact)
            return;

        event.setCancelled(true);
        FUtil.playerMsg(event.getPlayer(), "Player not found.", NamedTextColor.RED);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteractEntity(final PlayerInteractEntityEvent event)
    {
        if (!(event.getRightClicked() instanceof final Player target))
        {
            return;
        }

        if (areSeparated(event.getPlayer().getUniqueId(), target.getUniqueId()))
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(final EntityDamageByEntityEvent event)
    {
        if (!(event.getEntity() instanceof final Player target))
        {
            return;
        }

        final UUID attacker = resolveAttackingPlayer(event);
        if (attacker != null && areSeparated(attacker, target.getUniqueId()))
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionSplash(final PotionSplashEvent event)
    {
        final Optional<UUID> shooter = resolveProjectileOwner(
                event.getPotion().getOwnerUniqueId(),
                event.getPotion().getShooter());
        if (shooter.isEmpty())
            return;
        final UUID shooterId = shooter.orElseThrow();

        event.getAffectedEntities()
                .stream()
                .filter(Player.class::isInstance)
                .map(Player.class::cast)
                .filter(target -> areSeparated(shooterId, target.getUniqueId()))
                .forEach(target -> event.setIntensity(target, 0.0));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAreaEffectCloudApply(final AreaEffectCloudApplyEvent event)
    {
        final AreaEffectCloud cloud = event.getEntity();
        final Optional<UUID> source = resolveProjectileOwner(cloud.getOwnerUniqueId(), cloud.getSource());
        if (source.isEmpty())
            return;
        final UUID sourceId = source.orElseThrow();

        event.getAffectedEntities().removeIf(entity -> entity instanceof final Player target
                && areSeparated(sourceId, target.getUniqueId()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerFish(final PlayerFishEvent event)
    {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_ENTITY
                || !(event.getCaught() instanceof final Player target)
                || !areSeparated(event.getPlayer().getUniqueId(), target.getUniqueId()))
        {
            return;
        }

        event.setCancelled(true);
        event.getHook().remove();
    }

    private void applyVisibility(final Player viewer, final Player target)
    {
        if (!viewer.isOnline() || !target.isOnline() || viewer.getUniqueId().equals(target.getUniqueId()))
        {
            return;
        }

        final VisibilityPair pair = new VisibilityPair(viewer.getUniqueId(), target.getUniqueId());
        if (isBlocked(pair.viewer(), pair.target())
                && !runtimeState.isTemporarilyRevealed(pair.viewer(), pair.target()))
        {
            if (hiddenPairs.add(pair))
            {
                viewer.hidePlayer(plugin, target);
                syncTeamMembership(viewer, target, false);
                syncScoreEntries(viewer, target, false);
            }
            return;
        }

        if (hiddenPairs.remove(pair))
        {
            viewer.showPlayer(plugin, target);
            syncTeamMembership(viewer, target, true);
            syncScoreEntries(viewer, target, true);
        }
    }

    /**
     * Applies the nearby-action reveal to a coalesced WorldEdit/FAWE edit region.
     */
    public void revealWorldEditAction(
            final UUID actorId,
            final World world,
            final Map<BlockActionBucket, Long> editedBlocks)
    {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(editedBlocks, "editedBlocks");
        requireMainThread();

        final Player actor = server.getPlayer(actorId);
        if (actor == null || editedBlocks.isEmpty())
            return;

        world.getPlayers()
                .stream()
                .filter(viewer -> isBlocked(viewer.getUniqueId(), actorId))
                .filter(viewer -> isNearEditedBlock(viewer.getLocation(), editedBlocks))
                .forEach(viewer -> beginTemporaryReveal(viewer, actor));
    }

    private boolean isNearEditedBlock(
            final Location location,
            final Map<BlockActionBucket, Long> editedBlocks)
    {
        final int centerBucketX = location.getBlockX() >> 2;
        final int centerBucketY = location.getBlockY() >> 2;
        final int centerBucketZ = location.getBlockZ() >> 2;
        final int bucketRadius = ((int)TEMPORARY_REVEAL_RADIUS >> 2) + 1;
        for (int bucketX = centerBucketX - bucketRadius;
             bucketX <= centerBucketX + bucketRadius;
             bucketX++)
        {
            for (int bucketY = centerBucketY - bucketRadius;
                 bucketY <= centerBucketY + bucketRadius;
                 bucketY++)
            {
                for (int bucketZ = centerBucketZ - bucketRadius;
                     bucketZ <= centerBucketZ + bucketRadius;
                     bucketZ++)
                {
                    final BlockActionBucket bucket = new BlockActionBucket(bucketX, bucketY, bucketZ);
                    final long occupiedBlocks = editedBlocks.getOrDefault(bucket, 0L);
                    if (occupiedBlocks != 0L && isNearOccupiedBlock(location, bucket, occupiedBlocks))
                        return true;
                }
            }
        }
        return false;
    }

    private boolean isNearOccupiedBlock(
            final Location location,
            final BlockActionBucket bucket,
            final long occupiedBlocks)
    {
        long remainingBlocks = occupiedBlocks;
        while (remainingBlocks != 0L)
        {
            final int bit = Long.numberOfTrailingZeros(remainingBlocks);
            final int blockX = (bucket.x() << 2) + (bit & 3);
            final int blockZ = (bucket.z() << 2) + ((bit >> 2) & 3);
            final int blockY = (bucket.y() << 2) + ((bit >> 4) & 3);
            final double xDistance = axisDistance(location.getX(), blockX, blockX + 1.0);
            final double yDistance = axisDistance(location.getY(), blockY, blockY + 1.0);
            final double zDistance = axisDistance(location.getZ(), blockZ, blockZ + 1.0);
            if (xDistance * xDistance + yDistance * yDistance + zDistance * zDistance
                    <= TEMPORARY_REVEAL_RADIUS_SQUARED)
            {
                return true;
            }
            remainingBlocks &= remainingBlocks - 1L;
        }
        return false;
    }

    private double axisDistance(final double value, final double minimum, final double maximum)
    {
        if (value < minimum)
            return minimum - value;
        if (value > maximum)
            return value - maximum;
        return 0.0;
    }

    private void revealToNearbyBlockers(final Player actor, final Location actionLocation)
    {
        if (actionLocation.getWorld() == null)
            return;

        actionLocation.getWorld()
                .getNearbyPlayers(actionLocation, TEMPORARY_REVEAL_RADIUS)
                .stream()
                .filter(viewer -> isBlocked(viewer.getUniqueId(), actor.getUniqueId()))
                .filter(viewer -> viewer.getLocation().distanceSquared(actionLocation)
                        <= TEMPORARY_REVEAL_RADIUS_SQUARED)
                .forEach(viewer -> beginTemporaryReveal(viewer, actor));
    }

    @SuppressWarnings("deprecation")
    private void syncTeamMembership(
            final Player viewer,
            final Player target,
            final boolean visible)
    {
        final Team team = viewer.getScoreboard().getEntryTeam(target.getName());
        if (team == null)
            return;

        try
        {
            final WrapperPlayServerTeams packet = new WrapperPlayServerTeams(
                    team.getName(),
                    visible
                            ? WrapperPlayServerTeams.TeamMode.ADD_ENTITIES
                            : WrapperPlayServerTeams.TeamMode.REMOVE_ENTITIES,
                    Optional.empty(),
                    List.of(target.getName()));
            PacketEvents.getAPI()
                    .getPlayerManager()
                    .sendPacketSilently(viewer, packet);
        }
        catch (Throwable ex)
        {
            reportPacketFailure(ex);
        }
    }

    @SuppressWarnings("deprecation")
    private void syncScoreEntries(
            final Player viewer,
            final Player target,
            final boolean visible)
    {
        final Set<String> targetIdentities = new HashSet<>(getKnownPlayerIdentities(
                target.getUniqueId(),
                playerIdentitySnapshots));
        targetIdentities.add(target.getUniqueId().toString());

        try
        {
            final Set<String> scoreEntries = getScoreEntriesForIdentities(
                    viewer,
                    target.getUniqueId(),
                    targetIdentities);
            if (!visible)
            {
                scoreEntries.forEach(identity -> PacketEvents.getAPI()
                        .getPlayerManager()
                        .sendPacketSilently(viewer, new WrapperPlayServerResetScore(identity, null)));
                return;
            }

            viewer.getScoreboard()
                    .getObjectives()
                    .forEach(objective -> scoreEntries
                            .stream()
                            .map(objective::getScore)
                            .filter(Score::isScoreSet)
                            .forEach(score -> PacketEvents.getAPI()
                                    .getPlayerManager()
                                    .sendPacketSilently(viewer, new WrapperPlayServerUpdateScore(
                                            score.getEntry(),
                                            WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM,
                                            objective.getName(),
                                            score.getScore(),
                                            score.customName(),
                                            toPacketScoreFormat(score.numberFormat())))));
        }
        catch (Throwable ex)
        {
            reportPacketFailure(ex);
        }
    }

    private Set<String> getScoreEntriesForIdentities(
            final Player viewer,
            final UUID target,
            final Set<String> targetIdentities)
    {
        final BlockedIdentityFilter targetFilter = new BlockedIdentityFilter();
        targetFilter.replaceTarget(target, targetIdentities);
        final Set<String> scoreEntries = new HashSet<>(targetIdentities);
        viewer.getScoreboard()
                .getEntries()
                .stream()
                .filter(entry -> targetFilter.matchesExact(entry)
                        || viewer.getScoreboard()
                                .getObjectives()
                                .stream()
                                .map(objective -> objective.getScore(entry))
                                .filter(Score::isScoreSet)
                                .anyMatch(score -> scoreContainsIdentity(score, targetFilter)))
                .forEach(scoreEntries::add);
        return Set.copyOf(scoreEntries);
    }

    private boolean scoreContainsIdentity(
            final Score score,
            final BlockedIdentityFilter filter)
    {
        final Component customName = score.customName();
        if (customName != null && !filter.redact(customName).equals(customName))
            return true;

        if (score.numberFormat() instanceof final FixedFormat fixed)
        {
            final Component value = fixed.component();
            return !filter.redact(value).equals(value);
        }
        return false;
    }

    private ScoreFormat toPacketScoreFormat(final NumberFormat numberFormat)
    {
        if (numberFormat instanceof final FixedFormat fixed)
            return ScoreFormat.fixedScore(fixed.component());
        if (numberFormat instanceof final StyledFormat styled)
            return ScoreFormat.styledScore(styled.style());
        return numberFormat == null ? null : ScoreFormat.blankScore();
    }

    private void beginTemporaryReveal(final Player viewer, final Player target)
    {
        final VisibilityPair pair = new VisibilityPair(viewer.getUniqueId(), target.getUniqueId());
        final long deadline = runtimeState.beginTemporaryReveal(pair.viewer(), pair.target());
        final BukkitTask previousTask = revealTasks.remove(pair);
        if (previousTask != null)
            previousTask.cancel();

        applyVisibility(viewer, target);
        scheduleRevealExpiry(pair, deadline, TEMPORARY_REVEAL_TICKS);
    }

    private void scheduleRevealExpiry(
            final VisibilityPair pair,
            final long deadline,
            final long delayTicks)
    {
        final BukkitTask task = server.getScheduler().runTaskLater(
                plugin,
                FTask.guard("PlayerBlockEnforcer/revealExpiry", () -> finishTemporaryReveal(pair, deadline)),
                delayTicks);
        revealTasks.put(pair, task);
    }

    private void finishTemporaryReveal(final VisibilityPair pair, final long deadline)
    {
        if (!runtimeState.expireTemporaryReveal(pair.viewer(), pair.target(), deadline))
        {
            if (runtimeState.isCurrentTemporaryReveal(pair.viewer(), pair.target(), deadline))
                scheduleRevealExpiry(pair, deadline, 1L);
            return;
        }

        revealTasks.remove(pair);
        final Player viewer = server.getPlayer(pair.viewer());
        final Player target = server.getPlayer(pair.target());
        if (viewer != null && target != null)
            applyVisibility(viewer, target);
    }

    private void clearTemporaryReveal(final VisibilityPair pair)
    {
        runtimeState.clearTemporaryReveal(pair.viewer(), pair.target());
        final BukkitTask task = revealTasks.remove(pair);
        if (task != null)
            task.cancel();
    }

    private TeleportPair resolveDirectTeleport(final Player sender, final String message)
    {
        if (message == null || message.length() < 2)
            return null;

        final String[] parts = tokenizeCommand(message);
        if (parts.length == 0)
            return null;

        final Command command = server.getCommandMap().getCommand(parts[0].toLowerCase(Locale.ROOT));
        if (command == null || !isSupportedTeleportProvider(command))
            return null;

        final boolean selectorsSupported = isVanillaCommandProvider(command);
        final String commandName = command.getName().toLowerCase(Locale.ROOT);
        return switch (commandName)
        {
            case "tp", "tpo", "teleport" -> resolveTeleportTo(sender, parts, selectorsSupported);
            case "tphere", "tpohere" -> resolveTeleportHere(sender, parts);
            case "tpall" -> resolveTeleportAll(sender, parts);
            case "tpa" -> resolveTeleportRequest(sender, parts, false);
            case "tpahere" -> resolveTeleportRequest(sender, parts, true);
            default -> null;
        };
    }

    private boolean isBlockedVanillaContactCommand(final Player sender, final String message)
    {
        if (message == null || message.length() < 2)
            return false;

        final String[] parts = tokenizeCommand(message);
        if (parts.length < 2)
            return false;

        final Command command = server.getCommandMap().getCommand(parts[0].toLowerCase(Locale.ROOT));
        if (command == null || !isVanillaCommandProvider(command))
            return false;

        final String commandName = command.getName().toLowerCase(Locale.ROOT);
        return switch (commandName)
        {
            case "msg", "tell", "w", "damage" -> hasSeparatedTarget(sender, parts[1]);
            case "ride" -> isBlockedRideCommand(sender, parts);
            case "spectate" -> isBlockedSpectateCommand(sender, parts);
            default -> false;
        };
    }

    private boolean isBlockedRideCommand(final Player sender, final String[] parts)
    {
        if (parts.length < 3)
            return false;

        final Set<Player> riders = resolveOnlinePlayers(sender, parts[1], true);
        if (hasSeparatedPlayer(sender, riders))
            return true;

        if (!"mount".equalsIgnoreCase(parts[2]) || parts.length < 4)
            return false;

        final Set<Player> vehicles = resolveOnlinePlayers(sender, parts[3], true);
        return hasSeparatedPlayer(sender, vehicles)
                || riders.stream().anyMatch(rider -> vehicles.stream().anyMatch(vehicle -> areSeparated(
                        rider.getUniqueId(),
                        vehicle.getUniqueId())));
    }

    private boolean isBlockedSpectateCommand(final Player sender, final String[] parts)
    {
        if (parts.length < 2 || parts.length > 3)
            return false;

        final Set<Player> targets = resolveOnlinePlayers(sender, parts[1], true);
        final Set<Player> spectators = parts.length == 3
                ? resolveOnlinePlayers(sender, parts[2], true)
                : Set.of(sender);
        return hasSeparatedPlayer(sender, targets)
                || hasSeparatedPlayer(sender, spectators)
                || spectators.stream().anyMatch(spectator -> targets.stream().anyMatch(target -> areSeparated(
                        spectator.getUniqueId(),
                        target.getUniqueId())));
    }

    private boolean hasSeparatedTarget(final Player sender, final String value)
    {
        return hasSeparatedPlayer(sender, resolveOnlinePlayers(sender, value, true));
    }

    private boolean hasSeparatedPlayer(final Player sender, final Set<Player> targets)
    {
        return targets.stream().anyMatch(target -> areSeparated(
                sender.getUniqueId(),
                target.getUniqueId()));
    }

    private TeleportPair resolveTeleportTo(
            final Player sender,
            final String[] parts,
            final boolean selectorsSupported)
    {
        if (parts.length == 2)
        {
            return resolveOnlinePlayers(sender, parts[1], selectorsSupported)
                    .stream()
                    .map(destination -> new TeleportPair(
                            sender.getUniqueId(),
                            destination.getUniqueId()))
                    .filter(this::isTeleportBlocked)
                    .findFirst()
                    .orElse(null);
        }

        if (parts.length > 3)
        {
            final boolean implicitCoordinates = hasCoordinateTriplet(parts, 1);
            final boolean explicitCoordinates = hasCoordinateTriplet(parts, 2);
            if (!implicitCoordinates && !explicitCoordinates)
                return null;

            final Set<Player> explicitMovers = explicitCoordinates
                    ? resolveOnlinePlayers(sender, parts[1], selectorsSupported)
                    : Set.of();
            final Set<Player> movers = explicitCoordinates ? explicitMovers : Set.of(sender);
            final int facingIndex = explicitCoordinates ? 5 : 4;
            if (parts.length > facingIndex + 2
                    && "facing".equalsIgnoreCase(parts[facingIndex])
                    && "entity".equalsIgnoreCase(parts[facingIndex + 1]))
            {
                final Set<Player> facingTargets = resolveOnlinePlayers(
                        sender,
                        parts[facingIndex + 2],
                        selectorsSupported);
                final TeleportPair blockedPair = Stream.concat(Stream.of(sender), movers.stream())
                        .distinct()
                        .flatMap(mover -> facingTargets.stream()
                                .map(target -> new TeleportPair(
                                        mover.getUniqueId(),
                                        target.getUniqueId())))
                        .filter(this::isTeleportBlocked)
                        .findFirst()
                        .orElse(null);
                if (blockedPair != null)
                    return blockedPair;
            }

            // Vanilla coordinate/facing syntax puts explicit movers first. Do not let a
            // separated player use it to move the other party without naming a destination
            // player. A location-only teleport starts with a coordinate and resolves empty.
            return explicitMovers.stream()
                    .map(mover -> new TeleportPair(
                            mover.getUniqueId(),
                            sender.getUniqueId()))
                    .filter(this::isTeleportBlocked)
                    .findFirst()
                    .orElse(null);
        }

        if (parts.length != 3)
            return null;

        final Set<Player> movers = resolveOnlinePlayers(sender, parts[1], selectorsSupported);
        final Set<Player> destinations = resolveOnlinePlayers(sender, parts[2], selectorsSupported);
        return movers.stream()
                .flatMap(mover -> destinations.stream()
                        .map(destination -> new TeleportPair(
                                mover.getUniqueId(),
                                destination.getUniqueId())))
                .filter(this::isTeleportBlocked)
                .findFirst()
                .orElse(null);
    }

    static String[] tokenizeCommand(final String commandLine)
    {
        if (commandLine == null)
            return new String[0];

        String content = commandLine.trim();
        if (content.startsWith("/"))
            content = content.substring(1);
        if (content.isBlank())
            return new String[0];

        final List<String> parts = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        final Deque<Character> closingDelimiters = new ArrayDeque<>();
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < content.length(); index++)
        {
            final char character = content.charAt(index);
            if (quote != 0)
            {
                current.append(character);
                if (escaped)
                    escaped = false;
                else if (character == '\\')
                    escaped = true;
                else if (character == quote)
                    quote = 0;
                continue;
            }

            if (character == '\'' || character == '"')
            {
                quote = character;
                current.append(character);
                continue;
            }

            switch (character)
            {
                case '[' -> closingDelimiters.push(']');
                case '{' -> closingDelimiters.push('}');
                case '(' -> closingDelimiters.push(')');
                default ->
                {
                    if (!closingDelimiters.isEmpty() && character == closingDelimiters.peek())
                        closingDelimiters.pop();
                }
            }

            if (Character.isWhitespace(character) && closingDelimiters.isEmpty())
            {
                if (!current.isEmpty())
                {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }

            current.append(character);
        }

        if (!current.isEmpty())
            parts.add(current.toString());
        return parts.toArray(String[]::new);
    }

    static boolean hasCoordinateTriplet(final String[] parts, final int startIndex)
    {
        return parts.length > startIndex + 2
                && isCoordinate(parts[startIndex])
                && isCoordinate(parts[startIndex + 1])
                && isCoordinate(parts[startIndex + 2]);
    }

    private static boolean isCoordinate(final String value)
    {
        if (value == null || value.isBlank())
            return false;

        final char prefix = value.charAt(0);
        final String numericValue = prefix == '~' || prefix == '^'
                ? value.substring(1)
                : value;
        if (numericValue.isEmpty())
            return prefix == '~' || prefix == '^';

        try
        {
            return Double.isFinite(Double.parseDouble(numericValue));
        }
        catch (NumberFormatException ignored)
        {
            return false;
        }
    }

    private TeleportPair resolveTeleportHere(final Player sender, final String[] parts)
    {
        if (parts.length != 2)
            return null;

        final Player mover = resolveOnlinePlayer(sender, parts[1]);
        return mover == null
                ? null
                : new TeleportPair(mover.getUniqueId(), sender.getUniqueId());
    }

    private TeleportPair resolveTeleportAll(final Player sender, final String[] parts)
    {
        if (parts.length > 2)
            return null;

        final Player destination = parts.length == 1
                ? sender
                : resolveOnlinePlayer(sender, parts[1]);
        if (destination == null)
            return null;

        final UUID destinationId = destination.getUniqueId();
        return server.getOnlinePlayers()
                .stream()
                .map(player -> new TeleportPair(player.getUniqueId(), destinationId))
                .filter(this::isTeleportBlocked)
                .findFirst()
                .orElse(null);
    }

    private TeleportPair resolveTeleportRequest(
            final Player sender,
            final String[] parts,
            final boolean teleportHere)
    {
        if (parts.length != 2)
            return null;

        final Player target = resolveOnlinePlayer(sender, parts[1]);
        if (target == null)
            return null;

        return teleportHere
                ? new TeleportPair(target.getUniqueId(), sender.getUniqueId())
                : new TeleportPair(sender.getUniqueId(), target.getUniqueId());
    }

    private Player resolveOnlinePlayer(final Player sender, final String value)
    {
        if ("@s".equalsIgnoreCase(value))
            return sender;

        try
        {
            final Player uuidPlayer = server.getPlayer(UUID.fromString(value));
            if (uuidPlayer != null)
                return uuidPlayer;
        }
        catch (IllegalArgumentException ignored) {}

        final Player matchedPlayer = server.getPlayer(value);
        if (matchedPlayer != null)
            return matchedPlayer;

        final String normalizedValue = value.toLowerCase(Locale.ROOT);
        return server.getOnlinePlayers()
                .stream()
                .filter(player -> AdventureUtil.componentToPlainText(player.displayName())
                        .toLowerCase(Locale.ROOT)
                        .contains(normalizedValue))
                .findFirst()
                .orElse(null);
    }

    private Set<Player> resolveOnlinePlayers(
            final Player sender,
            final String value,
            final boolean selectorsSupported)
    {
        if (selectorsSupported && value.startsWith("@"))
        {
            try
            {
                return Bukkit.selectEntities(sender, value)
                        .stream()
                        .filter(Player.class::isInstance)
                        .map(Player.class::cast)
                        .collect(Collectors.toUnmodifiableSet());
            }
            catch (IllegalArgumentException ignored)
            {
                return Set.of();
            }
        }

        final Player player = resolveOnlinePlayer(sender, value);
        return player == null ? Set.of() : Set.of(player);
    }

    private boolean isTeleportBlocked(final TeleportPair pair)
    {
        return areSeparated(pair.destination(), pair.mover());
    }

    private boolean isSupportedTeleportProvider(final Command command)
    {
        if (command instanceof final PluginIdentifiableCommand pluginCommand)
            return "Essentials".equals(pluginCommand.getPlugin().getName());

        return isVanillaCommandProvider(command);
    }

    private boolean isVanillaCommandProvider(final Command command)
    {
        return command.getClass().getName().equals("org.bukkit.craftbukkit.command.VanillaCommandWrapper");
    }

    private void refreshAllVisibility()
    {
        for (final Player viewer : server.getOnlinePlayers())
        {
            for (final Player target : server.getOnlinePlayers())
            {
                applyVisibility(viewer, target);
            }
        }
    }

    private void refreshAdminSnapshot()
    {
        final Set<UUID> refreshedImmuneUuids = new HashSet<>();
        plugin.al.getActiveAdmins()
                .stream()
                .map(Admin::getUuid)
                .filter(Objects::nonNull)
                .forEach(refreshedImmuneUuids::add);
        plugin.al.getOnlineAdmins()
                .stream()
                .map(Player::getUniqueId)
                .forEach(refreshedImmuneUuids::add);

        final Set<String> ownerNames = Stream.concat(
                        ConfigEntry.SERVER_OWNERS.getStringList().stream(),
                        FUtil.DEVELOPERS.stream())
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        ownerNames.stream()
                .map(server::getOfflinePlayerIfCached)
                .filter(Objects::nonNull)
                .map(OfflinePlayer::getUniqueId)
                .forEach(refreshedImmuneUuids::add);
        server.getOnlinePlayers()
                .stream()
                .filter(player -> ownerNames.contains(player.getName().toLowerCase(Locale.ROOT)))
                .map(Player::getUniqueId)
                .forEach(refreshedImmuneUuids::add);

        final Set<UUID> refreshedSnapshot = Set.copyOf(refreshedImmuneUuids);
        if (!refreshedSnapshot.equals(immunePlayerUuids))
        {
            immunePlayerUuids = refreshedSnapshot;
            refreshIdentitySnapshots();
            refreshAllVisibility();
        }
    }

    private void refreshIdentitySnapshots()
    {
        server.getOnlinePlayers().forEach(this::reconcileCurrentIdentityOwnership);
        final Map<UUID, Set<String>> immutablePlayerIdentities = refreshPlayerIdentitySnapshots();
        final Map<UUID, BlockedIdentityFilter> refreshedFilters = new HashMap<>();
        server.getOnlinePlayers().forEach(viewer ->
        {
            final UUID viewerId = viewer.getUniqueId();
            final BlockedIdentityFilter filter = createBlockedIdentityFilter(viewerId, immutablePlayerIdentities);
            if (!filter.isEmpty())
                refreshedFilters.put(viewerId, filter);
        });

        blockedIdentityFilters = Map.copyOf(refreshedFilters);
    }

    private Map<UUID, Set<String>> refreshPlayerIdentitySnapshots()
    {
        final IdentityCollisionContext collisionContext = createIdentityCollisionContext();
        final Map<UUID, Set<String>> refreshedPlayerIdentities = new HashMap<>(playerIdentitySnapshots);
        server.getOnlinePlayers()
                .stream()
                .filter(player -> plugin.pbl.isBlockedByAnyone(player.getUniqueId()))
                .forEach(player -> refreshedPlayerIdentities.put(
                        player.getUniqueId(),
                        createPlayerIdentities(player, collisionContext)));
        playerIdentitySnapshots = Map.copyOf(refreshedPlayerIdentities);
        return playerIdentitySnapshots;
    }

    private Map<UUID, Set<String>> refreshPlayerIdentitySnapshot(final UUID playerId)
    {
        final Player player = server.getPlayer(playerId);
        if (player == null)
            return playerIdentitySnapshots;

        reconcileCurrentIdentityOwnership(player);

        final Set<String> identities = createPlayerIdentities(
                player,
                createIdentityCollisionContext());
        if (identities.equals(playerIdentitySnapshots.get(playerId)))
            return playerIdentitySnapshots;

        final Map<UUID, Set<String>> refreshedPlayerIdentities = new HashMap<>(playerIdentitySnapshots);
        refreshedPlayerIdentities.put(playerId, identities);
        playerIdentitySnapshots = Map.copyOf(refreshedPlayerIdentities);
        refreshTargetIdentityFilters(playerId, identities);
        return playerIdentitySnapshots;
    }

    private void refreshPlayerIdentityRelationships(final Player player)
    {
        final UUID playerId = player.getUniqueId();
        reconcileCurrentIdentityOwnership(player);
        final Map<UUID, Set<String>> identitiesByPlayer = plugin.pbl.isBlockedByAnyone(playerId)
                ? refreshPlayerIdentitySnapshot(playerId)
                : playerIdentitySnapshots;
        if (blockedIdentityFilters.containsKey(playerId) || !plugin.pbl.hasBlockedAnyone(playerId))
            return;

        final BlockedIdentityFilter filter = createBlockedIdentityFilter(playerId, identitiesByPlayer);
        if (filter.isEmpty())
            return;

        final Map<UUID, BlockedIdentityFilter> refreshedFilters = new HashMap<>(blockedIdentityFilters);
        refreshedFilters.put(playerId, filter);
        blockedIdentityFilters = Map.copyOf(refreshedFilters);
    }

    private BlockedIdentityFilter createBlockedIdentityFilter(
            final UUID viewer,
            final Map<UUID, Set<String>> identitiesByPlayer)
    {
        final BlockedIdentityFilter filter = new BlockedIdentityFilter();
        plugin.pbl.getBlockedPlayers(viewer)
                .stream()
                .filter(target -> isBlocked(viewer, target))
                .forEach(target -> filter.replaceTarget(
                        target,
                        getKnownPlayerIdentities(target, identitiesByPlayer)));
        return filter;
    }

    private void refreshIdentitySnapshotsIfChanged()
    {
        server.getOnlinePlayers().forEach(this::reconcileCurrentIdentityOwnership);
        final IdentityCollisionContext collisionContext = createIdentityCollisionContext();
        final Map<UUID, Set<String>> changedIdentities = server.getOnlinePlayers()
                .stream()
                .filter(player -> plugin.pbl.isBlockedByAnyone(player.getUniqueId()))
                .filter(player -> !createPlayerIdentities(player, collisionContext)
                        .equals(playerIdentitySnapshots.get(player.getUniqueId())))
                .collect(Collectors.toUnmodifiableMap(
                        Player::getUniqueId,
                        player -> createPlayerIdentities(player, collisionContext)));
        if (changedIdentities.isEmpty())
            return;

        final Map<UUID, Set<String>> refreshedPlayerIdentities = new HashMap<>(playerIdentitySnapshots);
        refreshedPlayerIdentities.putAll(changedIdentities);
        playerIdentitySnapshots = Map.copyOf(refreshedPlayerIdentities);

        changedIdentities.forEach(this::refreshTargetIdentityFilters);
    }

    private void pruneOfflineIdentitySnapshot(final UUID playerId)
    {
        if (server.getPlayer(playerId) != null || plugin.pbl.isBlockedByAnyone(playerId))
            return;

        final Map<UUID, Set<String>> retainedIdentities = new HashMap<>(playerIdentitySnapshots);
        if (retainedIdentities.remove(playerId) != null)
            playerIdentitySnapshots = Map.copyOf(retainedIdentities);
    }

    private void refreshTargetIdentityFilters(final UUID target, final Set<String> identities)
    {
        plugin.pbl.getBlockers(target).forEach(blocker ->
        {
            BlockedIdentityFilter filter = blockedIdentityFilters.get(blocker);
            if (filter == null)
            {
                if (!isBlocked(blocker, target) || server.getPlayer(blocker) == null)
                    return;

                filter = new BlockedIdentityFilter();
                filter.replaceTarget(target, getKnownPlayerIdentities(target, Map.of(target, identities)));
                final Map<UUID, BlockedIdentityFilter> refreshedFilters =
                        new HashMap<>(blockedIdentityFilters);
                refreshedFilters.put(blocker, filter);
                blockedIdentityFilters = Map.copyOf(refreshedFilters);
                return;
            }

            if (isBlocked(blocker, target))
                filter.replaceTarget(target, getKnownPlayerIdentities(target, Map.of(target, identities)));
            else
                filter.removeTarget(target);
            if (filter.isEmpty())
                removeBlockedIdentityFilter(blocker, filter);
        });
    }

    private Set<String> createPlayerIdentities(
            final Player player,
            final IdentityCollisionContext collisionContext)
    {
        final Set<String> identities = new HashSet<>();
        identities.add(player.getName());
        final String displayName = PLAIN_TEXT.serialize(player.displayName()).trim();
        if (isSafeAlternativeIdentity(player.getName(), displayName, collisionContext))
            identities.add(displayName);
        return Set.copyOf(identities);
    }

    private boolean isSafeAlternativeIdentity(
            final String accountName,
            final String displayName,
            final IdentityCollisionContext collisionContext)
    {
        final String candidate = comparableIdentity(displayName);
        final boolean renderedNickname = displayName.stripLeading().startsWith("~");
        if ((!renderedNickname && candidate.codePointCount(0, candidate.length()) < 3)
                || candidate.equals(comparableIdentity(accountName)))
        {
            return false;
        }

        return !collisionContext.protectedIdentities().contains(candidate)
                && collisionContext.onlineIdentityCounts().getOrDefault(candidate, 0) <= 1;
    }

    private IdentityCollisionContext createIdentityCollisionContext()
    {
        final Set<String> protectedIdentities = Stream.concat(
                        Stream.concat(
                                ConfigEntry.SERVER_OWNERS.getStringList().stream(),
                                FUtil.DEVELOPERS.stream()),
                        plugin.al.getActiveAdmins().stream().map(Admin::getName))
                .filter(Objects::nonNull)
                .map(this::comparableIdentity)
                .collect(Collectors.toUnmodifiableSet());

        final Map<String, Integer> onlineIdentityCounts = new HashMap<>();
        server.getOnlinePlayers().forEach(player -> Stream.of(
                        player.getName(),
                        PLAIN_TEXT.serialize(player.displayName()))
                .map(this::comparableIdentity)
                .filter(identity -> !identity.isBlank())
                .distinct()
                .forEach(identity -> onlineIdentityCounts.merge(identity, 1, Integer::sum)));
        return new IdentityCollisionContext(
                protectedIdentities,
                Map.copyOf(onlineIdentityCounts));
    }

    private String comparableIdentity(final String value)
    {
        return value == null ? "" : PlayerIdentityNormalizer.comparable(value);
    }

    private void reconcileCurrentIdentityOwnership(final Player player)
    {
        final Set<String> identities = Stream.of(
                        player.getName(),
                        String.format("~%s", player.getName()),
                        PLAIN_TEXT.serialize(player.displayName()).trim())
                .filter(identity -> !identity.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        final Set<String> previous = observedOnlineIdentities.put(player.getUniqueId(), identities);
        if (identities.equals(previous))
            return;

        identities.forEach(identity -> blockedIdentityFilters.values().forEach(
                filter -> filter.removeAliasFromOtherTargets(player.getUniqueId(), identity)));
        if (previous != null)
        {
            final Set<String> releasedIdentities = new HashSet<>(previous);
            releasedIdentities.removeAll(identities);
            if (!releasedIdentities.isEmpty())
                restoreReleasedIdentityAliases(Set.copyOf(releasedIdentities));
        }
    }

    private void restoreReleasedIdentityAliases(final Set<String> releasedIdentities)
    {
        final Set<String> normalizedReleasedIdentities = releasedIdentities
                .stream()
                .map(BlockedIdentityFilter::normalizedAliasIdentity)
                .filter(identity -> !identity.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        playerIdentitySnapshots.forEach((target, identities) ->
        {
            final boolean releasedAlias = identities
                    .stream()
                    .map(BlockedIdentityFilter::normalizedAliasIdentity)
                    .anyMatch(normalizedReleasedIdentities::contains);
            if (releasedAlias)
                refreshTargetIdentityFilters(target, identities);
        });
    }

    private Set<String> getKnownPlayerIdentities(
            final UUID playerId,
            final Map<UUID, Set<String>> identitiesByPlayer)
    {
        final Set<String> identities = identitiesByPlayer.get(playerId);
        if (identities != null && !identities.isEmpty())
            return removeReassignedCanonicalIdentities(playerId, identities);

        final Set<String> cachedIdentities = playerIdentitySnapshots.get(playerId);
        if (cachedIdentities != null && !cachedIdentities.isEmpty())
            return removeReassignedCanonicalIdentities(playerId, cachedIdentities);

        final String name = server.getOfflinePlayer(playerId).getName();
        if (name == null || name.isBlank())
            return Set.of();

        final Set<String> loadedIdentities = new HashSet<>();
        loadedIdentities.add(name);
        final PlayerData data = plugin.pl.getData(name);
        if (data != null && data.hasCustomNickname())
        {
            final String persistedDisplayName = PLAIN_TEXT.serialize(data.getDisplayedNickname()).trim();
            if (isSafeAlternativeIdentity(
                    name,
                    persistedDisplayName,
                    createIdentityCollisionContext()))
            {
                loadedIdentities.add(persistedDisplayName);
            }
        }

        final Set<String> retainedIdentities = removeReassignedCanonicalIdentities(
                playerId,
                Set.copyOf(loadedIdentities));
        if (!retainedIdentities.isEmpty())
        {
            final Map<UUID, Set<String>> refreshedIdentities = new HashMap<>(playerIdentitySnapshots);
            refreshedIdentities.put(playerId, retainedIdentities);
            playerIdentitySnapshots = Map.copyOf(refreshedIdentities);
        }
        return retainedIdentities;
    }

    private Set<String> removeReassignedCanonicalIdentities(
            final UUID expectedOwner,
            final Set<String> identities)
    {
        return identities.stream()
                .filter(identity -> server.getOnlinePlayers()
                        .stream()
                        .filter(player -> !player.getUniqueId().equals(expectedOwner))
                        .noneMatch(player -> comparableIdentity(player.getName())
                                .equals(comparableIdentity(identity))
                                || BlockedIdentityFilter.normalizedAliasIdentity(
                                        PLAIN_TEXT.serialize(player.displayName()))
                                        .equals(BlockedIdentityFilter.normalizedAliasIdentity(identity))))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Decides whether an outgoing team packet must have blocked identities stripped out of its
     * entry list before the viewer receives it.
     * <p>
     * {@code CREATE} and {@code ADD_ENTITIES} are redacted so a blocked identity never becomes a
     * team member on the viewer's client. {@code REMOVE_ENTITIES} must be redacted for the
     * mirror-image reason: because the add was suppressed (or the entry was already removed when
     * the pair was hidden), the viewer's client does not have that entry on the team, and a
     * vanilla client throws {@link IllegalStateException} out of
     * {@code Scoreboard#removePlayerFromTeam} and drops the connection with a protocol error when
     * told to remove an entry it does not hold. That is what happened when a blocked player quit:
     * the rank team cleanup broadcast a removal for an entry the blocker's client never had.
     * <p>
     * {@code REMOVE} (whole-team deletion) and {@code UPDATE} (metadata only) carry no entry list
     * that can desynchronise, and deleting a team the client holds never throws, so both are left
     * untouched.
     */
    static boolean redactsBlockedTeamEntries(final WrapperPlayServerTeams.TeamMode mode)
    {
        return mode == WrapperPlayServerTeams.TeamMode.CREATE
                || mode == WrapperPlayServerTeams.TeamMode.ADD_ENTITIES
                || mode == WrapperPlayServerTeams.TeamMode.REMOVE_ENTITIES;
    }

    private void registerBlockedIdentityPacketListener()
    {
        if (blockedIdentityPacketListener != null)
            return;

        final Plugin packetEventsPlugin = Optional.ofNullable(server.getPluginManager().getPlugin("packetevents"))
                .orElseGet(() -> server.getPluginManager().getPlugin("PacketEvents"));
        validatePacketEventsAvailability(packetEventsPlugin != null);

        // Deliberately not gated on packetEventsPlugin.isEnabled(): TotalFreedomMod is a
        // Paper-format plugin and PacketEvents is a classic Bukkit-format plugin, and on some
        // Paper builds the two plugin formats' onEnable() calls are not reliably ordered against
        // each other even though paper-plugin.yml declares packetevents as a required
        // "load: BEFORE" dependency. PacketEvents publishes its static API from its own onLoad()
        // (well before either plugin's onEnable() runs), so the registration below is safe even
        // when Bukkit's enabled flag for packetevents has not flipped true yet. If PacketEvents
        // genuinely is not ready, PacketEvents.getAPI() or registerListener() below will throw,
        // and that failure still fails startup closed via the catch block.
        try
        {
            blockedIdentityPacketListener = PacketEvents.getAPI()
                    .getEventManager()
                    .registerListener(new BlockedIdentityPacketListener());
        }
        catch (Throwable ex)
        {
            throw new FatalServiceStartupException(
                    "Could not register the player-block identity packet filter.",
                    ex);
        }
    }

    /**
     * Extracted so the fail-closed check can be exercised without a live Bukkit plugin manager
     * or the static PacketEvents API. {@link PlayerBlockEnforcer}'s system chat, team, and score
     * privacy filtering all run through the PacketEvents listener registered by
     * {@link #registerBlockedIdentityPacketListener()}; if it never registers, continuing to
     * start would silently leave the block feature enforcing only partial privacy instead of
     * failing closed.
     */
    static void validatePacketEventsAvailability(final boolean pluginPresent)
    {
        if (!pluginPresent)
            throw new FatalServiceStartupException("PacketEvents is required for player-block privacy filtering.");
    }

    private void unregisterBlockedIdentityPacketListener()
    {
        if (blockedIdentityPacketListener == null)
            return;

        try
        {
            PacketEvents.getAPI()
                    .getEventManager()
                    .unregisterListener(blockedIdentityPacketListener);
        }
        catch (Throwable ex)
        {
            FLog.warning(String.format(
                    "Could not unregister the player-block identity packet filter: %s",
                    ex.getMessage()));
        }
        blockedIdentityPacketListener = null;
    }

    private boolean isExactBlockedIdentity(final UUID viewer, final Component identity)
    {
        final BlockedIdentityFilter filter = blockedIdentityFilters.get(viewer);
        return filter != null && filter.matchesExact(identity);
    }

    private void removeBlockedIdentityFilter(
            final UUID viewer,
            final BlockedIdentityFilter expectedFilter)
    {
        final Map<UUID, BlockedIdentityFilter> refreshedFilters = new HashMap<>(blockedIdentityFilters);
        if (refreshedFilters.remove(viewer, expectedFilter))
            blockedIdentityFilters = Map.copyOf(refreshedFilters);
    }

    private void reportPacketFailure(final Throwable ex)
    {
        if (packetFailureLogged)
            return;

        packetFailureLogged = true;
        FLog.warning(String.format(
                "Player-block identity packet filtering failed; further failures will be suppressed: %s",
                ex.getMessage()));
    }

    private void requireMainThread()
    {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Player block runtime mutations must run on the server thread.");
    }

    private UUID resolveAttackingPlayer(final EntityDamageByEntityEvent event)
    {
        final Entity causingEntity = event.getDamageSource().getCausingEntity();
        if (causingEntity instanceof final Player player)
        {
            return player.getUniqueId();
        }

        final Entity damager = event.getDamager();
        if (damager instanceof final Player player)
        {
            return player.getUniqueId();
        }

        if (damager instanceof final Projectile projectile)
        {
            final UUID ownerUuid = projectile.getOwnerUniqueId();
            if (ownerUuid != null)
                return ownerUuid;

            final ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof final Player player)
            {
                return player.getUniqueId();
            }
        }

        if (damager instanceof final TNTPrimed tnt && tnt.getSource() instanceof final Player player)
        {
            return player.getUniqueId();
        }

        if (damager instanceof final Tameable tameable)
        {
            final AnimalTamer owner = tameable.getOwner();
            if (owner != null)
                return owner.getUniqueId();
        }

        return null;
    }

    private Optional<UUID> resolveProjectileOwner(final UUID ownerUuid, final ProjectileSource source)
    {
        if (ownerUuid != null)
            return Optional.of(ownerUuid);

        if (source instanceof final Player player)
            return Optional.of(player.getUniqueId());

        return Optional.empty();
    }

    private final class BlockedIdentityPacketListener extends PacketListenerAbstract
    {
        private BlockedIdentityPacketListener()
        {
            super(PacketListenerPriority.HIGHEST);
        }

        @Override
        public void onPacketSend(final PacketSendEvent event)
        {
            final UUID viewer = event.getUser().getUUID();
            if (viewer == null)
                return;

            try
            {
                if (event.getPacketType() == PacketType.Play.Server.CHAT_MESSAGE)
                {
                    suppressBlockedPlayerChat(event, viewer);
                }
                else if (event.getPacketType() == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE)
                {
                    redactSystemChat(event, viewer);
                }
                else if (event.getPacketType() == PacketType.Play.Server.DISGUISED_CHAT)
                {
                    final BlockedIdentityFilter filter = blockedIdentityFilters.get(viewer);
                    if (filter == null || filter.isEmpty())
                        return;
                    redactDisguisedChat(event, viewer);
                }
                else if (event.getPacketType() == PacketType.Play.Server.TEAMS)
                {
                    final BlockedIdentityFilter filter = blockedIdentityFilters.get(viewer);
                    if (filter == null || filter.isEmpty())
                        return;
                    redactTeamMembers(event, filter);
                }
                else if (event.getPacketType() == PacketType.Play.Server.UPDATE_SCORE)
                {
                    final BlockedIdentityFilter filter = blockedIdentityFilters.get(viewer);
                    if (filter == null || filter.isEmpty())
                        return;
                    suppressBlockedScoreEntry(event, filter);
                }
            }
            catch (Throwable ex)
            {
                reportPacketFailure(ex);
                final BlockedIdentityFilter filter = blockedIdentityFilters.get(viewer);
                if (filter != null && !filter.isEmpty())
                    event.setCancelled(true);
            }
        }

        private void suppressBlockedPlayerChat(final PacketSendEvent event, final UUID viewer)
        {
            final ChatMessage message = new WrapperPlayServerChatMessage(event).getMessage();
            if (message instanceof final ChatMessage_v1_16 playerMessage
                    && playerMessage.getSenderUUID() != null
                    && isBlocked(viewer, playerMessage.getSenderUUID()))
            {
                event.setCancelled(true);
            }
        }

        private void redactSystemChat(final PacketSendEvent event, final UUID viewer)
        {
            final WrapperPlayServerSystemChatMessage packet =
                    new WrapperPlayServerSystemChatMessage(event);
            final Component message = packet.getMessage();
            if (IDENTITY_MANAGEMENT_MARKER.equals(message.insertion()))
            {
                packet.setMessage(message.insertion(null));
                event.markForReEncode(true);
                return;
            }

            if (isBlockedAnnouncement(viewer, message))
            {
                event.setCancelled(true);
                return;
            }

            final BlockedIdentityFilter filter = blockedIdentityFilters.get(viewer);
            if (filter == null || filter.isEmpty())
                return;

            final Component redacted = redactBlockedIdentities(viewer, message);
            if (redacted.equals(message))
                return;

            packet.setMessage(redacted);
            event.markForReEncode(true);
        }

        private boolean isBlockedAnnouncement(final UUID viewer, final Component message)
        {
            if (!(message instanceof final TranslatableComponent translatable)
                    || !"chat.type.announcement".equals(translatable.key())
                    || translatable.arguments().isEmpty())
            {
                return false;
            }

            final TranslationArgument senderArgument = translatable.arguments().getFirst();
            return senderArgument.value() instanceof final Component senderIdentity
                    && isExactBlockedIdentity(viewer, senderIdentity);
        }

        private void redactDisguisedChat(final PacketSendEvent event, final UUID viewer)
        {
            final WrapperPlayServerDisguisedChat packet = new WrapperPlayServerDisguisedChat(event);
            final ChatType.Bound formatting = packet.getChatFormatting();
            if (isExactBlockedIdentity(viewer, formatting.getName()))
            {
                event.setCancelled(true);
                return;
            }

            boolean changed = false;
            final Component message = packet.getMessage();
            final Component redactedMessage = redactBlockedIdentities(viewer, message);
            if (!redactedMessage.equals(message))
            {
                packet.setMessage(redactedMessage);
                changed = true;
            }

            final Component name = formatting.getName();
            final Component redactedName = redactBlockedIdentities(viewer, name);
            if (!redactedName.equals(name))
            {
                formatting.setName(redactedName);
                changed = true;
            }

            final Component targetName = formatting.getTargetName();
            if (targetName != null)
            {
                final Component redactedTargetName = redactBlockedIdentities(viewer, targetName);
                if (!redactedTargetName.equals(targetName))
                {
                    formatting.setTargetName(redactedTargetName);
                    changed = true;
                }
            }

            if (changed)
            {
                packet.setChatFormatting(formatting);
                event.markForReEncode(true);
            }
        }

        private void redactTeamMembers(
                final PacketSendEvent event,
                final BlockedIdentityFilter filter)
        {
            final WrapperPlayServerTeams packet = new WrapperPlayServerTeams(event);
            final WrapperPlayServerTeams.TeamMode mode = packet.getTeamMode();
            if (!redactsBlockedTeamEntries(mode))
                return;

            final List<String> visiblePlayers = packet.getPlayers()
                    .stream()
                    .filter(playerName -> !filter.matchesExact(playerName))
                    .toList();
            if (visiblePlayers.size() == packet.getPlayers().size())
                return;

            if (visiblePlayers.isEmpty() && mode != WrapperPlayServerTeams.TeamMode.CREATE)
            {
                event.setCancelled(true);
                return;
            }

            packet.setPlayers(visiblePlayers);
            event.markForReEncode(true);
        }

        private void suppressBlockedScoreEntry(
                final PacketSendEvent event,
                final BlockedIdentityFilter filter)
        {
            final WrapperPlayServerUpdateScore packet = new WrapperPlayServerUpdateScore(event);
            if (filter.matchesExact(packet.getEntityName()))
            {
                event.setCancelled(true);
                return;
            }

            boolean changed = false;
            final Component displayName = packet.getEntityDisplayName();
            if (displayName != null)
            {
                final Component redactedDisplayName = filter.redact(displayName);
                if (!redactedDisplayName.equals(displayName))
                {
                    packet.setEntityDisplayName(redactedDisplayName);
                    changed = true;
                }
            }

            if (packet.getScoreFormat() instanceof final FixedScoreFormat fixed)
            {
                final Component value = fixed.getValue();
                final Component redactedValue = filter.redact(value);
                if (!redactedValue.equals(value))
                {
                    packet.setScoreFormat(ScoreFormat.fixedScore(redactedValue));
                    changed = true;
                }
            }

            if (changed)
                event.markForReEncode(true);
        }
    }

    private record VisibilityPair(UUID viewer, UUID target)
    {
    }

    private record TeleportPair(UUID mover, UUID destination)
    {
    }

    private record IdentityCollisionContext(
            Set<String> protectedIdentities,
            Map<String, Integer> onlineIdentityCounts)
    {
    }

    public record BlockActionBucket(int x, int y, int z)
    {
    }
}

package me.totalfreedom.totalfreedommod.player;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event.Result;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;

import me.totalfreedom.api.FreedomAPI;
import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.admin.Admin;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FTask;
import me.totalfreedom.totalfreedommod.util.FUtil;

public class PlayerBlockList extends FreedomService
{
    private static final String CONFIG_FILENAME = "player-blocks.yml";
    private static final long PROTECTED_TARGET_REFRESH_TICKS = 20L;
    private static final long RETRY_DELAY_TICKS = 20L * 30L;
    private static final long SAVE_DELAY_TICKS = 20L;
    private static final long TEMPORARY_REVEAL_TICKS = 20L * 5L;
    private static final double TEMPORARY_REVEAL_RADIUS = 10.0;
    private static final double TEMPORARY_REVEAL_RADIUS_SQUARED =
            TEMPORARY_REVEAL_RADIUS * TEMPORARY_REVEAL_RADIUS;

    private final Object fileWriteLock = new Object();
    private final Object lifecycleStateLock = new Object();
    private final Map<VisibilityPair, BukkitTask> revealTasks = new HashMap<>();
    private final PlayerBlockStorage storage;
    private Optional<BukkitTask> pendingSaveTask = Optional.empty();
    private Optional<BukkitTask> protectedTargetRefreshTask = Optional.empty();
    private Set<UUID> protectedOnlineTargets = Set.of();
    private volatile Map<UUID, Set<UUID>> blockedPlayers = Map.of();
    private volatile long lifecycleEpoch;
    private long changeGeneration, lastWrittenGeneration;
    // Keep unsaved block changes in memory if a reload flush fails.
    private boolean retainGraphAfterFailedFlush, stopping;
    private volatile boolean saveInFlight, storageAvailable;

    public PlayerBlockList(final FreedomAPI plugin)
    {
        super(plugin);
        storage = new PlayerBlockStorage(
                plugin.getDataFolder().toPath().resolve(CONFIG_FILENAME));
    }

    public boolean isAvailable()
    {
        return storageAvailable;
    }

    public boolean hasBlocked(final UUID blocker, final UUID blocked)
    {
        Objects.requireNonNull(blocker, "blocker");
        Objects.requireNonNull(blocked, "blocked");
        return blockedPlayers.getOrDefault(blocker, Set.of()).contains(blocked);
    }

    public Set<UUID> getBlockedPlayers(final UUID blocker)
    {
        Objects.requireNonNull(blocker, "blocker");
        return blockedPlayers.getOrDefault(blocker, Set.of());
    }

    public boolean isProtectedTarget(final OfflinePlayer target)
    {
        Objects.requireNonNull(target, "target");

        final boolean activeAdmin = Optional.ofNullable(target.getPlayer())
                .filter(plugin.admins()::isAdmin)
                .isPresent()
                || Optional.ofNullable(plugin.admins().getAdminByUuid(target.getUniqueId()))
                .filter(Admin::isActive)
                .isPresent();
        final Optional<String> targetName = Optional.ofNullable(target.getName());
        final boolean activeAdminByName = targetName
                .map(plugin.admins()::getEntryByName)
                .filter(Admin::isActive)
                .isPresent();
        return isProtectedIdentity(
                activeAdmin || activeAdminByName,
                targetName,
                ConfigEntry.SERVER_OWNERS.getStringList(),
                FUtil.DEVELOPERS);
    }

    public Optional<Boolean> block(final UUID blocker, final UUID blocked)
    {
        Objects.requireNonNull(blocker, "blocker");
        Objects.requireNonNull(blocked, "blocked");
        requireMainThread();

        if (!storageAvailable)
            return Optional.empty();
        if (blocker.equals(blocked)
                || hasBlocked(blocker, blocked)
                || isProtectedTarget(server.getOfflinePlayer(blocked)))
            return Optional.of(false);

        final Map<UUID, Set<UUID>> nextGraph = mutableGraph();
        final Set<UUID> targets = new HashSet<>(nextGraph.getOrDefault(blocker, Set.of()));
        targets.add(blocked);
        nextGraph.put(blocker, Set.copyOf(targets));
        publishGraph(nextGraph);
        refreshOnlinePair(blocker, blocked);
        return Optional.of(true);
    }

    public Optional<Boolean> unblock(final UUID blocker, final UUID blocked)
    {
        Objects.requireNonNull(blocker, "blocker");
        Objects.requireNonNull(blocked, "blocked");
        requireMainThread();

        if (!storageAvailable)
            return Optional.empty();
        if (!hasBlocked(blocker, blocked))
            return Optional.of(false);

        final Map<UUID, Set<UUID>> nextGraph = mutableGraph();
        final Set<UUID> targets = new HashSet<>(nextGraph.getOrDefault(blocker, Set.of()));
        targets.remove(blocked);
        if (targets.isEmpty())
            nextGraph.remove(blocker);
        else
            nextGraph.put(blocker, Set.copyOf(targets));

        publishGraph(nextGraph);
        refreshOnlinePair(blocker, blocked);
        return Optional.of(true);
    }

    public Optional<Integer> clear(final UUID blocker)
    {
        Objects.requireNonNull(blocker, "blocker");
        requireMainThread();

        if (!storageAvailable)
            return Optional.empty();

        final Set<UUID> removedTargets = getBlockedPlayers(blocker);
        if (removedTargets.isEmpty())
            return Optional.of(0);

        final Map<UUID, Set<UUID>> nextGraph = mutableGraph();
        nextGraph.remove(blocker);
        publishGraph(nextGraph);
        removedTargets.forEach(blocked -> refreshOnlinePair(blocker, blocked));
        return Optional.of(removedTargets.size());
    }

    public void refreshVisibility(final Player viewer, final Player target)
    {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(target, "target");
        requireMainThread();

        if (viewer.getUniqueId().equals(target.getUniqueId()))
            return;

        final VisibilityPair pair = new VisibilityPair(
                viewer.getUniqueId(),
                target.getUniqueId());
        final boolean blocked = hasEffectiveBlock(viewer, target);
        if (!blocked)
            clearTemporaryReveal(pair);

        final boolean temporarilyRevealed = blocked && revealTasks.containsKey(pair);
        final boolean visibleThroughVanish = plugin.vanish().canSee(viewer, target);
        if (shouldShow(blocked, temporarilyRevealed, visibleThroughVanish))
            viewer.showPlayer(plugin, target);
        else
            viewer.hidePlayer(plugin, target);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(final PlayerJoinEvent event)
    {
        final Player joinedPlayer = event.getPlayer();
        server.getOnlinePlayers()
                .stream()
                .filter(player -> !player.getUniqueId().equals(joinedPlayer.getUniqueId()))
                .forEach(player ->
                {
                    refreshVisibility(joinedPlayer, player);
                    refreshVisibility(player, joinedPlayer);
                });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(final PlayerQuitEvent event)
    {
        clearTemporaryRevealsFor(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event)
    {
        revealNearbyAction(event.getPlayer(), event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(final BlockPlaceEvent event)
    {
        if (!event.canBuild())
            return;

        revealNearbyAction(event.getPlayer(), event.getBlockPlaced().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(final PlayerInteractEvent event)
    {
        final Optional<Block> clickedBlock =
                Optional.ofNullable(event.getClickedBlock());
        if (!shouldRevealInteraction(clickedBlock.isPresent(), event.useInteractedBlock()))
            return;

        revealNearbyAction(
                event.getPlayer(),
                clickedBlock.orElseThrow().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(final PlayerBucketEmptyEvent event)
    {
        revealNearbyAction(event.getPlayer(), event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(final PlayerBucketFillEvent event)
    {
        revealNearbyAction(event.getPlayer(), event.getBlock().getLocation());
    }

    @Override
    public void onStart()
    {
        clearAllTemporaryReveals();
        synchronized (lifecycleStateLock)
        {
            lifecycleEpoch++;
            stopping = false;
            saveInFlight = false;
        }
        pendingSaveTask.ifPresent(BukkitTask::cancel);
        pendingSaveTask = Optional.empty();
        startProtectedTargetRefresh();

        if (retainGraphAfterFailedFlush)
        {
            storageAvailable = false;
            refreshAllVisibility();
            scheduleSave(RETRY_DELAY_TICKS);
            return;
        }

        final Optional<Map<UUID, Set<UUID>>> loadedGraph = storage.read();
        if (loadedGraph.isEmpty())
        {
            storageAvailable = false;
            FLog.error(String.format(
                    "Player blocks are unavailable until %s is repaired and services are restarted",
                    CONFIG_FILENAME));
            refreshAllVisibility();
            return;
        }

        blockedPlayers = immutableGraph(loadedGraph.orElseThrow());
        changeGeneration = 0L;
        lastWrittenGeneration = 0L;
        retainGraphAfterFailedFlush = false;
        storageAvailable = true;
        refreshAllVisibility();
    }

    @Override
    public void onStop()
    {
        clearAllTemporaryReveals();
        synchronized (lifecycleStateLock)
        {
            lifecycleEpoch++;
            stopping = true;
        }
        pendingSaveTask.ifPresent(BukkitTask::cancel);
        pendingSaveTask = Optional.empty();
        protectedTargetRefreshTask.ifPresent(BukkitTask::cancel);
        protectedTargetRefreshTask = Optional.empty();
        protectedOnlineTargets = Set.of();

        final boolean hasUnwrittenChanges = changeGeneration > lastWrittenGeneration;
        if (hasUnwrittenChanges)
        {
            final boolean successful;
            synchronized (fileWriteLock)
            {
                successful = storage.write(blockedPlayers);
            }
            storageAvailable = successful;
            retainGraphAfterFailedFlush = shouldRetainGraphAfterRestart(
                    hasUnwrittenChanges,
                    successful);
            if (successful)
                lastWrittenGeneration = changeGeneration;
        }
        else
            retainGraphAfterFailedFlush = false;
        saveInFlight = false;
    }

    private static boolean shouldShow(
            final boolean blocked,
            final boolean temporarilyRevealed,
            final boolean visibleThroughVanish)
    {
        return (!blocked || temporarilyRevealed) && visibleThroughVanish;
    }

    private static boolean isProtectedIdentity(
            final boolean activeAdmin,
            final Optional<String> targetName,
            final Collection<String> ownerNames,
            final Collection<String> developerNames)
    {
        Objects.requireNonNull(targetName, "targetName");
        Objects.requireNonNull(ownerNames, "ownerNames");
        Objects.requireNonNull(developerNames, "developerNames");
        if (activeAdmin)
            return true;

        return targetName
                .filter(name -> ownerNames
                        .stream()
                        .anyMatch(name::equalsIgnoreCase)
                        || developerNames
                        .stream()
                        .anyMatch(name::equalsIgnoreCase))
                .isPresent();
    }

    private static Set<UUID> changedProtectedTargets(
            final Set<UUID> previousTargets,
            final Set<UUID> currentTargets)
    {
        Objects.requireNonNull(previousTargets, "previousTargets");
        Objects.requireNonNull(currentTargets, "currentTargets");
        return Stream.concat(
                        previousTargets
                                .stream()
                                .filter(target -> !currentTargets.contains(target)),
                        currentTargets
                                .stream()
                                .filter(target -> !previousTargets.contains(target)))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean isNearBlock(
            final Location location,
            final int blockX,
            final int blockY,
            final int blockZ)
    {
        final double xDistance = axisDistance(location.getX(), blockX, blockX + 1.0);
        final double yDistance = axisDistance(location.getY(), blockY, blockY + 1.0);
        final double zDistance = axisDistance(location.getZ(), blockZ, blockZ + 1.0);
        return xDistance * xDistance + yDistance * yDistance + zDistance * zDistance
                <= TEMPORARY_REVEAL_RADIUS_SQUARED;
    }

    private static boolean shouldRevealInteraction(
            final boolean hasClickedBlock,
            final Result useInteractedBlock)
    {
        return hasClickedBlock && useInteractedBlock != Result.DENY;
    }

    private static boolean shouldRetainGraphAfterRestart(
            final boolean hasUnwrittenChanges,
            final boolean flushSuccessful)
    {
        return hasUnwrittenChanges && !flushSuccessful;
    }

    private Map<UUID, Set<UUID>> mutableGraph()
    {
        return new HashMap<>(blockedPlayers);
    }

    private static Map<UUID, Set<UUID>> immutableGraph(
            final Map<UUID, Set<UUID>> source)
    {
        return source.entrySet()
                .stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> Set.copyOf(entry.getValue())));
    }

    private static double axisDistance(
            final double value,
            final double minimum,
            final double maximum)
    {
        if (value < minimum)
            return minimum - value;
        if (value > maximum)
            return value - maximum;
        return 0.0;
    }

    private void publishGraph(final Map<UUID, Set<UUID>> nextGraph)
    {
        blockedPlayers = immutableGraph(nextGraph);
        changeGeneration++;
        scheduleSave(SAVE_DELAY_TICKS);
    }

    private void refreshOnlinePair(final UUID viewerId, final UUID targetId)
    {
        Optional.ofNullable(server.getPlayer(viewerId))
                .ifPresent(viewer -> Optional.ofNullable(server.getPlayer(targetId))
                        .ifPresent(target -> refreshVisibility(viewer, target)));
    }

    private void refreshAllVisibility()
    {
        server.getOnlinePlayers()
                .forEach(viewer -> server.getOnlinePlayers()
                        .stream()
                        .filter(target -> !viewer.getUniqueId().equals(target.getUniqueId()))
                        .forEach(target -> refreshVisibility(viewer, target)));
    }

    private void startProtectedTargetRefresh()
    {
        protectedTargetRefreshTask.ifPresent(BukkitTask::cancel);
        protectedOnlineTargets = protectedOnlineTargetIds();
        protectedTargetRefreshTask = Optional.of(server.getScheduler().runTaskTimer(
                plugin,
                FTask.guard(
                        "PlayerBlockList/protectedTargetRefresh",
                        this::refreshProtectedTargetTransitions),
                PROTECTED_TARGET_REFRESH_TICKS,
                PROTECTED_TARGET_REFRESH_TICKS));
    }

    private void refreshProtectedTargetTransitions()
    {
        final Set<UUID> currentTargets = protectedOnlineTargetIds();
        final Set<UUID> changedTargets = changedProtectedTargets(
                protectedOnlineTargets,
                currentTargets);
        protectedOnlineTargets = currentTargets;
        changedTargets.forEach(targetId -> Optional.ofNullable(server.getPlayer(targetId))
                .ifPresent(target -> server.getOnlinePlayers()
                        .stream()
                        .filter(viewer -> !viewer.getUniqueId().equals(targetId))
                        .forEach(viewer -> refreshVisibility(viewer, target))));
    }

    private Set<UUID> protectedOnlineTargetIds()
    {
        return server.getOnlinePlayers()
                .stream()
                .filter(this::isProtectedTarget)
                .map(Player::getUniqueId)
                .collect(Collectors.toUnmodifiableSet());
    }

    private void revealNearbyAction(final Player actor, final Location actionLocation)
    {
        Optional.ofNullable(actionLocation.getWorld())
                .ifPresent(world -> world.getPlayers()
                        .stream()
                        .filter(viewer -> hasEffectiveBlock(viewer, actor))
                        .filter(viewer -> isNearBlock(
                                viewer.getLocation(),
                                actionLocation.getBlockX(),
                                actionLocation.getBlockY(),
                                actionLocation.getBlockZ()))
                        .forEach(viewer -> beginTemporaryReveal(viewer, actor)));
    }

    private boolean hasEffectiveBlock(final Player viewer, final Player target)
    {
        return !isProtectedTarget(target)
                && hasBlocked(viewer.getUniqueId(), target.getUniqueId());
    }

    private void beginTemporaryReveal(final Player viewer, final Player target)
    {
        final VisibilityPair pair = new VisibilityPair(
                viewer.getUniqueId(),
                target.getUniqueId());
        clearTemporaryReveal(pair);

        final BukkitTask expiryTask = server.getScheduler().runTaskLater(
                plugin,
                FTask.guard(
                        "PlayerBlockList/revealExpiry",
                        () -> finishTemporaryReveal(pair)),
                TEMPORARY_REVEAL_TICKS);
        revealTasks.put(pair, expiryTask);
        refreshVisibility(viewer, target);
    }

    private void finishTemporaryReveal(final VisibilityPair pair)
    {
        revealTasks.remove(pair);
        Optional.ofNullable(server.getPlayer(pair.viewer()))
                .ifPresent(viewer -> Optional.ofNullable(server.getPlayer(pair.target()))
                        .ifPresent(target -> refreshVisibility(viewer, target)));
    }

    private void clearTemporaryReveal(final VisibilityPair pair)
    {
        Optional.ofNullable(revealTasks.remove(pair))
                .ifPresent(BukkitTask::cancel);
    }

    private void clearTemporaryRevealsFor(final UUID player)
    {
        revealTasks.entrySet().removeIf(entry ->
        {
            final VisibilityPair pair = entry.getKey();
            if (!pair.viewer().equals(player) && !pair.target().equals(player))
                return false;

            entry.getValue().cancel();
            return true;
        });
    }

    private void clearAllTemporaryReveals()
    {
        revealTasks.values().forEach(BukkitTask::cancel);
        revealTasks.clear();
    }

    private void scheduleSave(final long delayTicks)
    {
        if (stopping || pendingSaveTask.isPresent() || saveInFlight)
            return;

        pendingSaveTask = Optional.of(server.getScheduler().runTaskLater(
                plugin,
                FTask.guard("PlayerBlockList/save", this::dispatchSave),
                delayTicks));
    }

    private void dispatchSave()
    {
        pendingSaveTask = Optional.empty();
        if (stopping || saveInFlight || changeGeneration <= lastWrittenGeneration)
            return;

        saveInFlight = true;
        final long epoch = lifecycleEpoch;
        final long generation = changeGeneration;
        final Map<UUID, Set<UUID>> snapshot = blockedPlayers;
        server.getAsyncScheduler().runNow(
                plugin,
                FTask.guardAsync(
                        "PlayerBlockList/write",
                        task -> persistGeneration(epoch, generation, snapshot)));
    }

    private void persistGeneration(
            final long epoch,
            final long generation,
            final Map<UUID, Set<UUID>> snapshot)
    {
        boolean successful = false;
        try
        {
            if (epoch == lifecycleEpoch)
            {
                synchronized (fileWriteLock)
                {
                    if (epoch == lifecycleEpoch)
                        successful = storage.write(snapshot);
                }
            }
            if (!successful && epoch == lifecycleEpoch)
                storageAvailable = false;
        }
        finally
        {
            scheduleSaveCompletion(epoch, generation, successful);
        }
    }

    private void scheduleSaveCompletion(
            final long epoch,
            final long generation,
            final boolean successful)
    {
        try
        {
            server.getScheduler().runTask(
                    plugin,
                    FTask.guard(
                            "PlayerBlockList/finishSave",
                            () -> finishSave(epoch, generation, successful)));
        }
        catch (RuntimeException ex)
        {
            synchronized (lifecycleStateLock)
            {
                if (epoch != lifecycleEpoch)
                    return;

                saveInFlight = false;
                storageAvailable = false;
            }
            FLog.error(String.format(
                    "Could not schedule player block save completion: %s",
                    ex.getMessage()));
        }
    }

    private void finishSave(
            final long epoch,
            final long generation,
            final boolean successful)
    {
        if (epoch != lifecycleEpoch)
            return;

        saveInFlight = false;
        if (successful)
        {
            lastWrittenGeneration = Math.max(lastWrittenGeneration, generation);
            retainGraphAfterFailedFlush = false;
            storageAvailable = true;
        }
        else
            storageAvailable = false;

        if (stopping)
            return;

        if (!successful)
        {
            scheduleSave(RETRY_DELAY_TICKS);
            return;
        }

        if (changeGeneration > lastWrittenGeneration)
            scheduleSave(SAVE_DELAY_TICKS);
    }

    private void requireMainThread()
    {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Player block mutations must run on the server thread.");
    }

    private record VisibilityPair(UUID viewer, UUID target)
    {
    }
}

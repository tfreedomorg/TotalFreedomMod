package me.totalfreedom.totalfreedommod.player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.framework.FatalServiceStartupException;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FTask;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

public class PlayerBlockList extends FreedomService
{
    private static final String CONFIG_FILENAME = "player-blocks.yml";
    private static final String BLOCKS_PATH = "blocks";
    private static final int CONFIG_VERSION = 1;
    private static final long SAVE_DELAY_TICKS = 20L;
    private static final long MAX_SAVE_RETRY_DELAY_TICKS = 20L * 60L;

    // Async chat reads this graph while commands mutate it on the server thread.
    // The outer key is the blocker/viewer and the inner values are blocked players.
    private final File configFile;
    private final Object fileWriteLock = new Object();
    private final Object persistenceLock = new Object();
    private volatile BlockGraph blockGraph = createEmptyGraph();
    private BukkitTask pendingSaveTask;
    private volatile long lifecycleEpoch;
    private long changeGeneration;
    private long lastWrittenGeneration;
    private long saveRetryDelayTicks = SAVE_DELAY_TICKS;
    private boolean saveInFlight;
    private boolean loadHadSemanticErrors;
    private volatile boolean persistenceAvailable = true;
    private volatile boolean stopping;

    public PlayerBlockList(final TotalFreedomMod plugin)
    {
        super(plugin);
        this.configFile = new File(plugin.getDataFolder(), CONFIG_FILENAME);
    }

    public boolean hasBlocked(final UUID blocker, final UUID blocked)
    {
        Objects.requireNonNull(blocker, "blocker");
        Objects.requireNonNull(blocked, "blocked");

        final Set<UUID> blockedByBlocker = blockGraph.blockedPlayers().get(blocker);
        return blockedByBlocker != null && blockedByBlocker.contains(blocked);
    }

    public boolean block(final UUID blocker, final UUID blocked)
    {
        Objects.requireNonNull(blocker, "blocker");
        Objects.requireNonNull(blocked, "blocked");
        requireMainThread();

        if (blocker.equals(blocked))
            return false;

        final BlockGraph currentGraph = blockGraph;
        final boolean added = currentGraph.blockedPlayers()
                .computeIfAbsent(blocker, ignored -> new ConcurrentSkipListSet<>())
                .add(blocked);

        if (added)
        {
            currentGraph.indexedBlockedPlayers()
                    .computeIfAbsent(blocker, ignored -> new IndexedUuidSet())
                    .add(blocked);
            currentGraph.blockersByBlockedPlayer()
                    .computeIfAbsent(blocked, ignored -> ConcurrentHashMap.newKeySet())
                    .add(blocker);
            queueSave();
        }

        return added;
    }

    public boolean unblock(final UUID blocker, final UUID blocked)
    {
        Objects.requireNonNull(blocker, "blocker");
        Objects.requireNonNull(blocked, "blocked");
        requireMainThread();

        final BlockGraph currentGraph = blockGraph;
        final Set<UUID> blockedByBlocker = currentGraph.blockedPlayers().get(blocker);

        if (blockedByBlocker == null)
            return false;

        final boolean removed = blockedByBlocker.remove(blocked);
        if (!removed)
            return false;

        if (blockedByBlocker.isEmpty())
            currentGraph.blockedPlayers().remove(blocker, blockedByBlocker);

        final IndexedUuidSet indexedTargets = currentGraph.indexedBlockedPlayers().get(blocker);
        if (indexedTargets != null)
        {
            indexedTargets.remove(blocked);
            if (indexedTargets.isEmpty())
                currentGraph.indexedBlockedPlayers().remove(blocker, indexedTargets);
        }

        removeReverseEdge(currentGraph, blocker, blocked);
        queueSave();
        return true;
    }

    public Set<UUID> getBlockedPlayers(final UUID blocker)
    {
        Objects.requireNonNull(blocker, "blocker");

        final Set<UUID> blockedByBlocker = blockGraph.blockedPlayers().get(blocker);
        return blockedByBlocker == null ? Set.of() : Set.copyOf(blockedByBlocker);
    }

    public boolean hasBlockedAnyone(final UUID blocker)
    {
        Objects.requireNonNull(blocker, "blocker");

        final Set<UUID> blockedByBlocker = blockGraph.blockedPlayers().get(blocker);
        return blockedByBlocker != null && !blockedByBlocker.isEmpty();
    }

    public boolean isBlockedByAnyone(final UUID blocked)
    {
        Objects.requireNonNull(blocked, "blocked");

        final Set<UUID> blockers = blockGraph.blockersByBlockedPlayer().get(blocked);
        return blockers != null && !blockers.isEmpty();
    }

    public Set<UUID> getBlockers(final UUID blocked)
    {
        Objects.requireNonNull(blocked, "blocked");

        final Set<UUID> blockers = blockGraph.blockersByBlockedPlayer().get(blocked);
        return blockers == null ? Set.of() : Set.copyOf(blockers);
    }

    public int getBlockedPlayerCount(final UUID blocker)
    {
        Objects.requireNonNull(blocker, "blocker");
        final IndexedUuidSet targets = blockGraph.indexedBlockedPlayers().get(blocker);
        return targets == null ? 0 : targets.size();
    }

    public List<UUID> getBlockedPlayerPage(
            final UUID blocker,
            final int offset,
            final int limit)
    {
        Objects.requireNonNull(blocker, "blocker");
        if (offset < 0 || limit < 1)
            return List.of();

        final IndexedUuidSet targets = blockGraph.indexedBlockedPlayers().get(blocker);
        if (targets == null)
            return List.of();
        return targets.page(offset, limit);
    }

    public int clear(final UUID blocker)
    {
        Objects.requireNonNull(blocker, "blocker");
        requireMainThread();

        final BlockGraph currentGraph = blockGraph;
        final Set<UUID> blockedByBlocker = currentGraph.blockedPlayers().remove(blocker);

        if (blockedByBlocker == null)
            return 0;

        final int count = blockedByBlocker.size();
        currentGraph.indexedBlockedPlayers().remove(blocker);
        if (count > 0)
        {
            blockedByBlocker.forEach(blocked -> removeReverseEdge(currentGraph, blocker, blocked));
            queueSave();
        }

        return count;
    }

    public boolean areSeparated(final UUID first, final UUID second)
    {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");

        final Map<UUID, Set<UUID>> blockedPlayers = blockGraph.blockedPlayers();
        final Set<UUID> firstTargets = blockedPlayers.get(first);
        final Set<UUID> secondTargets = blockedPlayers.get(second);
        return firstTargets != null && firstTargets.contains(second)
                || secondTargets != null && secondTargets.contains(first);
    }

    public boolean validateStorage()
    {
        requireMainThread();
        return readBlockGraph().isPresent();
    }

    @Override
    protected void onStart()
    {
        lifecycleEpoch++;
        stopping = false;
        saveRetryDelayTicks = SAVE_DELAY_TICKS;
        if (!load())
        {
            throw new FatalServiceStartupException(String.format(
                    "Refusing to start with an unreadable or invalid %s",
                    CONFIG_FILENAME));
        }
        changeGeneration = 0L;
        synchronized (persistenceLock)
        {
            lastWrittenGeneration = 0L;
            saveInFlight = false;
        }
    }

    @Override
    protected void onStop()
    {
        // Invalidate asynchronous work dispatched by the previous service lifecycle. This matters
        // for /tfm reload, where the same service object is started again immediately.
        lifecycleEpoch++;
        stopping = true;
        if (pendingSaveTask != null)
        {
            pendingSaveTask.cancel();
            pendingSaveTask = null;
        }

        if (needsSave())
            writeSnapshot(createSnapshot(lifecycleEpoch, changeGeneration));
    }

    private boolean load()
    {
        final Optional<BlockGraph> loadedGraph = readBlockGraph();
        if (loadedGraph.isEmpty())
        {
            persistenceAvailable = false;
            return false;
        }

        blockGraph = loadedGraph.orElseThrow();
        persistenceAvailable = true;
        return true;
    }

    private Optional<BlockGraph> readBlockGraph()
    {
        if (!configFile.exists())
            return Optional.of(createEmptyGraph());

        final YamlConfiguration config = new YamlConfiguration();
        loadHadSemanticErrors = false;
        try
        {
            config.load(configFile);
        }
        catch (IOException | InvalidConfigurationException ex)
        {
            FLog.severe(String.format("Could not load %s; preserving the existing file: %s",
                    CONFIG_FILENAME,
                    ex.getMessage()));
            return Optional.empty();
        }

        final ConfigurationSection blocksSection = config.getConfigurationSection(BLOCKS_PATH);
        if (blocksSection == null)
        {
            if (config.contains(BLOCKS_PATH))
            {
                FLog.severe(String.format("Invalid '%s' value in %s; preserving the existing file",
                        BLOCKS_PATH,
                        CONFIG_FILENAME));
                return Optional.empty();
            }

            FLog.severe(String.format("Missing '%s' section in %s", BLOCKS_PATH, CONFIG_FILENAME));
            return Optional.empty();
        }

        final Map<UUID, Set<UUID>> loadedPlayers = new ConcurrentHashMap<>();
        blocksSection.getKeys(false)
                .stream()
                .sorted()
                .forEach(blockerValue -> loadBlocker(blocksSection, blockerValue, loadedPlayers));

        if (loadHadSemanticErrors)
        {
            FLog.severe(String.format(
                    "Invalid entries were found in %s; preserving the existing file",
                    CONFIG_FILENAME));
            return Optional.empty();
        }

        return Optional.of(createGraph(loadedPlayers));
    }

    private void loadBlocker(final ConfigurationSection blocksSection,
                             final String blockerValue,
                             final Map<UUID, Set<UUID>> loadedPlayers)
    {
        final Optional<UUID> blocker = parseUuid(blockerValue, "blocker");
        if (blocker.isEmpty())
            return;
        final UUID blockerId = blocker.orElseThrow();

        final Object targetsValue = blocksSection.get(blockerValue);
        if (!(targetsValue instanceof List<?> targets))
        {
            loadHadSemanticErrors = true;
            FLog.warning(String.format("Ignoring non-list block entry for %s in %s",
                    blockerValue,
                    CONFIG_FILENAME));
            return;
        }

        final Set<UUID> loadedTargets = new ConcurrentSkipListSet<>();
        targets.forEach(targetValue -> loadTarget(blockerId, targetValue, loadedTargets));

        if (!loadedTargets.isEmpty())
            loadedPlayers.put(blockerId, loadedTargets);
    }

    private void loadTarget(final UUID blocker, final Object targetValue, final Set<UUID> loadedTargets)
    {
        if (!(targetValue instanceof String targetString))
        {
            loadHadSemanticErrors = true;
            FLog.warning(String.format("Ignoring non-string blocked player for %s in %s",
                    blocker,
                    CONFIG_FILENAME));
            return;
        }

        final Optional<UUID> target = parseUuid(targetString, "blocked player");
        if (target.isEmpty())
            return;
        final UUID targetId = target.orElseThrow();

        if (blocker.equals(targetId))
        {
            loadHadSemanticErrors = true;
            FLog.warning(String.format("Ignoring self-block entry for %s in %s", blocker, CONFIG_FILENAME));
            return;
        }

        loadedTargets.add(targetId);
    }

    private Optional<UUID> parseUuid(final String value, final String description)
    {
        try
        {
            return Optional.of(UUID.fromString(value));
        }
        catch (IllegalArgumentException ex)
        {
            loadHadSemanticErrors = true;
            FLog.warning(String.format("Ignoring invalid %s UUID '%s' in %s",
                    description,
                    value,
                    CONFIG_FILENAME));
            return Optional.empty();
        }
    }

    private static BlockGraph createEmptyGraph()
    {
        return new BlockGraph(
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>());
    }

    private static BlockGraph createGraph(final Map<UUID, Set<UUID>> blockedPlayers)
    {
        final Map<UUID, Set<UUID>> blockersByBlockedPlayer = new ConcurrentHashMap<>();
        final Map<UUID, IndexedUuidSet> indexedBlockedPlayers = new ConcurrentHashMap<>();
        blockedPlayers.forEach((blocker, targets) -> targets.forEach(blocked -> blockersByBlockedPlayer
                .computeIfAbsent(blocked, ignored -> ConcurrentHashMap.newKeySet())
                .add(blocker)));
        blockedPlayers.forEach((blocker, targets) ->
        {
            final IndexedUuidSet indexedTargets = new IndexedUuidSet();
            targets.forEach(indexedTargets::add);
            if (!indexedTargets.isEmpty())
                indexedBlockedPlayers.put(blocker, indexedTargets);
        });
        return new BlockGraph(blockedPlayers, blockersByBlockedPlayer, indexedBlockedPlayers);
    }

    private void removeReverseEdge(final BlockGraph graph, final UUID blocker, final UUID blocked)
    {
        final Set<UUID> blockers = graph.blockersByBlockedPlayer().get(blocked);
        if (blockers == null)
            return;

        blockers.remove(blocker);
        if (blockers.isEmpty())
            graph.blockersByBlockedPlayer().remove(blocked, blockers);
    }

    private void queueSave()
    {
        changeGeneration++;

        if (!persistenceAvailable)
            return;

        if (pendingSaveTask != null || isSaveInFlight())
            return;

        pendingSaveTask = server.getScheduler().runTaskLater(
                plugin,
                FTask.guard("PlayerBlockList/queueSave", this::dispatchSave),
                SAVE_DELAY_TICKS);
    }

    private void dispatchSave()
    {
        pendingSaveTask = null;
        if (stopping || !beginAsyncSave())
            return;

        final long epoch = lifecycleEpoch;
        final long generation = changeGeneration;
        server.getAsyncScheduler().runNow(
                plugin,
                FTask.guardAsync("PlayerBlockList/save", task -> persistGeneration(epoch, generation)));
    }

    private void persistGeneration(final long epoch, final long generation)
    {
        boolean successful = false;
        try
        {
            successful = writeSnapshot(createSnapshot(epoch, generation));
        }
        finally
        {
            finishAsyncSave(epoch, successful);
        }
    }

    private void finishAsyncSave(final long epoch, final boolean successful)
    {
        synchronized (persistenceLock)
        {
            if (epoch != lifecycleEpoch)
                return;
            saveInFlight = false;
        }

        if (stopping)
            return;

        try
        {
            server.getScheduler().runTask(
                    plugin,
                    FTask.guard("PlayerBlockList/finishSave", () ->
                    {
                        if (pendingSaveTask != null || stopping)
                            return;

                        if (!needsSave())
                        {
                            if (successful)
                                saveRetryDelayTicks = SAVE_DELAY_TICKS;
                            return;
                        }

                        final long delayTicks;
                        if (successful)
                        {
                            saveRetryDelayTicks = SAVE_DELAY_TICKS;
                            delayTicks = SAVE_DELAY_TICKS;
                        }
                        else
                        {
                            delayTicks = saveRetryDelayTicks;
                            saveRetryDelayTicks = Math.min(
                                    MAX_SAVE_RETRY_DELAY_TICKS,
                                    saveRetryDelayTicks * 2L);
                        }

                        pendingSaveTask = server.getScheduler().runTaskLater(
                                plugin,
                                FTask.guard("PlayerBlockList/queueFollowupSave", this::dispatchSave),
                                delayTicks);
                    }));
        }
        catch (IllegalStateException ignored) {}
    }

    private boolean beginAsyncSave()
    {
        synchronized (persistenceLock)
        {
            if (!persistenceAvailable || saveInFlight)
                return false;
            saveInFlight = true;
            return true;
        }
    }

    private boolean isSaveInFlight()
    {
        synchronized (persistenceLock)
        {
            return saveInFlight;
        }
    }

    private boolean needsSave()
    {
        synchronized (persistenceLock)
        {
            return persistenceAvailable && changeGeneration > lastWrittenGeneration;
        }
    }

    private BlockSnapshot createSnapshot(final long epoch, final long generation)
    {
        final Map<UUID, List<UUID>> snapshot = new HashMap<>();
        blockGraph.blockedPlayers().forEach((blocker, targets) ->
        {
            final List<UUID> targetSnapshot = targets
                    .stream()
                    .sorted()
                    .toList();
            if (!targetSnapshot.isEmpty())
                snapshot.put(blocker, targetSnapshot);
        });

        return new BlockSnapshot(epoch, generation, Map.copyOf(snapshot));
    }

    private boolean writeSnapshot(final BlockSnapshot snapshot)
    {
        if (isSnapshotObsolete(snapshot))
            return true;

        final YamlConfiguration config = new YamlConfiguration();
        config.set("version", CONFIG_VERSION);
        final ConfigurationSection blocksSection = config.createSection(BLOCKS_PATH);

        snapshot.blockedPlayers()
                .entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> blocksSection.set(
                        entry.getKey().toString(),
                        entry.getValue()
                                .stream()
                                .map(UUID::toString)
                                .toList()));

        final Path configPath = configFile.toPath();
        final Path temporaryPath = configPath.resolveSibling(CONFIG_FILENAME + ".tmp");
        synchronized (fileWriteLock)
        {
            if (isSnapshotObsolete(snapshot))
                return true;

            try
            {
                Files.createDirectories(configPath.getParent());
                Files.writeString(temporaryPath, config.saveToString(), StandardCharsets.UTF_8);
                // Replacing a complete temporary file prevents a partial YAML document on interruption.
                replaceConfig(temporaryPath, configPath);
                synchronized (persistenceLock)
                {
                    lastWrittenGeneration = Math.max(lastWrittenGeneration, snapshot.generation());
                }
                return true;
            }
            catch (IOException ex)
            {
                FLog.severe(String.format("Could not save %s: %s", CONFIG_FILENAME, ex.getMessage()));
                try
                {
                    Files.deleteIfExists(temporaryPath);
                }
                catch (IOException cleanupEx)
                {
                    FLog.warning(String.format("Could not remove temporary %s file: %s",
                            CONFIG_FILENAME,
                            cleanupEx.getMessage()));
                }
                return false;
            }
        }
    }

    private boolean isSnapshotObsolete(final BlockSnapshot snapshot)
    {
        synchronized (persistenceLock)
        {
            return !persistenceAvailable
                    || snapshot.epoch() != lifecycleEpoch
                    || snapshot.generation() <= lastWrittenGeneration;
        }
    }

    private void replaceConfig(final Path temporaryPath, final Path configPath) throws IOException
    {
        try
        {
            Files.move(temporaryPath,
                    configPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        }
        catch (AtomicMoveNotSupportedException ex)
        {
            Files.move(temporaryPath, configPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void requireMainThread()
    {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Player block mutations must run on the server thread.");
    }

    private record BlockSnapshot(
            long epoch,
            long generation,
            Map<UUID, List<UUID>> blockedPlayers)
    {
    }

    private record BlockGraph(
            Map<UUID, Set<UUID>> blockedPlayers,
            Map<UUID, Set<UUID>> blockersByBlockedPlayer,
            Map<UUID, IndexedUuidSet> indexedBlockedPlayers)
    {
    }

    private static final class IndexedUuidSet
    {
        private final List<UUID> values = new ArrayList<>();
        private final Map<UUID, Integer> positions = new HashMap<>();

        synchronized void add(final UUID value)
        {
            if (positions.containsKey(value))
                return;
            positions.put(value, values.size());
            values.add(value);
        }

        synchronized void remove(final UUID value)
        {
            final Integer removedPosition = positions.remove(value);
            if (removedPosition == null)
                return;

            final int lastPosition = values.size() - 1;
            final UUID lastValue = values.remove(lastPosition);
            if (removedPosition < lastPosition)
            {
                values.set(removedPosition, lastValue);
                positions.put(lastValue, removedPosition);
            }
        }

        synchronized int size()
        {
            return values.size();
        }

        synchronized boolean isEmpty()
        {
            return values.isEmpty();
        }

        synchronized List<UUID> page(final int offset, final int limit)
        {
            if (offset >= values.size())
                return List.of();
            final int end = Math.min(values.size(), offset + limit);
            return List.copyOf(values.subList(offset, end));
        }
    }
}

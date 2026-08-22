package me.totalfreedom.totalfreedommod.title;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.display.Displayable;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.sql.PersistenceQueue;
import me.totalfreedom.totalfreedommod.sql.adapter.TitleRepository;
import me.totalfreedom.totalfreedommod.util.*;

import com.google.common.collect.Maps;
import com.google.gson.reflect.TypeToken;

/**
 * Owns the title registry and answers what a player's titles let them do and how they are shown.
 * <p>
 * Titles sit alongside ranks rather than inside them. Where {@code RankManager} resolves a ladder,
 * this resolves a flat set: a player holds zero or more titles, their capabilities are the union of
 * what those titles list, and nothing here compares levels or walks a parent chain. That is what
 * lets an ordinary operator be handed one specific capability without being promoted to reach it.
 * <p>
 * Storage mirrors ranks: SQL is authoritative when available, with {@code titles.json} kept as a
 * readable snapshot and used as the source when there is no database.
 */
public class TitleManager extends FreedomService
{

    public static final String TITLES_FILENAME = "titles.json";

    private static final Type TITLE_MAP_TYPE = new TypeToken<Map<String, Title>>() {}.getType();

    private static final long SHUTDOWN_FLUSH_TIMEOUT_MS = 10L * 1000L;

    /**
     * All titles, keyed by id.
     */
    private final Map<String, Title> titles = Maps.newLinkedHashMap();

    private final PersistenceQueue writes = new PersistenceQueue("title");

    private File titlesFile;

    private boolean usingSql = false;

    public TitleManager(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        loadTitles();
        plugin.dm.whenReady(this::loadTitles);
    }

    @Override
    protected void onStop()
    {
        saveTitles();
        writes.await(SHUTDOWN_FLUSH_TIMEOUT_MS);
    }

    /**
     * Load titles from SQL, falling back to {@code titles.json}. Never blocks: the SQL read runs
     * off-thread and its result is applied back on the main thread.
     */
    public void loadTitles()
    {
        titlesFile = new File(plugin.getDataFolder(), TITLES_FILENAME);

        if (plugin.dm != null && plugin.dm.isInitialized())
        {
            loadFromSqlAsync();
            return;
        }

        loadFromJsonOrDefaults();
    }

    // ========================================================================
    // Lookup
    // ========================================================================

    public Title getTitle(String id)
    {
        return id == null ? null : titles.get(id.toLowerCase());
    }

    public Map<String, Title> getTitles()
    {
        return Collections.unmodifiableMap(titles);
    }

    /**
     * All titles ordered for display, heaviest first.
     */
    public List<Title> getTitlesSorted()
    {
        return titles.values()
                     .stream()
                     .sorted()
                     .toList();
    }

    public boolean hasTitle(String id)
    {
        return id != null && titles.containsKey(id.toLowerCase());
    }

    /**
     * The titles {@code player} actually holds, skipping any id that no longer resolves so that a
     * deleted title simply stops applying rather than breaking every lookup for its holders.
     */
    public List<Title> getHeldTitles(Player player)
    {
        if (player == null)
            return List.of();

        final PlayerData data = plugin.pl.getData(player);

        return data == null ? List.of() : resolve(data.getTitles());
    }

    /**
     * Resolves a set of stored ids into titles, ordered for display.
     */
    public List<Title> resolve(Collection<String> ids)
    {
        return ids == null
                ? List.of()
                : ids.stream()
                     .map(this::getTitle)
                     .filter(title -> title != null)
                     .sorted()
                     .toList();
    }

    /**
     * The title that should represent {@code player} on screen, or {@code null} when they hold
     * none. The heaviest held title wins; see {@link Title#getWeight()}.
     */
    public Title getDisplayTitle(Player player)
    {
        return getHeldTitles(player).stream()
                                    .filter(Title::isAnnounce)
                                    .findFirst()
                                    .orElse(null);
    }

    // ========================================================================
    // Permissions
    // ========================================================================

    /**
     * Whether any title {@code sender} holds grants {@code permission}.
     * <p>
     * Only players can hold titles: a title is attached to a player profile, and a console channel
     * has no profile to attach one to. Console standing comes from its {@code host_senders:}
     * binding instead, which is a rank question rather than a title one.
     */
    public boolean grants(CommandSender sender, String permission)
    {
        return sender instanceof Player player && grants(player, permission);
    }

    public boolean grants(Player player, String permission)
    {
        return getHeldTitles(player).stream()
                                    .anyMatch(title -> title.grants(permission));
    }

    /**
     * Grants a title to a player. Returns false when the title does not exist or is already held.
     */
    public boolean grantTitle(Player player, String titleId)
    {
        final Title title = getTitle(titleId);
        if (title == null)
            return false;

        final PlayerData data = plugin.pl.getData(player);
        if (data == null || !data.addTitle(title.getId()))
            return false;

        plugin.pl.saveData(data);
        refreshDisplay(player);

        return true;
    }

    /**
     * Revokes a title from a player. Returns false when they did not hold it.
     */
    public boolean revokeTitle(Player player, String titleId)
    {
        final PlayerData data = plugin.pl.getData(player);
        if (data == null || titleId == null || !data.removeTitle(titleId.toLowerCase()))
            return false;

        plugin.pl.saveData(data);
        refreshDisplay(player);

        return true;
    }

    /**
     * Re-applies the player's tag after their titles changed.
     * <p>
     * A tag the player set by hand is left alone: they chose it deliberately, and having a title
     * grant silently overwrite it would be surprising.
     */
    private void refreshDisplay(Player player)
    {
        if (plugin.rm == null)
            return;

        plugin.rm.updatePlayerTeam(player);

        // A title can open the admin world, and that check is cached per player for 30s. Drop the
        // cache now so a grant or revoke takes effect immediately rather than on the next sweep.
        if (plugin.wm != null && plugin.wm.adminworld != null)
        {
            plugin.wm.adminworld.wipeAccessCache();
        }

        final PlayerData data = plugin.pl.getData(player);
        if (data == null || data.getSavedTag() != null)
            return;

        final Displayable display = plugin.rm.getDisplay(player);
        if (display != null)
        {
            plugin.pl.getPlayer(player)
                     .setTag(AdventureUtil.componentToLegacySection(display.getColoredTag()));
        }
    }

    // ========================================================================
    // Mutation
    // ========================================================================

    public void setTitle(Title title)
    {
        titles.put(title.getId(), title);
        saveTitles();
    }

    public boolean removeTitle(String id)
    {
        if (id == null)
            return false;

        final Title removed = titles.remove(Title.normalizeId(id));
        if (removed == null)
            return false;

        // saveTitles() only writes the titles that survive, so without an explicit delete the row
        // stays behind in SQL and the title returns on the next load.
        if (usingSql && plugin.dm != null && plugin.dm.isInitialized())
        {
            writes.enqueue(plugin.dm.getTitleRepository()
                    .deleteAsync(removed.getId())
                    .onErrorResume(ex ->
                    {
                        FLog.severe(String.format("Could not delete title %s from SQL: %s",
                                removed.getId(), ex.getMessage()));
                        return Mono.empty();
                    })
                    .then());
        }

        saveTitles();

        return true;
    }

    // ========================================================================
    // Persistence
    // ========================================================================

    private void loadFromSqlAsync()
    {
        final TitleRepository repo = plugin.dm.getTitleRepository();
        plugin.dm.readAsync("TitleManager/loadFromSql", repo.loadAllAsync(),
                loaded -> applyLoadedTitles(repo, loaded),
                () ->
                {
                    usingSql = false;
                    loadFromJsonOrDefaults();
                });
    }

    private void applyLoadedTitles(final TitleRepository repo, final Map<String, Title> loaded)
    {
        usingSql = true;

        if (loaded.isEmpty() && !titlesFile.exists())
        {
            installBundledTitles();

            // The bundled set only reached memory. Push it so a database that started empty ends
            // up holding the shipped titles rather than staying empty until someone edits one.
            saveTitles();
            return;
        }

        titles.clear();
        titles.putAll(loaded);
        FLog.info(String.format("Loaded %d titles from SQL database.", titles.size()));

        reconcileFromJsonIfNewer(repo);
    }

    /**
     * Re-imports {@code titles.json} when it is newer than anything in the table, which is also
     * what seeds a database that has never held a title. Without it an empty table plus an existing
     * snapshot loads as zero titles, and the shipped set is silently lost on the second start.
     */
    private void reconcileFromJsonIfNewer(final TitleRepository repo)
    {
        if (!titlesFile.exists())
        {
            // Nothing to reconcile against, but SQL now has rows that no snapshot covers.
            writes.enqueue(writeJsonAsync());
            return;
        }

        final Map<String, Title> jsonTitles;
        try
        {
            jsonTitles = readJsonTitles();
        }
        catch (IOException ex)
        {
            FLog.warning(String.format("Failed to read %s: %s", TITLES_FILENAME, ex.getMessage()));
            return;
        }

        if (jsonTitles.isEmpty())
        {
            writes.enqueue(writeJsonAsync());
            return;
        }

        final long fileModified = titlesFile.lastModified();

        writes.enqueue(Mono.fromCallable(() ->
                {
                    final Long sqlUpdatedAt = repo.getMaxUpdatedAt();
                    return FUtil.isSnapshotNewer(fileModified, sqlUpdatedAt);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .filter(Boolean::booleanValue)
                .flatMap(ignored ->
                {
                    FLog.info(String.format("%s is newer than the database; rebuilding it from the file's %d title(s).",
                                            TITLES_FILENAME, jsonTitles.size()));
                    return repo.deleteAll()
                               .thenMany(Flux.fromIterable(jsonTitles.values())
                                             .concatMap(repo::save))
                               .then(Mono.<Void>fromRunnable(() -> plugin.dm.sync("TitleManager/applyReconciled",
                                                             () -> applyReconciledTitles(jsonTitles))));
                })
                .onErrorResume(ex ->
                {
                    FLog.warning(String.format("Failed to reconcile %s into the database: %s",
                            TITLES_FILENAME, ex.getMessage()));
                    return Mono.empty();
                })
                .then());
    }

    private void applyReconciledTitles(final Map<String, Title> jsonTitles)
    {
        titles.clear();
        titles.putAll(jsonTitles);
    }

    private void loadFromJsonOrDefaults()
    {
        if (!titlesFile.exists())
        {
            installBundledTitles();
            return;
        }

        loadFromJson();
    }

    /**
     * Writes the bundled {@code titles.json} into the data folder and loads it. Titles are defined
     * entirely by configuration, so a first run copies the shipped file rather than synthesising
     * anything; if the resource is missing the registry stays empty, which grants nothing.
     */
    private void installBundledTitles()
    {
        try
        {
            plugin.saveResource(TITLES_FILENAME, false);
        }
        catch (IllegalArgumentException ex)
        {
            FLog.severe(String.format("No bundled %s to install: %s", TITLES_FILENAME, ex.getMessage()));
            return;
        }

        if (!titlesFile.exists())
        {
            FLog.severe(String.format("Could not install a default %s; no titles will be available.",
                    TITLES_FILENAME));
            return;
        }

        FLog.info(String.format("Installed the default %s.", TITLES_FILENAME));
        loadFromJson();
    }

    private void loadFromJson()
    {
        titles.clear();

        try
        {
            titles.putAll(readJsonTitles());
        }
        catch (IOException ex)
        {
            FLog.severe(String.format("Could not read %s: %s", TITLES_FILENAME, ex.getMessage()));
        }

        FLog.info(String.format("Loaded %d titles.", titles.size()));
    }

    private Map<String, Title> readJsonTitles() throws IOException
    {
        try (FileReader reader = new FileReader(titlesFile))
        {
            return stampIds(JsonUtil.GSON.fromJson(reader, TITLE_MAP_TYPE));
        }
    }

    /**
     * Re-files deserialised titles under their own normalised id.
     * <p>
     * A JSON entry carries its id as the key it sits under rather than as a field, so a freshly
     * deserialised title has none. Stamping it here and re-keying the map on the result keeps the
     * key and the title's own id in agreement even where normalisation rewrites the key.
     */
    private static Map<String, Title> stampIds(final Map<String, Title> loaded)
    {
        if (loaded == null)
            return Map.of();

        final Map<String, Title> keyed = new LinkedHashMap<>();

        loaded.forEach((key, title) ->
        {
            title.assignId(key);
            keyed.put(title.getId(), title);
        });

        return keyed;
    }

    /**
     * Queue a write of every title to SQL, followed by a refresh of the JSON snapshot. Falls back
     * to a JSON-only write when SQL is unavailable. Safe from a command handler: the SQL round
     * trips run off the main thread.
     */
    public void saveTitles()
    {
        if (!usingSql || plugin.dm == null || !plugin.dm.isInitialized())
        {
            writes.enqueue(writeJsonAsync());
            return;
        }

        final TitleRepository repo = plugin.dm.getTitleRepository();
        final List<Title> snapshot = new ArrayList<>(titles.values());

        writes.enqueue(Flux.fromIterable(snapshot)
                .concatMap(title -> repo.save(title)
                        .onErrorResume(ex ->
                        {
                            FLog.severe(String.format("Could not save title %s to SQL: %s",
                                    title.getId(), ex.getMessage()));
                            return Mono.empty();
                        }))
                .then(writeJsonAsync()));
    }

    public void awaitPendingWrites(long timeoutMs)
    {
        writes.await(timeoutMs);
    }

    private Mono<Void> writeJsonAsync()
    {
        final Map<String, Title> snapshot = new LinkedHashMap<>(titles);

        return Mono.<Void>fromRunnable(() -> writeJson(snapshot))
                   .subscribeOn(Schedulers.boundedElastic());
    }

    private void writeJson(final Map<String, Title> snapshot)
    {
        if (titlesFile == null)
        {
            titlesFile = new File(plugin.getDataFolder(), TITLES_FILENAME);
        }

        try (FileWriter writer = new FileWriter(titlesFile))
        {
            JsonUtil.GSON.toJson(snapshot, TITLE_MAP_TYPE, writer);
        }
        catch (IOException ex)
        {
            FLog.severe(String.format("Could not save %s: %s", TITLES_FILENAME, ex.getMessage()));
        }
    }

    /**
     * Every title id currently registered, for tab completion.
     */
    public Set<String> getTitleIds()
    {
        return Collections.unmodifiableSet(titles.keySet());
    }

    /**
     * The ids a player holds that still resolve to a registered title.
     */
    public Set<String> getHeldTitleIds(Player player)
    {
        return getHeldTitles(player).stream()
                                    .map(Title::getId)
                                    .collect(Collectors.toSet());
    }

    /**
     * The title filling a given id, wrapped for callers that would rather not null-check.
     */
    public Optional<Title> find(String id)
    {
        return Optional.ofNullable(getTitle(id));
    }

}

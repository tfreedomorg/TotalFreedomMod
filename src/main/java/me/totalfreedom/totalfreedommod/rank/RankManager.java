package me.totalfreedom.totalfreedommod.rank;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.GameMode;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.CommandMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.admin.Admin;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.dispatch.RemoteDispatchContext;
import me.totalfreedom.totalfreedommod.dispatch.RemoteDispatchSession;
import me.totalfreedom.totalfreedommod.display.Displayable;
import me.totalfreedom.totalfreedommod.player.FPlayer;
import me.totalfreedom.totalfreedommod.sql.PersistenceQueue;
import me.totalfreedom.totalfreedommod.sql.adapter.RankRepository;
import me.totalfreedom.totalfreedommod.util.*;

import com.google.common.collect.Maps;
import com.google.gson.reflect.TypeToken;

public class RankManager extends FreedomService
{
    public static final String RANKS_FILENAME = "ranks.json";

    private static final Type RANK_MAP_TYPE = new TypeToken<Map<String, CustomRank>>() {}.getType();
    private static final long SHUTDOWN_FLUSH_TIMEOUT_MS = 10L * 1000L;

    /**
     * All custom ranks, keyed by ID.
     */
    private final Map<String, CustomRank> customRanks = Maps.newLinkedHashMap();

    /**
     * Resolves senders to ranks and permission nodes to the tier they require. Reads
     * {@link #customRanks} live, so a reload is visible without rebuilding it.
     */
    private final RankRegistry registry;

    /**
     * File for storing custom ranks.
     */
    private File ranksFile = new File(plugin.getDataFolder(), RANKS_FILENAME);

    private final PersistenceQueue writes = new PersistenceQueue("rank");

    private boolean usingSql = false;

    /**
     * Chat input handler for interactive menus.
     */
    private final ChatInputHandler chatInputHandler = new ChatInputHandler();

    public RankManager(TotalFreedomMod plugin)
    {
        super(plugin);
        this.registry = new RankRegistry(plugin, customRanks);
    }

    /**
     * The rank registry, which is the only supported way to ask what rank something holds or what
     * tier a permission node requires.
     */
    public RankRegistry getRegistry()
    {
        return registry;
    }

    private BukkitRunnable persistentMonitorTask = null;

    @Override
    protected void onStart()
    {
        loadRanks();
        plugin.dm.whenReady(this::loadRanks);

        if (plugin.csr != null)
        {
            plugin.csr.load();
        }

        server.getScheduler().runTask(plugin, this::updateAllPlayerTeams);

        // Start persistent monitor if enabled
        if (ConfigEntry.AUTO_OP_ENABLED.getBoolean() && ConfigEntry.AUTO_OP_PERSISTENT_MONITOR.getBoolean())
        {
            startPersistentMonitor();
        }
    }

    @Override
    protected void onStop()
    {
        // Save ranks before shutdown, then let the queue drain: a queued write landing after
        // the flush would restore a stale snapshot.
        saveRanks();
        awaitPendingWrites(SHUTDOWN_FLUSH_TIMEOUT_MS);

        // Stop persistent monitor
        if (persistentMonitorTask != null)
        {
            persistentMonitorTask.cancel();
            persistentMonitorTask = null;
        }

        // Clear chat input handlers
        chatInputHandler.clearAll();
    }

    /**
     * Load custom ranks from SQL, falling back to ranks.json. Never blocks: the SQL read runs
     * off-thread and its result is applied back on the main thread, so this is safe from a
     * command handler as well as from startup.
     */
    public void loadRanks()
    {
        if (plugin.dm != null && plugin.dm.isInitialized())
        {
            loadFromSqlAsync();
            return;
        }

        loadFromJsonOrDefaults();
    }

    private void loadFromSqlAsync()
    {
        final RankRepository repo = plugin.dm.getRankRepository();
        plugin.dm.readAsync("RankManager/loadFromSql", repo.loadAllAsync(),
                loaded -> applyLoadedRanks(repo, loaded),
                () ->
                {
                    usingSql = false;
                    loadFromJsonOrDefaults();
                });
    }

    private void applyLoadedRanks(final RankRepository repo, final Map<String, CustomRank> loaded)
    {
        usingSql = true;

        if (loaded.isEmpty() && !ranksFile.exists())
        {
            installBundledRanks();

            // The bundled set only reached memory. Push it so a database that started empty ends
            // up holding the shipped ranks rather than staying empty until someone edits one.
            saveRanks();
            return;
        }

        customRanks.clear();
        customRanks.putAll(loaded);
        resolveInheritance();
        updateAllPlayerTeams();
        refreshConsoleBindings();
        FLog.info(String.format("Loaded %d custom ranks from SQL database.", customRanks.size()));

        reconcileFromJsonIfNewer(repo);
    }

    /**
     * Re-resolve the console whitelist against the rank set that is now in memory.
     * <p>
     * The first read happens before this service starts, when no custom ranks are loaded,
     * so bindings that name one are skipped with a warning. {@code onStart} rereads it
     * once the JSON ranks are in, but the swap to SQL and the snapshot reconcile both
     * land later and asynchronously, and until now neither told the registry that the
     * ranks had changed.
     */
    private void refreshConsoleBindings()
    {
        if (plugin.csr != null)
            plugin.csr.load();
    }

    private void loadFromJsonOrDefaults()
    {
        if (!ranksFile.exists())
        {
            installBundledRanks();
            return;
        }

        loadFromJson();
    }

    /**
     * Writes the {@code ranks.json} bundled with the plugin into the data folder and loads it.
     * <p>
     * Ranks are defined entirely by that file; nothing in code knows a default tier any more, so a
     * first run copies the shipped definitions rather than synthesising them. If the resource is
     * somehow missing the registry is left empty, which denies every guarded command instead of
     * inventing ranks that the team never approved.
     */
    private void installBundledRanks()
    {
        try
        {
            plugin.saveResource(RANKS_FILENAME, false);
        }
        catch (IllegalArgumentException ex)
        {
            FLog.severe(String.format("No bundled %s to install: %s", RANKS_FILENAME, ex.getMessage()));
            return;
        }

        if (!ranksFile.exists())
        {
            FLog.severe(String.format("Could not install a default %s; all guarded commands will be denied.",
                    RANKS_FILENAME));
            return;
        }

        FLog.info(String.format("Installed the default %s.", RANKS_FILENAME));
        loadFromJson();
    }

    private void loadFromJson()
    {
        customRanks.clear();
        try
        {
            customRanks.putAll(readJsonRanks());
        }
        catch (IOException ex)
        {
            FLog.severe("Could not read " + RANKS_FILENAME + ": " + ex.getMessage());
        }

        resolveInheritance();
        updateAllPlayerTeams();
        FLog.info("Loaded " + customRanks.size() + " custom ranks.");
    }

    private Map<String, CustomRank> readJsonRanks() throws IOException
    {
        try (FileReader reader = new FileReader(ranksFile))
        {
            return stampIds(JsonUtil.GSON.fromJson(reader, RANK_MAP_TYPE));
        }
    }

    /**
     * Re-files deserialised ranks under their own normalised id.
     * <p>
     * A JSON entry carries its id as the key it sits under rather than as a field, so a freshly
     * deserialised rank has none. Stamping it here and re-keying the map on the result keeps the
     * key and the rank's own id in agreement even where normalisation rewrites the key.
     */
    private static Map<String, CustomRank> stampIds(final Map<String, CustomRank> loaded)
    {
        if (loaded == null)
            return Maps.newLinkedHashMap();

        final Map<String, CustomRank> keyed = Maps.newLinkedHashMap();

        loaded.forEach((key, rank) ->
        {
            rank.assignId(key);
            keyed.put(rank.getId(), rank);
        });

        return keyed;
    }

    /**
     * If ranks.json was written more recently than the database's last update, re-import it into
     * SQL. The comparison and the re-import both ride the write queue off the main thread.
     * <p>
     * The import replaces the table rather than merging into it, so a rank deleted from the file is
     * deleted from SQL as well. An empty or unreadable file is ignored.
     */
    private void reconcileFromJsonIfNewer(final RankRepository repo)
    {
        if (!ranksFile.exists())
        {
            // Nothing to reconcile against, but SQL now has rows that no snapshot covers.
            writes.enqueue(writeJsonAsync());
            return;
        }

        final Map<String, CustomRank> jsonRanks;
        try
        {
            jsonRanks = readJsonRanks();
        }
        catch (IOException ex)
        {
            FLog.warning(String.format("Failed to read %s: %s", RANKS_FILENAME, ex.getMessage()));
            return;
        }

        if (jsonRanks.isEmpty())
        {
            writes.enqueue(writeJsonAsync());
            return;
        }

        final long fileModified = ranksFile.lastModified();

        writes.enqueue(Mono.fromCallable(() ->
              {
                  final Long sqlUpdatedAt = repo.getMaxUpdatedAt();
                  return FUtil.isSnapshotNewer(fileModified, sqlUpdatedAt);
              })
              .subscribeOn(Schedulers.boundedElastic())
              .filter(Boolean::booleanValue)
              .flatMap(ignored ->
              {
                FLog.info(String.format("%s is newer than the database; rebuilding it from the file's %d rank(s).",
                                        RANKS_FILENAME, jsonRanks.size()));
                return Flux.fromIterable(jsonRanks.values())
                           .concatMap(repo::save)
                           .then(repo.loadAllAsync())
                           .flatMapMany(existing -> Flux.fromIterable(existing.keySet()))
                           .filter(id -> !jsonRanks.containsKey(id))
                           .concatMap(repo::deleteAsync)
                           .then(Mono.<Void>fromRunnable(() -> plugin.dm.sync("RankManager/applyReconciled",
                                                         () -> applyReconciledRanks(jsonRanks))));
              })
              .onErrorResume(ex ->
              {
                  FLog.warning(String.format("Failed to reconcile %s into the database: %s",
                                             RANKS_FILENAME, ex.getMessage()));
                  return Mono.empty();
              })
              .then());
    }

    private void applyReconciledRanks(final Map<String, CustomRank> jsonRanks)
    {
        customRanks.clear();
        customRanks.putAll(jsonRanks);
        resolveInheritance();
        updateAllPlayerTeams();
        refreshConsoleBindings();
    }


    /**
     * Queue a write of every custom rank to SQL, followed by a refresh of the ranks.json
     * snapshot. Falls back to a JSON-only write when SQL is unavailable. Safe from a command
     * handler: the SQL round trips run off the main thread.
     */
    public void saveRanks()
    {
        if (!usingSql || plugin.dm == null || !plugin.dm.isInitialized())
        {
            writes.enqueue(writeJsonAsync());
            return;
        }

        final RankRepository repo = plugin.dm.getRankRepository();
        final List<CustomRank> snapshot = new ArrayList<>(customRanks.values());

        writes.enqueue(Flux.fromIterable(snapshot)
              .concatMap(rank -> repo.save(rank)
                                     .onErrorResume(ex ->
                                     {
                                         FLog.severe(String.format("Could not save rank %s to SQL: %s",
                                                                   rank.getId(), 
                                                                   ex.getMessage()));
                                         return Mono.empty();
                                     }))
              .then(writeJsonAsync()));
    }

    /**
     * Wait for queued rank writes to land, up to {@code timeoutMs}.
     */
    public void awaitPendingWrites(long timeoutMs)
    {
        writes.await(timeoutMs);
    }

    private Mono<Void> writeJsonAsync()
    {
        final Map<String, CustomRank> snapshot = new LinkedHashMap<>(customRanks);
        return Mono.<Void>fromRunnable(() -> writeJson(snapshot))
                   .subscribeOn(Schedulers.boundedElastic());
    }

    private void writeJson(final Map<String, CustomRank> snapshot)
    {
        try (FileWriter writer = new FileWriter(ranksFile))
        {
            JsonUtil.GSON.toJson(snapshot, RANK_MAP_TYPE, writer);
        }
        catch (IOException ex)
        {
            FLog.severe(String.format("Could not save %s: %s", RANKS_FILENAME, ex.getMessage()));
        }
    }

    /**
     * Flattens each rank's inherited permissions, then rebuilds the registry's permission index so
     * that the tier a node requires is recomputed from what the ranks now grant.
     */
    private void resolveInheritance()
    {
        customRanks.values()
                   .forEach(rank -> rank.setResolvedPermissions(collectPermissions(rank, new HashSet<>())));

        registry.reindex();
    }

    private Set<String> collectPermissions(CustomRank rank, Set<String> visited)
    {
        if (rank == null) 
            return Set.of();

        if (visited.contains(rank.getId()))
        {
            FLog.warning("Circular inheritance detected for rank: " + rank.getId());
            return Set.of();
        }
        visited.add(rank.getId());

        Set<String> perms = new HashSet<>(rank.getPermissions());

        if (rank.getInheritFrom() != null)
        {
            CustomRank parent = customRanks.get(rank.getInheritFrom().toLowerCase());
            if (parent == null)
                FLog.warning("Rank '" + rank.getId() + "' inherits from non-existent rank: " + rank.getInheritFrom());
            else
                perms.addAll(collectPermissions(parent, visited));
        }

        return perms;
    }

    public CustomRank getCustomRank(String id)
    {
        if (id == null)
            return null;

        return customRanks.get(id.toLowerCase());
    }


    private CustomRank getAssignedAdminRank(Player player)
    {
        if (plugin.al.isAdminImpostor(player))
            return null;

        final Admin admin = plugin.al.getAdmin(player);

        if (admin == null || !admin.isActive())
            return null;

        return getCustomRank(admin.getRankId());
    }

    public void updatePlayerTeam(Player player)
    {
        ScoreboardManager manager = server.getScoreboardManager();

        if (manager == null)
            return;

        final Scoreboard scoreboard = manager.getMainScoreboard();
        final Team currentTeam = scoreboard.getEntryTeam(player.getName());
        final CustomRank rank = getAssignedAdminRank(player);

        final boolean admin = rank != null && rank.isAdmin();
        final String teamName = admin ? createTeamName(rank) : DEFAULT_TEAM_NAME;

        if (currentTeam != null && !currentTeam.getName().equals(teamName))
            currentTeam.removeEntry(player.getName());

        Team team = scoreboard.getTeam(teamName);

        if (team == null)
            team = scoreboard.registerNewTeam(teamName);

        // this npe warning can be ignored since boolean admin already validates that rank won't be null if true
        team.color(admin ? rank.getColor() : NamedTextColor.WHITE);
        team.prefix(Component.empty());
        team.addEntry(player.getName());
    }

    private static final String DEFAULT_TEAM_NAME = "zz_default";

    private String createTeamName(CustomRank rank)
    {
        final int level = Math.max(0, Math.min(99, rank.getLevel()));
        String name = String.format("%02d_%s", 99 - level,
                rank.getId().replaceAll("[^A-Za-z0-9_\\-]", "_"));

        if (name.length() > 16)
            name = name.substring(0, 16);

        return name;
    }

    public void updateAllPlayerTeams()
    {
        for (Player player : server.getOnlinePlayers())
            updatePlayerTeam(player);
    }

    /**
     * Get all custom ranks.
     */
    public Map<String, CustomRank> getCustomRanks()
    {
        return customRanks;
    }

    /**
     * Get custom ranks sorted by level.
     */
    public List<CustomRank> getCustomRanksSorted()
    {
        List<CustomRank> sorted = new ArrayList<>(customRanks.values());
        sorted.sort(Comparator.comparingInt(CustomRank::getLevel));
        return sorted;
    }

    public void setCustomRank(CustomRank rank)
    {
        customRanks.put(rank.getId(), rank);
        resolveInheritance();
        saveRanks();
        updateAllPlayerTeams();
    }

    /**
     * Remove a custom rank.
     */
    public boolean removeCustomRank(String id)
    {
        if (id == null)
            return false;

        final CustomRank removed = customRanks.remove(CustomRank.normalizeId(id));
        if (removed == null)
            return false;

        if (usingSql && plugin.dm != null && plugin.dm.isInitialized())
        {
            writes.enqueue(plugin.dm.getRankRepository()
                  .deleteAsync(removed.getId())
                  .onErrorResume(ex ->
                  {
                      FLog.severe(String.format("Could not delete rank %s from SQL: %s",
                                                removed.getId(), 
                                                ex.getMessage()));
                      return Mono.empty();
                  })
                  .then());
        }

        resolveInheritance();
        saveRanks();
        updateAllPlayerTeams();

        return true;
    }

    /**
     * Check if a custom rank exists.
     */
    public boolean hasCustomRank(String id)
    {
        return customRanks.containsKey(id.toLowerCase());
    }

    /**
     * Whether {@code sender} may exercise an internal TFM permission node.
     * <p>
     * These are TFM's own nodes and are never registered with Bukkit 
     * because if we registered with Bukkit then OPs would have these nodes too.
     *
     * @param sender     the command sender
     * @param permission the internal node, for example {@code tfm.admin.ban}
     */
    public boolean hasPermission(CommandSender sender, String permission)
    {
        return registry.satisfies(sender, permission);
    }

    /**
     * Check if sender has permission to manage ranks.
     */
    public boolean canManageRanks(CommandSender sender)
    {
        return hasPermission(sender, "tfm.manage.ranks");
    }

    /**
     * Get the chat input handler for interactive menus.
     */
    public ChatInputHandler getChatInputHandler()
    {
        return chatInputHandler;
    }

    /**
     * Inner class that handles chat input for interactive configuration menus.
     * Players can be registered to have their next chat message captured.
     */
    public class ChatInputHandler
    {
        /**
         * Map of player UUIDs to their pending input handlers.
         */
        private final Map<UUID, PendingInput> pendingInputs = new ConcurrentHashMap<>();

        /**
         * Register a player to capture their next chat message.
         *
         * @param player The player
         * @param prompt The prompt to show the player
         * @param callback The callback to invoke with the input
         * @param timeoutSeconds How long to wait before expiring (0 = no timeout)
         */
        public void awaitInput(Player player, Component prompt, Consumer<String> callback, int timeoutSeconds)
        {
            UUID uuid = player.getUniqueId();

            cancelInput(player);

            player.sendMessage(Component.empty());
            player.sendMessage(prompt);
            player.sendMessage(Component.text("Type your response in chat, or type 'cancel' to abort.")
                    .color(NamedTextColor.GRAY).decorate(TextDecoration.ITALIC));

            PendingInput pending = new PendingInput(callback, System.currentTimeMillis());
            pendingInputs.put(uuid, pending);

            if (timeoutSeconds > 0)
                new BukkitRunnable()
                {
                    @Override
                    public void run()
                    {
                        PendingInput current = pendingInputs.get(uuid);
                        if (current == pending)
                        {
                            pendingInputs.remove(uuid);
                            Player p = server.getPlayer(uuid);

                            if (p != null && p.isOnline())
                                p.sendMessage(Component.text("Input timed out.").color(NamedTextColor.RED));
                        }
                    }
                }.runTaskLater(plugin, timeoutSeconds * 20L);
        }

        /**
         * Cancel pending input for a player.
         */
        public void cancelInput(Player player)
        {
            pendingInputs.remove(player.getUniqueId());
        }

        /**
         * Check if a player has pending input.
         */
        public boolean hasPendingInput(Player player)
        {
            return pendingInputs.containsKey(player.getUniqueId());
        }

        /**
         * Process a chat message from a player.
         *
         * @param player The player
         * @param message The chat message
         * @return true if the message was consumed (pending input), false otherwise
         */
        public boolean processChat(Player player, String message)
        {
            UUID uuid = player.getUniqueId();
            PendingInput pending = pendingInputs.remove(uuid);

            if (pending == null)
                return false;

            // Check for cancel
            if (message.equalsIgnoreCase("cancel"))
            {
                player.sendMessage(Component.text("Input cancelled.").color(NamedTextColor.YELLOW));
                return true;
            }

            try
            {
                pending.callback().accept(message);
            }
            catch (Exception ex)
            {
                player.sendMessage(Component.text("Error processing input: " + ex.getMessage()).color(NamedTextColor.RED));
                FLog.warning("Error in chat input callback: " + ex.getMessage());
            }

            return true;
        }

        /**
         * Clear all pending inputs.
         */
        public void clearAll()
        {
            pendingInputs.clear();
        }

        /**
         * Inner class representing pending input.
         */
        private record PendingInput(Consumer<String> callback, long timestamp)
        {
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncChatEvent event)
    {
        Player player = event.getPlayer();

        if (chatInputHandler.hasPendingInput(player))
        {
            final String message = PlainTextComponentSerializer.plainText().serialize(event.message());

            FTask.run("chatInputHandler#processChat", () -> chatInputHandler.processChat(player, message));

            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        // Clean up pending inputs when player leaves
        chatInputHandler.cancelInput(event.getPlayer());

        ScoreboardManager manager = server.getScoreboardManager();

        if (manager != null)
        {
            Team team = manager.getMainScoreboard().getEntryTeam(event.getPlayer().getName());

            if (team != null)
                team.removeEntry(event.getPlayer().getName());
        }
    }

    /**
     * Build the main rank configuration menu.
     */
    public Component buildMainMenu()
    {
        Component builder = Component.empty();

        builder.append(Component.text("\n"));
        builder.append(Component.text("═══════════════════════════════════════").color(NamedTextColor.GOLD));
        builder.append(Component.text("\n"));
        builder.append(Component.text("         RANK CONFIGURATION").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        builder.append(Component.text("\n"));
        builder.append(Component.text("═══════════════════════════════════════").color(NamedTextColor.GOLD));
        builder.append(Component.text("\n\n"));

        // List ranks with edit buttons
        builder.append(Component.text("Ranks (sorted by level):").color(NamedTextColor.YELLOW));
        builder.append(Component.text("\n"));

        for (CustomRank rank : getCustomRanksSorted())
        {
            builder.append(Component.text("  • ").color(NamedTextColor.GRAY));
            builder.append(rank.getColoredTag());
            builder.append(Component.text(" ").append(rank.getColoredName()));
            builder.append(Component.text(" (Level " + rank.getLevel() + ")").color(NamedTextColor.DARK_GRAY));
            builder.append(Component.text(" "));

            // Edit button
            builder.append(Component.text("[Edit]")
                    .color(NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand("/rankconfig edit " + rank.getId()))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to edit " + rank.getName()))));

            builder.append(Component.text(" "));

            // Delete button
            builder.append(Component.text("[Delete]")
                    .color(NamedTextColor.RED)
                    .clickEvent(ClickEvent.runCommand("/rankconfig delete " + rank.getId()))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to delete " + rank.getName()))));

            builder.append(Component.text("\n"));
        }

        builder.append(Component.text("\n"));

        // Actions
        builder.append(Component.text("[+ Create New Rank]")
                .color(NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/rankconfig create"))
                .hoverEvent(HoverEvent.showText(Component.text("Click to create a new rank"))));

        builder.append(Component.text("  "));

        builder.append(Component.text("[Reload]")
                .color(NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.runCommand("/rankconfig reload"))
                .hoverEvent(HoverEvent.showText(Component.text("Reload ranks from file"))));

        builder.append(Component.text("\n"));
        builder.append(Component.text("═══════════════════════════════════════").color(NamedTextColor.GOLD));
        builder.append(Component.text("\n"));

        return builder;
    }

    /**
     * Build the rank edit menu.
     */
    public Component buildEditMenu(CustomRank rank)
    {
        Component builder = Component.empty();

        builder.append(Component.text("\n"));
        builder.append(Component.text("═══════════════════════════════════════").color(NamedTextColor.AQUA));
        builder.append(Component.text("\n"));
        builder.append(Component.text("  Editing: ").color(NamedTextColor.WHITE));
        builder.append(rank.getColoredTag());
        builder.append(Component.text(" ").append(rank.getColoredName()));
        builder.append(Component.text("\n"));
        builder.append(Component.text("═══════════════════════════════════════").color(NamedTextColor.AQUA));
        builder.append(Component.text("\n\n"));

        builder.append(buildEditableProperty("Name", rank.getName(), "/rankconfig set " + rank.getId() + " name"));
        builder.append(buildEditableProperty("Abbreviation", rank.getAbbreviation(), "/rankconfig set " + rank.getId() + " abbreviation"));
        builder.append(buildEditableProperty("Prefix", rank.getPrefix() != null ? rank.getPrefix() : "(none)", "/rankconfig set " + rank.getId() + " prefix"));
        builder.append(buildEditableProperty("Level", String.valueOf(rank.getLevel()), "/rankconfig set " + rank.getId() + " level"));
        builder.append(buildEditableProperty("Color", rank.getColor().toString(), "/rankconfig set " + rank.getId() + " color"));
        builder.append(buildEditableProperty("Determiner", rank.getDeterminer(), "/rankconfig set " + rank.getId() + " determiner"));
        builder.append(buildEditableProperty("Is Admin", String.valueOf(rank.isAdmin()), "/rankconfig set " + rank.getId() + " admin"));
        builder.append(buildEditableProperty("Inherit From", rank.getInheritFrom() != null ? rank.getInheritFrom() : "(none)", "/rankconfig set " + rank.getId() + " inherit"));

        builder.append(Component.text("\n"));

        // Permissions section
        builder.append(Component.text("Permissions:").color(NamedTextColor.YELLOW));
        builder.append(Component.text(" "));
        builder.append(Component.text("[+ Add]")
                .color(NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/rankconfig set " + rank.getId() + " addperm"))
                .hoverEvent(HoverEvent.showText(Component.text("Add a permission"))));
        builder.append(Component.text("\n"));

        if (rank.getPermissions().isEmpty())
        {
            builder.append(Component.text("  (none)").color(NamedTextColor.DARK_GRAY).decorate(TextDecoration.ITALIC));
            builder.append(Component.text("\n"));
        }
        else
        {
            for (String perm : rank.getPermissions())
            {
                builder.append(Component.text("  • ").color(NamedTextColor.GRAY));
                builder.append(Component.text(perm).color(NamedTextColor.WHITE));
                builder.append(Component.text(" "));
                builder.append(Component.text("[X]")
                        .color(NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/rankconfig set " + rank.getId() + " remperm " + perm))
                        .hoverEvent(HoverEvent.showText(Component.text("Remove this permission"))));
                builder.append(Component.text("\n"));
            }
        }

        builder.append(Component.text("\n"));
        builder.append(Component.text("[← Back to List]")
                .color(NamedTextColor.GRAY)
                .clickEvent(ClickEvent.runCommand("/rankconfig"))
                .hoverEvent(HoverEvent.showText(Component.text("Return to rank list"))));
        builder.append(Component.text("  "));
        builder.append(Component.text("[Save & Close]")
                .color(NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/rankconfig save"))
                .hoverEvent(HoverEvent.showText(Component.text("Save changes"))));
        builder.append(Component.text("\n"));
        builder.append(Component.text("═══════════════════════════════════════").color(NamedTextColor.AQUA));
        builder.append(Component.text("\n"));

        return builder;
    }

    /**
     * Build an editable property line.
     */
    private Component buildEditableProperty(String label, String value, String command)
    {
        return Component.text("  " + label + ": ").color(NamedTextColor.GRAY)
                .append(FUtil.colorizeWithLinks(value, NamedTextColor.WHITE))
                .append(Component.text(" "))
                .append(Component.text("[Edit]")
                        .color(NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand(command))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to change " + label.toLowerCase()))))
                .append(Component.text("\n"));
    }

    private void startPersistentMonitor()
    {
        final int interval = ConfigEntry.AUTO_OP_MONITOR_INTERVAL.getInteger();
        if (interval <= 0)
        {
            return;
        }

        persistentMonitorTask = new BukkitRunnable()
        {
            @Override
            public void run()
            {
                if (!ConfigEntry.AUTO_OP_ENABLED.getBoolean())
                {
                    cancel();
                    return;
                }

                FTask.run("RankManager/autoOpMonitor", () ->
                {
                    for (Player player : server.getOnlinePlayers())
                    {
                        // Skip admins and players who should not be OP
                        if (plugin.al.isAdmin(player) || plugin.al.isAdminImpostor(player))
                            continue;

                        // Re-OP players who lost OP status
                        if (!player.isOp())
                            ensureOp(player);
                    }
                });
            }
        };
        persistentMonitorTask.runTaskTimer(plugin, interval, interval);
    }

    /**
     * Ensures a player has OP status and aggressively refreshes permissions.
     * Used for auto-OP system to maintain OP status reliably.
     */
    private void ensureOp(Player player)
    {
        if (player == null || !player.isOnline())
            return;


        // Skip admins and impostors
        if (plugin.al.isAdmin(player) || plugin.al.isAdminImpostor(player))
            return;

        // Only ensure OP if auto-OP is enabled
        if (!ConfigEntry.AUTO_OP_ENABLED.getBoolean())
            return;

        // Set OP if not already set
        if (!player.isOp())
            player.setOp(true);

        // Aggressively refresh permissions immediately
        try
        {
            player.recalculatePermissions();
        }
        catch (Exception ex)
        {
            // Ignore - some plugins may throw exceptions
        }

        // Schedule multiple delayed recalculations to catch plugins that cache late
        // This ensures WorldEdit, Essentials, etc. pick up the OP status
        for (long delay : new long[]{2L, 5L, 10L, 20L}) // 100ms, 250ms, 500ms, 1s
            new BukkitRunnable()
            {
                @Override
                public void run()
                {
                    if (player.isOnline() && !plugin.al.isAdmin(player) && !plugin.al.isAdminImpostor(player))
                    {
                        try
                        {
                            player.recalculatePermissions();
                        }
                        catch (Exception ex)
                        {
                            // Ignore
                        }
                    }
                }
            }.runTaskLater(plugin, delay);
    }

    /**
     * The rank whose name, colour and tag should be shown for {@code sender}.
     * <p>
     * Display is not the same question as permission. A few identities are recognised here purely
     * so they read correctly in chat, and none of them grants anything: the impostor marker, the
     * hardcoded developer list, and the owners named in config. Each is honoured only when a rank
     * of that name actually exists in the registry, so a staff member who removes one simply gets the
     * sender's real rank instead.
     */
    public Displayable getDisplay(CommandSender sender)
    {
        if (!(sender instanceof Player player))
        {
            return registry.forSender(sender).orElse(null);
        }

        if (plugin.al.isAdminImpostor(player))
        {
            return registry.byRole(RankRole.IMPOSTOR).orElse(null);
        }

        // A held title outranks the player's rank for display purposes: a title is the identity
        // people recognise ("Master Builder"), while the rank underneath is only what they may do.
        final Displayable title = plugin.tm == null ? null : plugin.tm.getDisplayTitle(player);

        return title != null ? title : registry.forSender(player).orElse(null);
    }

    /**
     * The login line announcing an impostor, falling back to plain wording when the registry has no
     * impostor rank to style it with.
     */
    private Component impostorLoginMessage()
    {
        return registry.byRole(RankRole.IMPOSTOR)
                       .map(CustomRank::getColoredLoginMessage)
                       .orElseGet(() -> Component.text("an Impostor").color(NamedTextColor.YELLOW));
    }

    /**
     * Resolves the rank a sender actually acts at.
     * <p>
     * Delegates to {@link RankRegistry}, which is the single place that knows how a sender earns a
     * rank: an identified SSH or Discord session resolves to that admin's own profile, while a host
     * channel carries no identity and resolves to its {@code host_senders:} binding.
     *
     * @return the sender's effective rank, or {@code null} when none could be resolved
     */
    public CustomRank getEffectiveRank(CommandSender sender)
    {
        return registry.forSender(sender).orElse(null);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoinAutoOp(PlayerJoinEvent event)
    {
        final Player player = event.getPlayer();
        final boolean isAdmin = plugin.al.isAdmin(player);

        // Skip admins and impostors
        if (isAdmin || plugin.al.isAdminImpostor(player))
        {
            return;
        }

        // Verify and ensure OP status with aggressive permission refresh
        // This runs at LOWEST priority to execute before other plugins
        if (ConfigEntry.AUTO_OP_ENABLED.getBoolean())
        {
            ensureOp(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        final Player player = event.getPlayer();
        //plugin.pl.getData(player);
        final FPlayer fPlayer = plugin.pl.getPlayer(player);

        // Unban admins
        boolean isAdmin = plugin.al.isAdmin(player);
        if (isAdmin)
        {
            // Verify strict IP match
            if (!plugin.al.isIdentityMatched(player))
            {
                Component warningMsg = Component.text("Warning: " + player.getName() + " is an admin, but is using an account not registered to one of their ip-list.")
                        .color(NamedTextColor.RED);
                FUtil.bcastMsg(warningMsg);
                fPlayer.setSuperadminIdVerified(false);
            }
            else
            {
                fPlayer.setSuperadminIdVerified(true);
                plugin.al.updateLastLogin(player);
            }
        }

        updatePlayerTeam(player);

        // Handle impostors
        if (plugin.al.isAdminImpostor(player))
        {
            Component impostorMsg = Component.text(player.getName() + " is ")
                    .color(NamedTextColor.AQUA)
                    .append(impostorLoginMessage());
            FUtil.bcastMsg(impostorMsg);
            if (plugin.db != null)
            {
                plugin.db.relayLoginMessage(impostorMsg);
            }

            Component warningMsg = Component.text("Warning: " + player.getName() + " has been flagged as an impostor and has been frozen!")
                    .color(NamedTextColor.RED);
            FUtil.bcastMsg(warningMsg);

            player.getInventory().clear();
            player.setOp(false);
            player.setGameMode(GameMode.SURVIVAL);
            plugin.pl.getPlayer(player).getFreezeData().setFrozen(true);

            Component playerMsg = Component.text("You are marked as an impostor, please verify yourself!")
                    .color(NamedTextColor.RED);
            player.sendMessage(playerMsg);
            return;
        }

        // Announce admins, and anyone holding a title worth announcing. The hardcoded developer
        // list used to stand in for the latter; a title says the same thing as data instead.
        if (isAdmin || (plugin.tm != null && plugin.tm.getDisplayTitle(player) != null))
        {
            final Displayable display = getDisplay(player);
            Component loginMsg = formatLoginMessage(player);
            FUtil.bcastMsg(loginMsg);
            if (plugin.db != null)
            {
                plugin.db.relayLoginMessage(loginMsg);
            }

            // Skip rank tag when the player has a saved custom tag.
            final String savedTag = plugin.pl.getData(player).getSavedTag();
            if (savedTag == null)
            {
                String tagLegacy = AdventureUtil.componentToLegacySection(display.getColoredTag());
                plugin.pl.getPlayer(player).setTag(tagLegacy);
            }

        }
    }

    public Component formatLoginMessage(Player player)
    {
        final Displayable display = getDisplay(player);
        final boolean isAdmin = plugin.al.isAdmin(player);
        Component loginMsg = Component.text(player.getName() + " is ")
                .color(NamedTextColor.AQUA)
                .append(display.getColoredLoginMessage());

        if (isAdmin)
        {
            Admin admin = plugin.al.getAdmin(player);
            if (admin.hasLoginMessage())
            {
                // Temporary measure to convert old tags to the preferred MiniMessage system and update database
                String loginMessage = admin.getLoginMessage();
                if (loginMessage.contains("%name%") || loginMessage.contains("%rank%") || loginMessage.contains("%coloredrank%"))
                {
                    loginMessage = loginMessage
                            .replace("%name%", "<name>")
                            .replace("%rank%", "<rank>")
                            .replace("%coloredrank%", "<colored_rank>");

                    admin.setLoginMessage(loginMessage);
                    plugin.al.saveAsync();
                    plugin.al.updateTables();
                }

                loginMsg = AdventureUtil.addLinks(
                        AdventureUtil.formatWithPlaceholders(
                                loginMessage,
                                Placeholder.unparsed("name", player.getName()),
                                Placeholder.unparsed("rank", display.getName()),
                                Placeholder.component("colored_rank", display.getColoredName())
                        ));
            }
        }

        return loginMsg;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event)
    {
        final Player player = event.getPlayer();
        if (ConfigEntry.AUTO_OP_ENABLED.getBoolean() && !plugin.al.isAdmin(player) && !plugin.al.isAdminImpostor(player))
        {
            // Re-verify OP after respawn
            ensureOp(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event)
    {
        final Player player = event.getPlayer();
        if (ConfigEntry.AUTO_OP_ENABLED.getBoolean() && !plugin.al.isAdmin(player) && !plugin.al.isAdminImpostor(player))
        {
            // Re-verify OP after world change
            ensureOp(player);
        }
    }
}
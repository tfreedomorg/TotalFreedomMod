package me.totalfreedom.totalfreedommod.sql.adapter.generic;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;

import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.player.LegacyAdmin;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.player.SpyMode;
import me.totalfreedom.totalfreedommod.sql.StatementHandler;
import me.totalfreedom.totalfreedommod.sql.adapter.DatabaseAdapter;
import me.totalfreedom.api.sql.adapter.PlayerRepository;
import me.totalfreedom.totalfreedommod.util.AdventureUtil;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;

/**
 * All dialect differences are resolved through the {@link DatabaseAdapter} passed in.
 */
public class GenericPlayerRepository implements PlayerRepository
{
    private final StatementHandler statementHandler;
    private final DatabaseAdapter adapter;

    private final String tblPlayers;
    private final String colUuid;
    private final String colUsername;
    private final String colFirstJoin;
    private final String colLastJoin;
    private final String colPotionSpyMode;
    private final String colCommandSpyMode;
    private final String colCommandSpyAlertSound;
    private final String colCommandSpyAlertRegex;
    private final String colSignSpyMode;
    private final String colBookSpyMode;
    private final String colMuted;
    private final String colFrozen;
    private final String colCommandsBlocked;
    private final String colJoinLeaveMessages;
    private final String colStrikes;
    private final String colSavedTag;
    private final String colTitles;
    private final String colNickname;
    private final String colIp;
    private final String colRankId;
    private final String colAdminActive;
    private final String colLoginMessage;
    private final String colDiscordId;
    private final String colDiscordIdentifier;
    private final String colTrustedIps;
    private final String colId;
    private final String colUpdatedAt;

    /**
     * The columns {@code player_data} shares with the legacy {@code players} table, which had them
     * keyed by username.
     */
    private final List<String> legacyColumns;

    /**
     * Every stored column except the key, in the order {@link #dataValues} produces them.
     */
    private final List<String> dataColumns;
    private final String selectColumns;

    public GenericPlayerRepository(StatementHandler statementHandler, DatabaseAdapter adapter)
    {
        this.statementHandler = statementHandler;
        this.adapter = adapter;

        this.tblPlayers = adapter.quoteIdentifier("player_data");
        this.colUuid = adapter.quoteIdentifier("uuid");
        this.colUsername = adapter.quoteIdentifier("username");
        this.colFirstJoin = adapter.quoteIdentifier("first_join_unix");
        this.colLastJoin = adapter.quoteIdentifier("last_join_unix");
        this.colPotionSpyMode = adapter.quoteIdentifier("potion_spy_mode");
        this.colCommandSpyMode = adapter.quoteIdentifier("command_spy_mode");
        this.colCommandSpyAlertSound = adapter.quoteIdentifier("command_spy_alert_sound");
        this.colCommandSpyAlertRegex = adapter.quoteIdentifier("command_spy_alert_regex");
        this.colSignSpyMode = adapter.quoteIdentifier("sign_spy_mode");
        this.colBookSpyMode = adapter.quoteIdentifier("book_spy_mode");
        this.colMuted = adapter.quoteIdentifier("muted");
        this.colFrozen = adapter.quoteIdentifier("frozen");
        this.colCommandsBlocked = adapter.quoteIdentifier("commands_blocked");
        this.colJoinLeaveMessages = adapter.quoteIdentifier("join_leave_messages");
        this.colStrikes = adapter.quoteIdentifier("strikes");
        this.colSavedTag = adapter.quoteIdentifier("saved_tag");
        this.colTitles = adapter.quoteIdentifier("titles");
        this.colNickname = adapter.quoteIdentifier("nickname");
        this.colIp = adapter.quoteIdentifier("ip");
        this.colRankId = adapter.quoteIdentifier("rank_id");
        this.colAdminActive = adapter.quoteIdentifier("admin_active");
        this.colLoginMessage = adapter.quoteIdentifier("login_message");
        this.colDiscordId = adapter.quoteIdentifier("discord_id");
        this.colDiscordIdentifier = adapter.quoteIdentifier("discord_identifier");
        this.colTrustedIps = adapter.quoteIdentifier("trusted_ips");
        this.colId = adapter.quoteIdentifier("id");
        this.colUpdatedAt = adapter.quoteIdentifier("updated_at");

        this.legacyColumns = List.of(colUsername, colFirstJoin, colLastJoin, colPotionSpyMode, colCommandSpyMode,
                colCommandSpyAlertSound, colCommandSpyAlertRegex, colSignSpyMode, colBookSpyMode,
                colMuted, colFrozen, colCommandsBlocked, colJoinLeaveMessages, colStrikes, colSavedTag,
                colTitles, colNickname);
        this.dataColumns = Stream.concat(legacyColumns.stream(),
                                         Stream.of(colIp, colRankId, colAdminActive, colLoginMessage, colDiscordId, colDiscordIdentifier,
                                         colTrustedIps))
                                 .toList();
        this.selectColumns = String.format("%s, %s", colUuid, String.join(", ", dataColumns));
    }

    @Override
    public void insert(PlayerData data) throws SQLException
    {
        final String placeholders = String.join(", ", Collections.nCopies(dataColumns.size() + 1, "?"));
        final String sql = String.format("INSERT INTO %s (%s, %s) VALUES (%s, %s)",
                tblPlayers, selectColumns, colUpdatedAt, placeholders, adapter.currentTimestamp());

        final List<Object> values = new ArrayList<>();
        values.add(data.getUuid().toString());
        values.addAll(dataValues(data));
        statementHandler.executeUpdate(sql, values.toArray());
    }

    @Override
    public Map<UUID, PlayerData> loadAll() throws SQLException
    {
        return findMany(String.format("SELECT %s FROM %s", selectColumns, tblPlayers))
                .stream()
                .collect(Collectors.toMap(PlayerData::getUuid, Function.identity()));
    }

    @Override
    public List<PlayerData> findStaff() throws SQLException
    {
        return findMany(String.format("SELECT %s FROM %s WHERE %s IS NOT NULL", selectColumns, tblPlayers, colRankId));
    }

    @Override
    public Optional<PlayerData> findByUuid(UUID uuid) throws SQLException
    {
        final String sql = String.format("SELECT %s FROM %s WHERE %s = ?", selectColumns, tblPlayers, colUuid);
        return findOne(sql, uuid.toString());
    }

    @Override
    public Optional<PlayerData> findByUsername(String username) throws SQLException
    {
        final String sql = String.format("SELECT %s FROM %s WHERE %s ORDER BY %s DESC LIMIT 1", selectColumns, tblPlayers,
                adapter.caseInsensitiveEquals(colUsername, "?"), colLastJoin);
        return findOne(sql, username);
    }

    @Override
    public boolean exists(UUID uuid) throws SQLException
    {
        final String sql = String.format("SELECT COUNT(*) FROM %s WHERE %s = ?", tblPlayers, colUuid);
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, uuid.toString());
             ResultSet rs = stmt.executeQuery())
        {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    @Override
    public boolean update(PlayerData data) throws SQLException
    {
        final String assignments = dataColumns.stream()
                                              .map(column -> String.format("%s = ?", column))
                                              .collect(Collectors.joining(", "));
        final String sql = String.format("UPDATE %s SET %s, %s = %s WHERE %s = ?",
                tblPlayers, assignments, colUpdatedAt, adapter.currentTimestamp(), colUuid);

        final List<Object> values = new ArrayList<>(dataValues(data));
        values.add(data.getUuid().toString());

        return statementHandler.executeUpdate(sql, values.toArray()) > 0;
    }

    @Override
    public void saveOrUpdate(PlayerData data) throws SQLException
    {
        if (exists(data.getUuid()))
            update(data);
        else
            insert(data);
    }

    @Override
    public boolean delete(UUID uuid) throws SQLException
    {
        return statementHandler.executeUpdate(String.format("DELETE FROM %s WHERE %s = ?", tblPlayers, colUuid), uuid.toString()) > 0;
    }

    @Override
    public void deleteAllSync() throws SQLException
    {
        statementHandler.executeUpdate(String.format("DELETE FROM %s", tblPlayers));
    }

    @Override
    public Long getUpdatedAt(UUID uuid) throws SQLException
    {
        final String sql = String.format("SELECT %s FROM %s WHERE %s = ?", colUpdatedAt, tblPlayers, colUuid);
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, uuid.toString());
             ResultSet rs = stmt.executeQuery())
        {
            if (rs.next())
            {
                return adapter.readTimestamp(rs, 1);
            }
        }
        return null;
    }

    @Override
    public Mono<Map<UUID, PlayerData>> loadAllAsync()
    {
        return statementHandler.supplyMono(this::loadAll);
    }

    @Override
    public Mono<List<PlayerData>> findStaffAsync()
    {
        return statementHandler.supplyMono(this::findStaff);
    }

    @Override
    public Mono<Void> save(PlayerData data)
    {
        return statementHandler.runMono(() -> saveOrUpdate(data));
    }

    @Override
    public Mono<Boolean> deleteAsync(UUID uuid)
    {
        return statementHandler.supplyMono(() -> delete(uuid));
    }

    @Override
    public Mono<Void> deleteAll()
    {
        return statementHandler.runMono(this::deleteAllSync);
    }

    /**
     * Moves every row of the username-keyed {@code players} / {@code player_ips} tables into
     * {@code player_data}, then drops them. Those rows never recorded a UUID, so each one is keyed
     * by {@link PlayerData#offlineUuid(String)} and moved to the real UUID on that player's next
     * join. Of the addresses each player had, only the latest is kept.
     * <p>
     * Safe to repeat: rows are upserted, and the old tables only go once every row has landed, so a
     * crash part-way through redoes the copy on the next start.
     */
    public void migrateLegacyTables() throws SQLException
    {
        final String legacyPlayers = adapter.quoteIdentifier("players");
        final String legacyIps = adapter.quoteIdentifier("player_ips");
        final Map<String, PlayerData> byUsername = new LinkedHashMap<>();

        try (ResultSet rs = statementHandler.executeQuery(String.format("SELECT %s FROM %s", String.join(", ", legacyColumns), legacyPlayers)))
        {
            while (rs.next())
            {
                final PlayerData data = loadPlayerFromRow(rs);
                byUsername.put(data.getUsername(), data);
            }
        }

        // Ordered oldest first, so the last address written for each player is their latest.
        try (ResultSet rs = statementHandler.executeQuery(String.format("SELECT %s, %s FROM %s ORDER BY %s ASC",
                colUsername, colIp, legacyIps, colId)))
        {
            while (rs.next())
            {
                final PlayerData data = byUsername.get(rs.getString("username"));
                if (data != null)
                    data.setIp(rs.getString("ip"));
            }
        }

        for (final PlayerData data : byUsername.values())
        {
            saveOrUpdate(data);
        }

        statementHandler.executeUpdate(String.format("DROP TABLE %s", legacyIps));
        statementHandler.executeUpdate(String.format("DROP TABLE %s", legacyPlayers));
        FLog.info("Moved {} player record(s) from the old players table to player_data.", byUsername.size());
    }

    /**
     * Moves every admin in the retired {@code admins} / {@code admin_ips} / {@code discord_links}
     * tables onto the matching {@code player_data} row, creating one where the admin has none.
     * Which row that is follows {@link LegacyAdmin#targetUuid(boolean)}.
     * <p>
     * Records were never deduplicated, so one admin may have several rows; the most recently
     * updated row for each name wins. The admin's addresses all carry over as trusted ones, up to
     * the cap {@link PlayerData} keeps.
     * <p>
     * Once done, the three tables are renamed with a {@code legacy_} prefix rather than dropped, as a
     * backup until the move has been checked; they can be dropped by hand after that.
     *
     * @param hasDiscordLinks whether a {@code discord_links} table exists to read.
     * @param authenticated   whether Mojang vouches for session UUIDs, directly or through a proxy.
     */
    public void migrateLegacyStaff(boolean hasDiscordLinks, boolean authenticated) throws SQLException
    {
        final String admins = adapter.quoteIdentifier("admins");
        final String adminIps = adapter.quoteIdentifier("admin_ips");
        final String discordLinks = adapter.quoteIdentifier("discord_links");

        // Oldest first, so each list ends on the latest address.
        final Map<Integer, List<String>> ipsByAdmin = new HashMap<>();
        try (ResultSet rs = statementHandler.executeQuery(String.format("SELECT admin_id, ip FROM %s ORDER BY %s ASC", adminIps, colId)))
        {
            while (rs.next())
            {
                ipsByAdmin.computeIfAbsent(rs.getInt("admin_id"), id -> new ArrayList<>()).add(rs.getString("ip"));
            }
        }

        // Oldest first, so a later row for the same name replaces an earlier one.
        final Map<String, LegacyAdmin> byName = new LinkedHashMap<>();
        try (ResultSet rs = statementHandler.executeQuery(String.format("SELECT * FROM %s ORDER BY %s ASC, %s ASC",
                admins, colUpdatedAt, colId)))
        {
            while (rs.next())
            {
                final String username = rs.getString("username");
                final String rankId = LegacyAdmin.resolveRankId(rs.getString("custom_rank"), rs.getString("rank"));
                if (username == null || rankId == null)
                    continue;

                final long lastLogin = Optional.ofNullable(rs.getString("last_login"))
                                               .map(FUtil::stringToDate)
                                               .map(date -> date.getTime() / 1000L)
                                               .orElse(0L);
                byName.put(username.toLowerCase(), new LegacyAdmin(rs.getString("uuid"), username, rankId,
                        rs.getBoolean("active"), ipsByAdmin.get(rs.getInt("id")), lastLogin, rs.getString("login_message")));
            }
        }

        final Map<String, String> discordByAdminUuid = new HashMap<>();
        if (hasDiscordLinks)
        {
            try (ResultSet rs = statementHandler.executeQuery(String.format("SELECT admin_uuid, discord_user_id FROM %s", discordLinks)))
            {
                while (rs.next())
                {
                    discordByAdminUuid.put(rs.getString("admin_uuid").trim(), rs.getString("discord_user_id"));
                }
            }
        }

        final Set<String> takenIdentifiers = new HashSet<>();
        try (ResultSet rs = statementHandler.executeQuery(String.format("SELECT %s FROM %s WHERE %s IS NOT NULL",
                colDiscordIdentifier, tblPlayers, colDiscordIdentifier)))
        {
            while (rs.next())
            {
                takenIdentifiers.add(rs.getString(1));
            }
        }

        for (final LegacyAdmin admin : byName.values())
        {
            final UUID target = admin.targetUuid(authenticated);
            final Optional<PlayerData> existing = findByUuid(target);
            final PlayerData data = existing.isPresent()
                    ? existing.get()
                    : findByUsername(admin.username())
                            .filter(LegacyAdmin::isUnclaimedPlaceholder)
                            .orElseGet(() -> new PlayerData(admin.username()));
            final UUID previous = data.getUuid();

            data.setUuid(target);
            admin.applyTo(data);

            final Optional<String> discordId = Optional.ofNullable(admin.uuid())
                                                       .map(String::trim)
                                                       .map(discordByAdminUuid::get);
            if (discordId.isPresent())
            {
                data.setDiscordId(discordId.get());
                if (data.getDiscordIdentifier().isEmpty())
                {
                    final String identifier = PlayerData.newDiscordIdentifier(takenIdentifiers::contains);
                    takenIdentifiers.add(identifier);
                    data.setDiscordIdentifier(identifier);
                }
            }

            saveOrUpdate(data);
            if (!previous.equals(target))
                delete(previous);
        }

        renameTable("admin_ips", "legacy_admin_ips");
        renameTable("admins", "legacy_admins");
        if (hasDiscordLinks)
            renameTable("discord_links", "legacy_discord_links");

        FLog.info("Moved {} admin(s) onto player_data. The old tables are kept as legacy_admins, legacy_admin_ips and legacy_discord_links.",
                byName.size());
    }

    private void renameTable(String from, String to) throws SQLException
    {
        statementHandler.executeUpdate(String.format("ALTER TABLE %s RENAME TO %s",
                adapter.quoteIdentifier(from), adapter.quoteIdentifier(to)));
    }

    private List<Object> dataValues(PlayerData data)
    {
        // Arrays.asList rather than List.of: several of these may be null.
        return Arrays.asList(
                data.getUsername(),
                data.getFirstJoinUnix(),
                data.getLastJoinUnix(),
                data.getPotionSpyMode().getName(),
                data.getCommandSpyMode().getName(),
                Registry.SOUNDS.getKeyOrThrow(data.getCommandSpyAlertSound()).asString(),
                data.commandSpyAlertRegex(),
                data.getSignSpyMode().getName(),
                data.getBookSpyMode().getName(),
                data.isMuted(),
                data.isFrozen(),
                data.isCommandsBlocked(),
                data.isJoinLeaveMessagesEnabled(),
                data.getStrikes(),
                data.getSavedTag(),
                serializeList(data.getTitles()),
                serializeNickname(data),
                data.getStoredIp().orElse(null),
                data.getRankId().orElse(null),
                data.isAdminActive(),
                data.getLoginMessage().orElse(null),
                data.getDiscordId().orElse(null),
                data.getDiscordIdentifier().orElse(null),
                serializeList(data.getTrustedIps()));
    }

    private Optional<PlayerData> findOne(String sql, Object key) throws SQLException
    {
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, key);
             ResultSet rs = stmt.executeQuery())
        {
            return rs.next() ? Optional.of(loadStoredRow(rs)) : Optional.empty();
        }
    }

    private List<PlayerData> findMany(String sql) throws SQLException
    {
        final List<PlayerData> players = new ArrayList<>();
        try (ResultSet rs = statementHandler.executeQuery(sql))
        {
            while (rs.next())
            {
                players.add(loadStoredRow(rs));
            }
        }
        return players;
    }

    /**
     * A row of {@code player_data}. CHAR(36) can come back padded on some drivers, hence the trim.
     */
    private PlayerData loadStoredRow(ResultSet rs) throws SQLException
    {
        final PlayerData data = loadPlayerFromRow(rs);
        data.setUuid(UUID.fromString(rs.getString("uuid").trim()));
        data.setIp(rs.getString("ip"));
        data.setRankId(rs.getString("rank_id"));
        data.setAdminActive(rs.getBoolean("admin_active"));
        data.setLoginMessage(rs.getString("login_message"));
        data.setDiscordId(rs.getString("discord_id"));
        data.setDiscordIdentifier(rs.getString("discord_identifier"));
        data.setTrustedIps(parseList(rs.getString("trusted_ips")));
        return data;
    }

    /**
     * Reads the columns shared with the legacy table. The result is keyed by its username's offline
     * UUID until the caller says otherwise.
     */
    private PlayerData loadPlayerFromRow(ResultSet rs) throws SQLException
    {
        PlayerData data = new PlayerData(rs.getString("username"));
        data.setFirstJoinUnix(rs.getLong("first_join_unix"));
        data.setLastJoinUnix(rs.getLong("last_join_unix"));
        data.setPotionSpyMode(SpyMode.fromStorage(rs.getString("potion_spy_mode")));
        data.setCommandSpyMode(SpyMode.fromStorage(rs.getString("command_spy_mode")));
        data.setCommandSpyAlertRegex(rs.getString("command_spy_alert_regex"));
        data.setSignSpyMode(SpyMode.fromStorage(rs.getString("sign_spy_mode")));
        data.setBookSpyMode(SpyMode.fromStorage(rs.getString("book_spy_mode")));
        data.setMuted(rs.getBoolean("muted"));
        data.setFrozen(rs.getBoolean("frozen"));
        data.setCommandsBlocked(rs.getBoolean("commands_blocked"));
        data.setJoinLeaveMessagesEnabled(rs.getBoolean("join_leave_messages"));
        data.setStrikes(rs.getInt("strikes"));
        data.setSavedTag(rs.getString("saved_tag"));
        data.setTitles(parseList(rs.getString("titles")));

        final String alertSound = rs.getString("command_spy_alert_sound");
        if (alertSound != null && !alertSound.isBlank())
        {
            final NamespacedKey key = NamespacedKey.fromString(alertSound);
            if (key != null)
            {
                data.setCommandSpyAlertSound(Registry.SOUNDS.get(key));
            }
        }

        String rawNickname = rs.getString("nickname");
        if (rawNickname != null && !rawNickname.isEmpty())
        {
            data.setNicknameRaw(AdventureUtil.legacyToComponent(rawNickname));
        }

        return data;
    }


    /**
     * Held titles and trusted addresses are stored as a comma-separated list in one column rather
     * than as a child table: a player has a handful at most and they are always read with the
     * player row itself, so a join would cost more than it saves.
     */
    private static String serializeList(Collection<String> values)
    {
        return values.isEmpty() ? null : String.join(",", values);
    }

    private static List<String> parseList(String stored)
    {
        return stored == null || stored.isBlank()
                ? List.of()
                : Arrays.stream(stored.split(","))
                        .map(String::trim)
                        .filter(id -> !id.isEmpty())
                        .toList();
    }

    private static String serializeNickname(PlayerData data)
    {
        return data.getNickname() != null ? AdventureUtil.componentToLegacy(data.getNickname()) : null;
    }
}

package me.totalfreedom.totalfreedommod.player;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import me.totalfreedom.api.FreedomAPI;
import me.totalfreedom.api.rank.RankRole;
import me.totalfreedom.totalfreedommod.rank.CustomRank;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;

/**
 * Moves the retired {@code admins.json} and {@code discord_links.json} onto each admin's own
 * {@code players/<uuid>.json}, then renames both to {@code *.migrated} as a backup.
 * <p>
 * The SQL tables get the same treatment during the database's own migrations; this covers the JSON
 * snapshot, which is all a server without a reachable database ever had. Each file written is
 * dated back to when {@code admins.json} was last written, so the per-player reconcile in
 * {@link PlayerList} never mistakes an old snapshot for something newer than the database.
 */
final class LegacyStaffJsonMigration
{
    private static final String ADMINS_FILE = "admins.json";
    private static final String LINKS_FILE = "discord_links.json";
    private static final String MIGRATED_SUFFIX = ".migrated";

    private final FreedomAPI plugin;
    private final PlayerList players;

    LegacyStaffJsonMigration(FreedomAPI plugin, PlayerList players)
    {
        this.plugin = plugin;
        this.players = players;
    }

    void run()
    {
        final File adminsFile = new File(plugin.getDataFolder(), ADMINS_FILE);
        if (!adminsFile.exists())
            return;

        // The admin list created an empty file when it had none to read.
        if (adminsFile.length() == 0)
        {
            renameAside(adminsFile);
            return;
        }

        final Optional<JsonObject> admins = readObject(adminsFile);
        if (admins.isEmpty())
        {
            FLog.warn("Could not read {}; leaving it in place and moving no admins from it.", ADMINS_FILE);
            return;
        }

        final File linksFile = new File(plugin.getDataFolder(), LINKS_FILE);
        final Map<String, String> discordByAdminUuid = new HashMap<>();
        readObject(linksFile).ifPresent(links -> links.entrySet()
                                                      .stream()
                                                      .filter(link -> link.getValue().isJsonPrimitive())
                                                      .forEach(link -> discordByAdminUuid.put(link.getKey().trim(), link.getValue().getAsString())));

        final boolean authenticated = plugin.getServer().getServerConfig().isProxyOnlineMode();
        final long snapshotTime = adminsFile.lastModified();
        final Set<String> takenIdentifiers = new HashSet<>();
        final List<String> unconfirmedNames = new ArrayList<>();
        int moved = 0;

        for (final Map.Entry<String, JsonElement> entry : admins.get().entrySet())
        {
            if (!entry.getValue().isJsonObject())
                continue;

            final Optional<LegacyAdmin> admin = toLegacyAdmin(entry.getKey(), entry.getValue().getAsJsonObject(), unconfirmedNames);
            if (admin.isEmpty())
            {
                FLog.warn("Skipping admin '{}' in {}: no name or rank.", entry.getKey(), ADMINS_FILE);
                continue;
            }

            if (moveAdmin(admin.get(), authenticated, discordByAdminUuid, takenIdentifiers, snapshotTime))
                moved++;
        }

        renameAside(adminsFile);
        if (linksFile.exists())
            renameAside(linksFile);

        FLog.info("Moved {} admin(s) from {} onto their player data; the old file is kept as {}{}.",
                moved, ADMINS_FILE, ADMINS_FILE, MIGRATED_SUFFIX);

        if (!unconfirmedNames.isEmpty())
            FLog.warn("Couldn't confirm how these admins capitalise their names: {}. Any that are wrong won't be recognised on join; move them with /saconfig rekey <name> <player> once they're online.",
                    String.join(", ", unconfirmedNames));
    }

    /**
     * @return whether the admin's record was written.
     */
    private boolean moveAdmin(LegacyAdmin admin, boolean authenticated, Map<String, String> discordByAdminUuid,
                              Set<String> takenIdentifiers, long snapshotTime)
    {
        final UUID target = admin.targetUuid(authenticated);
        final File targetFile = players.getConfigFile(target);
        final File legacyFile = players.getLegacyConfigFile(admin.username());

        final Optional<PlayerData> existing = Optional.of(targetFile)
                                                      .filter(File::exists)
                                                      .map(players::readJsonFile);
        final Optional<PlayerData> byName = existing.isPresent()
                ? Optional.empty()
                : Optional.of(legacyFile)
                          .filter(File::exists)
                          .map(players::readJsonFile)
                          .filter(LegacyAdmin::isUnclaimedPlaceholder);
        final PlayerData data = existing.or(() -> byName)
                                        .orElseGet(() -> new PlayerData(admin.username()));

        data.setUuid(target);
        admin.applyTo(data);

        Optional.ofNullable(admin.uuid())
                .map(String::trim)
                .map(discordByAdminUuid::get)
                .ifPresent(discordId ->
                {
                    data.setDiscordId(discordId);
                    if (data.getDiscordIdentifier().isEmpty())
                    {
                        final String identifier = PlayerData.newDiscordIdentifier(taken ->
                                takenIdentifiers.contains(taken) || players.isDiscordIdentifierTaken(taken));
                        takenIdentifiers.add(identifier);
                        data.setDiscordIdentifier(identifier);
                    }
                });

        if (!data.isValid())
        {
            FLog.warn("Skipping admin '{}': their record would be missing details (no stored address?).", admin.username());
            return false;
        }

        if (!players.saveToJson(data))
            return false;

        targetFile.setLastModified(snapshotTime);
        if (byName.isPresent())
            legacyFile.delete();

        return true;
    }

    /**
     * @param unconfirmedNames collects the names whose capitalisation is only a guess: no
     *                         {@code name} member, and the server's name cache doesn't know them.
     */
    private Optional<LegacyAdmin> toLegacyAdmin(String key, JsonObject json, List<String> unconfirmedNames)
    {
        final Optional<String> rankId = string(json, "rankId").map(String::toLowerCase)
                                                              .or(this::adminDefaultRankId);
        if (key == null || rankId.isEmpty())
            return Optional.empty();

        // The key is the lowercased name, so it only stands in when nothing better is known.
        final String username = string(json, "name").or(() -> LegacyAdmin.cachedName(plugin.getServer(), key))
                                                    .orElseGet(() ->
                                                    {
                                                        unconfirmedNames.add(key);
                                                        return key;
                                                    });

        // Oldest first, so the last one is the latest.
        final List<String> ips = Optional.ofNullable(json.get("ips"))
                                         .filter(JsonElement::isJsonArray)
                                         .map(JsonElement::getAsJsonArray)
                                         .map(array -> array.asList()
                                                            .stream()
                                                            .filter(JsonElement::isJsonPrimitive)
                                                            .map(JsonElement::getAsString)
                                                            .toList())
                                         .orElse(List.of());
        final long lastLogin = string(json, "lastLogin").map(FUtil::stringToDate)
                                                         .map(date -> date.getTime() / 1000L)
                                                         .orElse(0L);
        final boolean active = !json.has("active") || json.get("active").getAsBoolean();

        return Optional.of(new LegacyAdmin(string(json, "uuid").orElse(null), username, rankId.get(), active, ips,
                lastLogin, string(json, "loginMessage").orElse(null)));
    }

    /**
     * An unset rank used to mean whichever rank fills the default admin role.
     */
    private Optional<String> adminDefaultRankId()
    {
        return Optional.ofNullable(plugin.ranks())
                       .flatMap(ranks -> ranks.getRegistry().byRole(RankRole.ADMIN_DEFAULT))
                       .map(CustomRank::getId);
    }

    private static Optional<String> string(JsonObject json, String member)
    {
        return Optional.ofNullable(json.get(member))
                       .filter(JsonElement::isJsonPrimitive)
                       .map(JsonElement::getAsString)
                       .filter(value -> !value.isBlank());
    }

    private static Optional<JsonObject> readObject(File file)
    {
        if (!file.exists())
            return Optional.empty();

        try (FileReader reader = new FileReader(file))
        {
            final JsonElement json = JsonParser.parseReader(reader);
            return json.isJsonObject() ? Optional.of(json.getAsJsonObject()) : Optional.empty();
        }
        catch (IOException | RuntimeException ex)
        {
            FLog.warn("Could not read {}", file.getName(), ex);
            return Optional.empty();
        }
    }

    private static void renameAside(File file)
    {
        final File target = new File(file.getParentFile(), file.getName() + MIGRATED_SUFFIX);
        if (!file.renameTo(target))
            FLog.warn("Could not rename {} to {}; it will be read again next start.", file.getName(), target.getName());
    }
}

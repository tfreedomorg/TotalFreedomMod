package me.totalfreedom.totalfreedommod.discord;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.UUID;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.sql.PersistenceQueue;
import me.totalfreedom.totalfreedommod.sql.adapter.DiscordLinkRepository;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.JsonUtil;
import me.totalfreedom.totalfreedommod.util.FUtil;

import com.google.gson.reflect.TypeToken;

/**
 * JSON write-through + startup reconciliation for admin-uuid to Discord-user-id links.
 */
final class DiscordLinkJsonSync
{
    static final String DATA_FILENAME = "discord_links.json";

    private static final Type LINKS_MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();
    private static final PersistenceQueue WRITES = new PersistenceQueue("discord link");

    private DiscordLinkJsonSync()
    {
    }

    /**
     * Rewrites discord_links.json from the database's current state. Call after any
     * successful link/unlink write.
     */
    static void writeSnapshot(TotalFreedomMod plugin, DiscordLinkRepository repo)
    {
        try
        {
            Map<String, String> links = repo.loadAll();
            File file = new File(plugin.getDataFolder(), DATA_FILENAME);
            try (FileWriter writer = new FileWriter(file))
            {
                JsonUtil.GSON.toJson(links, LINKS_MAP_TYPE, writer);
            }
        }
        catch (Exception ex)
        {
            FLog.severe("Failed to save " + DATA_FILENAME + ": " + ex.getMessage());
        }
    }

    /**
     * If discord_links.json was written more recently than the database's last update, re-import
     * it into SQL. Runs entirely off the main thread, so this is safe to call from {@code onStart}.
     */
    static void reconcileFromJsonIfNewer(TotalFreedomMod plugin, DiscordLinkRepository repo)
    {
        final File file = new File(plugin.getDataFolder(), DATA_FILENAME);
        if (!file.exists())
            return;

        final Map<String, String> jsonLinks;
        try (FileReader reader = new FileReader(file))
        {
            final Map<String, String> loaded = JsonUtil.GSON.fromJson(reader, LINKS_MAP_TYPE);
            jsonLinks = loaded != null ? loaded : Map.of();
        }
        catch (Exception ex)
        {
            FLog.warning(String.format("Failed to read %s: %s", DATA_FILENAME, ex.getMessage()));
            return;
        }

        if (jsonLinks.isEmpty())
            return;

        final long fileModified = file.lastModified();

        WRITES.enqueue(repo.getMaxUpdatedAtAsync()
              .map(sqlUpdatedAt -> FUtil.isSnapshotNewer(fileModified, sqlUpdatedAt))
              .defaultIfEmpty(Boolean.TRUE)
              .filter(Boolean::booleanValue)
              .flatMap(ignored ->
              {
                  FLog.info(String.format("%s is newer than the database; re-importing %d discord link(s) from it.",
                                          DATA_FILENAME, jsonLinks.size()));
                  return Flux.fromIterable(jsonLinks.entrySet())
                             .concatMap(entry -> repo.relinkAsync(UUID.fromString(entry.getKey()), entry.getValue()))
                             .then();
              })
              .onErrorResume(ex ->
              {
                  FLog.warning(String.format("Failed to reconcile %s into the database: %s",
                                             DATA_FILENAME, ex.getMessage()));
                  return Mono.empty();
              })
              .then());
    }
}

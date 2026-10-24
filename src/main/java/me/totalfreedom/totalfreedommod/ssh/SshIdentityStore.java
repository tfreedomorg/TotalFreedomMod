package me.totalfreedom.totalfreedommod.ssh;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.JsonUtil;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class SshIdentityStore
{
    private static final DateTimeFormatter LOGIN_FMT = DateTimeFormatter.ofPattern("dd-MM-yy HH:mm");
    private static final Gson GSON = JsonUtil.GSON;

    private final File directory;
    private final Map<String, SshIdentity> identities = new ConcurrentHashMap<>();

    public SshIdentityStore(File directory)
    {
        this.directory = directory;
        reload();
    }

    public void reload()
    {
        identities.clear();
        File[] files = directory.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null)
        {
            return;
        }
        for (File file : files)
        {
            String identifier = file.getName().substring(0, file.getName().length() - ".json".length());
            try (FileReader reader = new FileReader(file))
            {
                JsonObject root = GSON.fromJson(reader, JsonObject.class);
                identities.put(identifier, fromJson(identifier, root));
            }
            catch (Exception e)
            {
                FLog.warning("[SSH] Failed to load identity '" + identifier + "': " + e.getMessage());
            }
        }
        FLog.info("[SSH] Loaded " + identities.size() + " SSH identit" + (identities.size() == 1 ? "y" : "ies") + ".");
    }

    public SshIdentity get(String identifier)
    {
        return identities.get(identifier);
    }

    public Collection<SshIdentity> getAll()
    {
        return Collections.unmodifiableCollection(identities.values());
    }

    public void setTotpSecret(String identifier, String secret)
    {
        SshIdentity current = identities.get(identifier);
        if (current != null)
        {
            identities.put(identifier, new SshIdentity(
                    current.identifier(),
                    current.lastLogin(),
                    current.rank(),
                    secret,
                    current.keys()));
        }
        CompletableFuture.runAsync(() ->
        {
            File file = new File(directory, identifier + ".json");
            if (!file.exists())
            {
                return;
            }
            try
            {
                JsonObject root;
                try (FileReader reader = new FileReader(file))
                {
                    root = GSON.fromJson(reader, JsonObject.class);
                }
                root.addProperty("totp-secret", secret);
                try (FileWriter writer = new FileWriter(file))
                {
                    GSON.toJson(root, writer);
                }
            }
            catch (Exception e)
            {
                FLog.warning("[SSH] Failed to write TOTP secret for '" + identifier + "': " + e.getMessage());
            }
        });
    }

    public void updateLastLogin(String identifier)
    {
        String now = LocalDateTime.now().format(LOGIN_FMT);
        SshIdentity current = identities.get(identifier);
        if (current != null)
        {
            identities.put(identifier, new SshIdentity(
                    current.identifier(),
                    now,
                    current.rank(),
                    current.totpSecret(),
                    current.keys()));
        }
        CompletableFuture.runAsync(() ->
        {
            File file = new File(directory, identifier + ".json");
            if (!file.exists())
            {
                return;
            }
            try
            {
                JsonObject root;
                try (FileReader reader = new FileReader(file))
                {
                    root = GSON.fromJson(reader, JsonObject.class);
                }
                root.addProperty("last-login", now);
                try (FileWriter writer = new FileWriter(file))
                {
                    GSON.toJson(root, writer);
                }
            }
            catch (Exception e)
            {
                FLog.warning("[SSH] Failed to update last-login for '" + identifier + "': " + e.getMessage());
            }
        });
    }

    private static SshIdentity fromJson(String identifier, JsonObject root)
    {
        return new SshIdentity(
                identifier,
                getString(root, "last-login"),
                getString(root, "rank"),
                getString(root, "totp-secret"),
                Collections.unmodifiableMap(parseKeys(root)));
    }

    private static Map<String, String> parseKeys(JsonObject root)
    {
        Map<String, String> keys = new HashMap<>();
        if (!root.has("keys"))
        {
            return keys;
        }
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("keys").entrySet())
        {
            if (entry.getValue().isJsonObject())
            {
                JsonObject keyObj = entry.getValue().getAsJsonObject();
                if (keyObj.has("key"))
                {
                    keys.put(entry.getKey(), keyObj.get("key").getAsString());
                }
            }
        }
        return keys;
    }

    private static String getString(JsonObject obj, String key)
    {
        return obj.has(key) ? obj.get(key).getAsString() : null;
    }
}

package me.totalfreedom.totalfreedommod;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.util.FileUtil;

import me.totalfreedom.totalfreedommod.framework.PluginComponent;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

public class BackupManager extends PluginComponent<TotalFreedomMod>
{
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type BACKUP_DATA_TYPE = new TypeToken<Map<String, BackupEntry>>() {}.getType();

    public BackupManager(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    public void createBackups(String file)
    {
        createBackups(file, false);
    }

    public void createBackups(String file, boolean onlyWeekly)
    {
        final String save = file.split("\\.")[0];
        final File configFile = new File(plugin.getDataFolder(), "backup/backup.json");
        final Map<String, BackupEntry> data = loadBackupData(configFile);
        final BackupEntry entry = data.getOrDefault(save, new BackupEntry(null, null));

        Long weekly = entry.weekly();
        if (weekly == null || weekly + 3600L * 24 * 7 < FUtil.getUnixTime())
        {
            performBackup(file, "weekly");
            weekly = FUtil.getUnixTime();
        }

        Long daily = entry.daily();
        if (!onlyWeekly && (daily == null || daily + 3600L * 24 < FUtil.getUnixTime()))
        {
            performBackup(file, "daily");
            daily = FUtil.getUnixTime();
        }

        data.put(save, new BackupEntry(weekly, daily));
        saveBackupData(configFile, data);
    }

    private void performBackup(String file, String type)
    {
        FLog.info("Backing up " + file + " to " + file + "." + type + ".bak");
        final File backupFolder = new File(plugin.getDataFolder(), "backup");

        if (!backupFolder.exists())
        {
            backupFolder.mkdirs();
        }

        final File oldYaml = new File(plugin.getDataFolder(), file);
        final File newYaml = new File(backupFolder, file + "." + type + ".bak");
        FileUtil.copy(oldYaml, newYaml);
    }

    private Map<String, BackupEntry> loadBackupData(File configFile)
    {
        if (!configFile.exists())
        {
            return new HashMap<>();
        }

        try (FileReader reader = new FileReader(configFile))
        {
            Map<String, BackupEntry> data = GSON.fromJson(reader, BACKUP_DATA_TYPE);
            return data != null ? data : new HashMap<>();
        }
        catch (IOException ex)
        {
            FLog.severe("Could not read backup.json: " + ex.getMessage());
            return new HashMap<>();
        }
    }

    private void saveBackupData(File configFile, Map<String, BackupEntry> data)
    {
        try
        {
            configFile.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(configFile))
            {
                GSON.toJson(data, BACKUP_DATA_TYPE, writer);
            }
        }
        catch (IOException ex)
        {
            FLog.severe("Could not save backup.json: " + ex.getMessage());
        }
    }

    private record BackupEntry(Long weekly, Long daily)
    {
    }
}

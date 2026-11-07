package me.totalfreedom.totalfreedommod.config;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.EnumMap;
import java.util.List;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import me.totalfreedom.api.config.IMainConfig;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.framework.PluginComponent;
import me.totalfreedom.totalfreedommod.util.FLog;

import org.apache.commons.io.FileUtils;

public class MainConfig extends PluginComponent<TotalFreedomMod> implements IMainConfig
{

    public static final String CONFIG_FILENAME = "config.yml";
    //
    private final EnumMap<ConfigEntry, Object> entries;
    private final ConfigDefaults defaults;

    public MainConfig(TotalFreedomMod plugin)
    {
        super(plugin);

        entries = new EnumMap<>(ConfigEntry.class);

        ConfigDefaults tempDefaults = null;
        try
        {
            try
            {
                try (InputStream defaultConfig = getDefaultConfig())
                {
                    tempDefaults = new ConfigDefaults(defaultConfig);
                    for (ConfigEntry entry : ConfigEntry.values())
                    {
                        entries.put(entry, tempDefaults.get(entry.getConfigName()));
                    }
                }
            }
            catch (IOException ex)
            {
                FLog.error("Failed to load configuration file", ex);
            }

            copyDefaultConfig(getConfigFile());

            load();
        }
        catch (Exception ex)
        {
            FLog.error("Failed to load configuration file", ex);
        }

        defaults = tempDefaults;
    }

    public void load()
    {
        try
        {
            YamlConfiguration config = new YamlConfiguration();
            File configFile = getConfigFile();

            config.load(configFile);

            boolean needsSave = false;
            for (ConfigEntry entry : ConfigEntry.values())
            {
                String path = entry.getConfigName();
                if (config.contains(path))
                {
                    Object value = config.get(path);
                    if (value == null || entry.getType().isAssignableFrom(value.getClass()))
                    {
                        entries.put(entry, value);
                    }
                    else
                    {
                        FLog.warn("Value for " + entry.getConfigName() + " is of type "
                                + value.getClass().getSimpleName() + ". Needs to be " + entry.getType().getSimpleName() + ". Using default value.");
                    }
                }
                else
                {
                    Object defaultValue = defaults != null ? defaults.get(path) : null;
                    if (defaultValue != null)
                    {
                        config.set(path, defaultValue);
                        entries.put(entry, defaultValue);
                        needsSave = true;
                    }
                }
            }

            if (needsSave)
            {
                config.save(configFile);
            }
        }
        catch (IOException | InvalidConfigurationException ex)
        {
            FLog.error("Failed to load configuration file", ex);
        }
    }

    private File getConfigFile()
    {
        return new File(plugin.getDataFolder(), "config.yml");
    }

    public String getString(ConfigEntry entry)
    {
        try
        {
            return get(entry, String.class);
        }
        catch (IllegalArgumentException ex)
        {
            FLog.error("Failed to get {} as a String", entry.name(), ex);
        }
        return null;
    }

    public void setString(ConfigEntry entry, String value)
    {
        try
        {
            set(entry, value, String.class);
        }
        catch (IllegalArgumentException ex)
        {
            FLog.error("Failed to set {} to a String", entry.name(), ex);
        }
    }

    public Double getDouble(ConfigEntry entry)
    {
        try
        {
            return get(entry, Double.class);
        }
        catch (IllegalArgumentException ex)
        {
            FLog.error("Failed to get {} as a Double", entry.name(), ex);
        }
        return null;
    }

    public void setDouble(ConfigEntry entry, Double value)
    {
        try
        {
            set(entry, value, Double.class);
        }
        catch (IllegalArgumentException ex)
        {
            FLog.error("Failed to set {} to a Double", entry.name(), ex);
        }
    }

    public Boolean getBoolean(ConfigEntry entry)
    {
        try
        {
            return get(entry, Boolean.class);
        }
        catch (IllegalArgumentException ex)
        {
            FLog.error("Failed to get {} as a Boolean", entry.name(), ex);
        }
        return null;
    }

    public void setBoolean(ConfigEntry entry, Boolean value)
    {
        try
        {
            set(entry, value, Boolean.class);
        }
        catch (IllegalArgumentException ex)
        {
            FLog.error("Failed to set {} to a Boolean", entry.name(), ex);
        }
    }

    public Integer getInteger(ConfigEntry entry)
    {
        try
        {
            return get(entry, Integer.class);
        }
        catch (IllegalArgumentException ex)
        {
            FLog.error("Failed to get {} as an Integer", entry.name(), ex);
        }
        return null;
    }

    public void setInteger(ConfigEntry entry, Integer value)
    {
        try
        {
            set(entry, value, Integer.class);
        }
        catch (IllegalArgumentException ex)
        {
            FLog.error("Failed to set {} to an Integer", entry.name(), ex);
        }
    }

    public List getList(ConfigEntry entry)
    {
        try
        {
            return get(entry, List.class);
        }
        catch (IllegalArgumentException ex)
        {
            FLog.error("Failed to get {} as a List", entry.name(), ex);
        }
        return null;
    }

    public void setList(ConfigEntry entry, List<?> value)
    {
        try
        {
            set(entry, value, List.class);
        }
        catch (IllegalArgumentException ex)
        {
            FLog.error("Failed to set {} to a List", entry.name(), ex);
        }
    }

    public <T> T get(ConfigEntry entry, Class<T> type) throws IllegalArgumentException
    {
        Object value = entries.get(entry);
        try
        {
            return type.cast(value);
        }
        catch (ClassCastException ex)
        {
            throw new IllegalArgumentException(entry.name() + " is not of type " + type.getSimpleName());
        }
    }

    public <T> void set(ConfigEntry entry, T value, Class<T> type) throws IllegalArgumentException
    {
        if (!type.isAssignableFrom(entry.getType()))
        {
            throw new IllegalArgumentException(entry.name() + " is not of type " + type.getSimpleName());
        }
        if (value != null && !type.isAssignableFrom(value.getClass()))
        {
            throw new IllegalArgumentException("Value is not of type " + type.getSimpleName());
        }
        entries.put(entry, value);
    }

    public void save()
    {
        try
        {
            YamlConfiguration config = new YamlConfiguration();
            File configFile = getConfigFile();

            if (configFile.exists())
            {
                config.load(configFile);
            }

            for (ConfigEntry entry : ConfigEntry.values())
            {
                config.set(entry.getConfigName(), entries.get(entry));
            }

            config.save(configFile);
        }
        catch (IOException | InvalidConfigurationException ex)
        {
            FLog.error("Failed to save configuration file", ex);
        }
    }

    private void copyDefaultConfig(File targetFile)
    {
        if (targetFile.exists())
        {
            return;
        }

        FLog.info("Installing default configuration file template: " + targetFile.getPath());

        try
        {
            try (InputStream defaultConfig = getDefaultConfig())
            {
                FileUtils.copyInputStreamToFile(defaultConfig, targetFile);
            }
        }
        catch (IOException ex)
        {
            FLog.error("Failed to copy default configuration file", ex);
        }
    }

    private InputStream getDefaultConfig()
    {
        return plugin.getResource(TotalFreedomMod.CONFIG_FILENAME);
    }

    public ConfigDefaults getDefaults()
    {
        return defaults;
    }

    public static class ConfigDefaults
    {

        private YamlConfiguration defaults = null;

        private ConfigDefaults(InputStream defaultConfig)
        {
            try
            {
                defaults = new YamlConfiguration();
                final InputStreamReader isr = new InputStreamReader(defaultConfig);
                defaults.load(isr);
                isr.close();
            }
            catch (IOException | InvalidConfigurationException ex)
            {
                FLog.error("Failed to read defaults", ex);
            }
        }

        public Object get(String path)
        {
            return defaults.get(path);
        }
    }

}

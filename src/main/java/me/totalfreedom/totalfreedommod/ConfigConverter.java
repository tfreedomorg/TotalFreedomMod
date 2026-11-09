package me.totalfreedom.totalfreedommod;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import me.totalfreedom.totalfreedommod.banning.PermBan;
import me.totalfreedom.totalfreedommod.banning.PermbanList;
import me.totalfreedom.totalfreedommod.framework.PluginComponent;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.rank.CustomRank;
import me.totalfreedom.totalfreedommod.rank.RankManager;
import me.totalfreedom.api.rank.RankRole;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;
import me.totalfreedom.totalfreedommod.util.JsonUtil;

import com.google.common.io.Files;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

public class ConfigConverter extends PluginComponent<TotalFreedomMod>
{

    public static final int CURRENT_CONFIG_VERSION = 1;

    /**
     * Suffix shared by the retired console-only rank ids ({@code senior_console},
     * {@code telnet_console}), matched so that any of them is remapped without naming each.
     */
    private static final String CONSOLE_RANK_SUFFIX = "_console";

    public ConfigConverter(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    public void convert()
    {
        File data = plugin.getDataFolder();
        data.mkdirs();
        File versionFile = new File(data, "version.yml");

        boolean convert = false;
        if (!versionFile.exists() && data.listFiles().length > 0)
            convert = true;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                versionFile);
        if (!versionFile.exists())
            try
            {
                versionFile.getParentFile().mkdirs();
                if (!versionFile.createNewFile())
                    throw new IOException("Could not create file " + versionFile.getName());
            }
            catch (IOException ex)
            {
                FLog.error(
                        "File error. %s could not be created, so this config conversion is aborted. \n%s",
                        versionFile.getName(), ex.getMessage());
                return;
            }

        if (config.getInt("version", -1) < CURRENT_CONFIG_VERSION)
            convert = true;

        if (convert)
        {
            FLog.warn("Converting old configs to new format...");

            File backup = new File(data, "backup_old_format");
            backup.mkdirs();

            for (File file : data.listFiles())
            {
                if (file.equals(backup) || file.equals(versionFile))
                    continue;

                try
                {
                    Files.move(file, new File(backup, file.getName()));
                }
                catch (IOException ex)
                {
                    FLog.error("Could not backup file: {}", file.getName(), ex);
                }
            }

            convertSuperadmins(new File(backup, "superadmin.yml"));
            convertPermbans(new File(backup, "permban.yml"));

            FLog.info("Conversion complete!");
        }

        if (config.getInt("version", -1) != CURRENT_CONFIG_VERSION)
        {
            config.set("version", CURRENT_CONFIG_VERSION);
            try
            {
                config.save(versionFile);
            }
            catch (IOException ex)
            {
                FLog.error("Could not save version.yml", ex);
            }
        }
    }

    /**
     * Removes some of the unused console variant keys/ranks from ranks.yml.
     */
    public void convertRanksYaml()
    {
        File ranksFile = new File(plugin.getDataFolder(), "ranks.yml");
        if (!ranksFile.exists())
            return;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(ranksFile);
        boolean changed = false;

        for (String key : yaml.getKeys(false))
        {
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null)
                continue;
            if (section.contains("console_variant"))
            {
                section.set("console_variant", null);
                changed = true;
            }
            if (section.contains("player_variant"))
            {
                section.set("player_variant", null);
                changed = true;
            }
        }

        if (yaml.contains("senior_console"))
        {
            yaml.set("senior_console", null);
            changed = true;
        }

        if (!changed)
            return;

        try
        {
            yaml.save(ranksFile);
            FLog.info(
                    "Stripped deprecated console variant fields from ranks.yml.");
        }
        catch (IOException ex)
        {
            FLog.error("Could not save migrated ranks.yml", ex);
        }
    }

    /**
     * Remaps admins still assigned to one of the retired {@code *_console} ranks.
     * <p>
     * Console-only ranks existed so that a shared channel could be given standing of its own. That
     * is now the job of the {@code host_senders:} binding, which the attributed console sender
     * resolves per channel, so an admin holding one is remapped to the equivalent real rank rather
     * than left pointing at a rank id the registry no longer knows.
     */
    public void convertAdminConsoleRanks()
    {
        final String senior = seniorRankId();
        if (plugin.players() == null || senior == null)
            return;

        final List<PlayerData> remapped = plugin.players().getStaff()
                                                .stream()
                                                .filter(admin -> admin.getRankId().filter(id -> id.endsWith(CONSOLE_RANK_SUFFIX)).isPresent())
                                                .toList();

        remapped.forEach(admin ->
        {
            FLog.info("Remapped admin '{}' from retired rank '{}' to '{}'.",
                    admin.getUsername(), admin.getRankId().orElseThrow(), senior);
            admin.setRankId(senior);
            plugin.players().saveData(admin);
        });

        if (!remapped.isEmpty())
            FLog.info("Remapped {} admin(s) from retired console ranks.", remapped.size());
    }

    /**
     * Moves admins off the cosmetic ranks that are now titles.
     * <p>
     * Developer, Owner and Executive were ranks only because the old ladder had no other way to
     * show them: each had to be seated above Senior Admin to display, which handed its holder every
     * permission underneath. They are titles now, so a holder keeps the recognition without the
     * ladder position, and their actual authority drops to the senior rank they were really
     * exercising. Without this pass their stored rank id would no longer resolve and the registry
     * would quietly seat them at the default admin rank instead.
     */
    public void convertCosmeticRankHolders()
    {
        if (plugin.ranks() == null || plugin.titles() == null || plugin.players() == null)
            return;

        final String senior = seniorRankId();
        if (senior == null)
            return;

        final List<PlayerData> converted = plugin.players().getStaff()
                                                 .stream()
                                                 .filter(admin -> admin.getRankId()
                                                                       .filter(id -> plugin.ranks().getCustomRank(id) == null)
                                                                       .filter(plugin.titles()::hasTitle)
                                                                       .isPresent())
                                                 .toList();

        // The staff record is the player's own record now, so the title goes straight onto it.
        converted.forEach(admin ->
        {
            final String retired = admin.getRankId().orElseThrow();
            FLog.info("Moved admin '{}' from retired rank '{}' to the '{}' title, rank '{}'.",
                    admin.getUsername(), retired, retired, senior);
            admin.addTitle(retired);
            admin.setRankId(senior);
            plugin.players().saveData(admin);
        });

        if (!converted.isEmpty())
            FLog.info("Converted {} admin(s) from cosmetic ranks to titles.", converted.size());
    }

    /**
     * The rank a senior admin should hold, resolved as the least privileged rank that is granted
     * senior standing. Derived from {@code ranks.json} rather than named, so a renamed or
     * defined senior rank is still found.
     */
    private String seniorRankId()
    {
        return plugin.ranks() == null
                ? null
                : plugin.ranks().getRegistry()
                        .requiredFor(RankManager.SENIOR_STATUS_NODE)
                        .map(CustomRank::getId)
                        .orElseGet(this::adminDefaultRankId);
    }

    /**
     * The rank a plain admin should hold, taken from whichever rank fills that role.
     */
    private String adminDefaultRankId()
    {
        return plugin.ranks() == null
                ? null
                : plugin.ranks().getRegistry()
                        .byRole(RankRole.ADMIN_DEFAULT)
                        .map(CustomRank::getId)
                        .orElse(null);
    }

    private void convertSuperadmins(File oldFile)
    {
        if (!oldFile.exists() || !oldFile.isFile())
        {
            FLog.warn("No old superadmin list found!");
            return;
        }

        YamlConfiguration oldYaml = YamlConfiguration.loadConfiguration(oldFile);

        ConfigurationSection admins = oldYaml.getConfigurationSection("admins");
        if (admins == null)
        {
            FLog.warn("No admin section in superadmin list!");
            return;
        }

        // Written in the shape the retired admin list used, so the player list's import of
        // admins.json moves these onto player records like any other.
        final JsonObject converted = new JsonObject();
        for (String uuid : admins.getKeys(false))
        {
            ConfigurationSection asec = admins.getConfigurationSection(uuid);
            if (asec == null)
            {
                FLog.warn("Invalid superadmin format for admin {}", uuid);
                continue;
            }

            final String username = asec.getString("last_login_name");
            if (username == null)
                continue;

            final JsonObject admin = new JsonObject();
            admin.addProperty("uuid", uuid);
            admin.addProperty("name", username);
            admin.addProperty("rankId", asec.getBoolean("is_senior_admin") ? seniorRankId() : adminDefaultRankId());
            admin.addProperty("active", asec.getBoolean("is_activated"));
            admin.addProperty("loginMessage", asec.getString("custom_login_message"));
            admin.addProperty("lastLogin", FUtil.dateToString(new Date()));
            final JsonArray ips = new JsonArray();
            asec.getStringList("ips").forEach(ips::add);
            admin.add("ips", ips);
            converted.add(username.toLowerCase(), admin);
        }

        File newJsonFile = new File(plugin.getDataFolder(), "admins.json");
        try (FileWriter writer = new FileWriter(newJsonFile))
        {
            JsonUtil.GSON.toJson(converted, writer);
        }
        catch (IOException ex)
        {
            FLog.error("Could not save converted admin list", ex);
        }

        FLog.info("Converted {} admins", converted.size());
    }

    private void convertPermbans(File oldFile)
    {
        if (!oldFile.exists())
        {
            FLog.warn("No old permban list found!");
            return;
        }

        final YamlConfiguration oldYaml = YamlConfiguration.loadConfiguration(
                oldFile);
        final Map<String, PermBan> converted = new HashMap<>();
        for (String name : oldYaml.getKeys(false))
        {
            final String lowerName = name.toLowerCase().trim();
            final PermBan permban = new PermBan();
            permban.setUsername(lowerName);
            permban.setIps(oldYaml.getStringList(name));
            converted.put(lowerName, permban);
        }

        try (FileWriter writer = new FileWriter(new File(plugin.getDataFolder(),
                PermbanList.CONFIG_FILENAME)))
        {
            JsonUtil.GSON.toJson(converted,
                    new TypeToken<Map<String, PermBan>>()
                    {
                    }.getType(), writer);
            FLog.info("Converted {} permbans", converted.size());
        }
        catch (IOException ex)
        {
            FLog.warn("Could not save converted permban list", ex);
        }
    }

}

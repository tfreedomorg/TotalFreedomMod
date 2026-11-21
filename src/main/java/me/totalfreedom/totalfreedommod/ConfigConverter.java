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

import me.totalfreedom.totalfreedommod.admin.Admin;
import me.totalfreedom.totalfreedommod.admin.AdminList;
import me.totalfreedom.totalfreedommod.banning.PermBan;
import me.totalfreedom.totalfreedommod.banning.PermbanList;
import me.totalfreedom.totalfreedommod.framework.PluginComponent;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.rank.CustomRank;
import me.totalfreedom.totalfreedommod.rank.RankRole;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.JsonUtil;

import com.google.common.collect.Lists;
import com.google.common.io.Files;
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
        {
            convert = true;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(versionFile);
        if (!versionFile.exists())
        {
            try
            {
                versionFile.getParentFile().mkdirs();
                versionFile.createNewFile();
            }
            catch (IOException ex)
            {
                // Ignore
            }
        }

        if (config.getInt("version", -1) < CURRENT_CONFIG_VERSION)
        {
            convert = true;
        }

        if (convert)
        {
            FLog.warning("Converting old configs to new format...");

            File backup = new File(data, "backup_old_format");
            backup.mkdirs();

            for (File file : data.listFiles())
            {
                if (file.equals(backup) || file.equals(versionFile))
                {
                    continue;
                }

                try
                {
                    Files.move(file, new File(backup, file.getName()));
                }
                catch (IOException ex)
                {
                    FLog.severe("Could not backup file: " + file.getName());
                    FLog.severe(ex);
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
                FLog.severe("Could not save version.yml");
                FLog.severe(ex);
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
        {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(ranksFile);
        boolean changed = false;

        for (String key : yaml.getKeys(false))
        {
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null)
            {
                continue;
            }
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
        {
            return;
        }

        try
        {
            yaml.save(ranksFile);
            FLog.info("Stripped deprecated console variant fields from ranks.yml.");
        }
        catch (IOException ex)
        {
            FLog.severe("Could not save migrated ranks.yml");
            FLog.severe(ex);
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
        if (plugin.al == null)
        {
            return;
        }

        final long migrated = plugin.al.getAllAdmins().values()
                .stream()
                .filter(admin -> admin.getRankId() != null)
                .filter(admin -> admin.getRankId().endsWith(CONSOLE_RANK_SUFFIX))
                .peek(admin -> FLog.info(String.format("Remapped admin '%s' from retired rank '%s' to '%s'.",
                        admin.getName(), admin.getRankId(), seniorRankId())))
                .peek(admin -> admin.setRankId(seniorRankId()))
                .count();

        if (migrated > 0)
        {
            plugin.al.saveAsync();
            FLog.info(String.format("Remapped %d admin(s) from retired console ranks.", migrated));
        }
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
        if (plugin.al == null || plugin.rm == null || plugin.tm == null || plugin.pl == null)
        {
            return;
        }

        final String senior = seniorRankId();
        if (senior == null)
        {
            return;
        }

        final long migrated = plugin.al.getAllAdmins().values()
                .stream()
                .filter(admin -> admin.getRankId() != null)
                .filter(admin -> plugin.rm.getCustomRank(admin.getRankId()) == null)
                .filter(admin -> plugin.tm.hasTitle(admin.getRankId()))
                .peek(admin -> grantTitleOffline(admin.getName(), admin.getRankId()))
                .peek(admin -> FLog.info(String.format(
                        "Moved admin '%s' from retired rank '%s' to the '%s' title, rank '%s'.",
                        admin.getName(), admin.getRankId(), admin.getRankId(), senior)))
                .peek(admin -> admin.setRankId(senior))
                .count();

        if (migrated > 0)
        {
            plugin.al.saveAsync();
            FLog.info(String.format("Converted %d admin(s) from cosmetic ranks to titles.", migrated));
        }
    }

    /**
     * Records a title against a player profile without needing them online, since a migration runs
     * at startup when nobody is.
     */
    private void grantTitleOffline(final String username, final String titleId)
    {
        final PlayerData data = plugin.pl.getData(username);

        if (data != null && data.addTitle(titleId))
        {
            plugin.pl.saveData(data);
        }
    }

    /**
     * The rank a senior admin should hold, resolved as the least privileged rank that is granted
     * senior standing. Derived from {@code ranks.json} rather than named, so a renamed or
     * defined senior rank is still found.
     */
    private String seniorRankId()
    {
        return plugin.rm == null
                ? null
                : plugin.rm.getRegistry()
                           .requiredFor(AdminList.SENIOR_STATUS_NODE)
                           .map(CustomRank::getId)
                           .orElseGet(this::adminDefaultRankId);
    }

    /**
     * The rank a plain admin should hold, taken from whichever rank fills that role.
     */
    private String adminDefaultRankId()
    {
        return plugin.rm == null
                ? null
                : plugin.rm.getRegistry()
                           .byRole(RankRole.ADMIN_DEFAULT)
                           .map(CustomRank::getId)
                           .orElse(null);
    }

    private void convertSuperadmins(File oldFile)
    {
        if (!oldFile.exists() || !oldFile.isFile())
        {
            FLog.warning("No old superadmin list found!");
            return;
        }

        YamlConfiguration oldYaml = YamlConfiguration.loadConfiguration(oldFile);

        ConfigurationSection admins = oldYaml.getConfigurationSection("admins");
        if (admins == null)
        {
            FLog.warning("No admin section in superadmin list!");
            return;
        }

        List<Admin> conversions = Lists.newArrayList();
        for (String uuid : admins.getKeys(false))
        {
            ConfigurationSection asec = admins.getConfigurationSection(uuid);
            if (asec == null)
            {
                FLog.warning("Invalid superadmin format for admin: " + uuid);
                continue;
            }

            String username = asec.getString("last_login_name");
            final String rankId = asec.getBoolean("is_senior_admin")
                    ? seniorRankId()
                    : adminDefaultRankId();
            List<String> ips = asec.getStringList("ips");
            String loginMessage = asec.getString("custom_login_message");
            boolean active = asec.getBoolean("is_activated");

            Admin admin = new Admin(username);
            admin.setName(username);
            admin.setRankId(rankId);
            admin.addIps(ips);
            admin.setLoginMessage(loginMessage);
            admin.setActive(active);
            admin.setLastLogin(new Date());
            conversions.add(admin);
        }

        File newJsonFile = new File(plugin.getDataFolder(), AdminList.CONFIG_FILENAME);
        Map<String, Admin> converted = new HashMap<>();
        for (Admin admin : conversions)
        {
            converted.put(admin.getName().toLowerCase(), admin);
        }
        try (FileWriter writer = new FileWriter(newJsonFile))
        {
            JsonUtil.GSON.toJson(converted, new TypeToken<Map<String, Admin>>() {}.getType(), writer);
        }
        catch (IOException ex)
        {
            FLog.severe("Could not save converted admin list");
        }

        FLog.info("Converted " + conversions.size() + " admins");
    }

    private void convertPermbans(File oldFile)
    {
        if (!oldFile.exists())
        {
            FLog.warning("No old permban list found!");
            return;
        }

        final YamlConfiguration oldYaml = YamlConfiguration.loadConfiguration(oldFile);
        final Map<String, PermBan> converted = new HashMap<>();
        for (String name : oldYaml.getKeys(false))
        {
            final String lowerName = name.toLowerCase().trim();
            final PermBan permban = new PermBan();
            permban.setUsername(lowerName);
            permban.setIps(oldYaml.getStringList(name));
            converted.put(lowerName, permban);
        }

        try (FileWriter writer = new FileWriter(new File(plugin.getDataFolder(), PermbanList.CONFIG_FILENAME)))
        {
            JsonUtil.GSON.toJson(converted, new TypeToken<Map<String, PermBan>>() {}.getType(), writer);
            FLog.info("Converted " + converted.size() + " permbans");
        }
        catch (IOException ex)
        {
            FLog.warning("Could not save converted permban list!");
        }
    }

}

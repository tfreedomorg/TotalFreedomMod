package me.totalfreedom.totalfreedommod.admin;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import me.totalfreedom.totalfreedommod.util.ConfigInterfaces.ConfigLoadable;
import me.totalfreedom.totalfreedommod.util.ConfigInterfaces.Validatable;
import me.totalfreedom.totalfreedommod.util.FUtil;

import com.google.common.collect.Lists;

public class Admin implements ConfigLoadable, Validatable
{

    private UUID uuid;
    private String configKey;
    private String name;
    private boolean active = true;

    /**
     * The id of the rank this admin holds, resolved against {@code ranks.json}. Stored as an id
     * rather than as a fixed tier so that defined ranks are first-class: an admin may hold
     * any rank the registry knows, not just one of a handful the plugin ships with.
     */
    private String rankId = null;

    private final List<String> ips = Lists.newArrayList();
    private Date lastLogin = new Date();
    private String loginMessage = null;

    public Admin(Player player)
    {
        this.uuid = player.getUniqueId();
        this.configKey = player.getName().toLowerCase();
        this.name = player.getName();
        this.ips.add(player.getAddress().getAddress().getHostAddress());
    }

    public Admin(String configKey)
    {
        this.configKey = configKey;
    }

    @Override
    public String toString()
    {
        final StringBuilder output = new StringBuilder();

        output.append("Admin: ").append(name).append("\n")
                .append("- IPs: ").append(String.join(", ", ips)).append("\n")
                .append("- Last Login: ").append(FUtil.dateToString(lastLogin)).append("\n")
                .append("- Custom Login Message: ").append(loginMessage).append("\n")
                .append("- Rank: ").append(rankId).append("\n")
                .append("- Is Active: ").append(active);

        return output.toString();
    }

    public void loadFrom(Player player)
    {
        configKey = player.getName().toLowerCase();
        name = player.getName();
        ips.clear();
        ips.add(player.getAddress().getAddress().getHostAddress());
    }

    @Override
    public void loadFrom(ConfigurationSection cs)
    {
        name = cs.getString("username", configKey);
        active = cs.getBoolean("active", true);
        rankId = normaliseRankId(cs.getString("custom_rank"), cs.getString("rank"));

        ips.clear();
        ips.addAll(cs.getStringList("ips"));
        lastLogin = FUtil.stringToDate(cs.getString("last_login"));
        loginMessage = cs.getString("login_message", null);
    }

    /**
     * Folds the two rank fields records used to carry into the single id used now.
     * <p>
     * {@code custom_rank} was the assigned rank and took precedence over {@code rank},
     * which held one of the fixed tiers, so it is preferred here too. A tier name is lowercased to
     * become an id, which is the convention the shipped {@code ranks.json} follows.
     */
    private static String normaliseRankId(final String customRank, final String legacyRank)
    {
        if (customRank != null && !customRank.isBlank())
            return customRank.toLowerCase();

        if (legacyRank != null && !legacyRank.isBlank())
            return legacyRank.toLowerCase();

        return null;
    }

    public boolean hasLoginMessage()
    {
        return loginMessage != null && !loginMessage.isEmpty();
    }

    // Util IP methods
    public void addIp(String ip)
    {
        if (!ips.contains(ip))
        {
            ips.add(ip);
        }
    }

    public void addIps(List<String> ips)
    {
        for (String ip : ips)
        {
            addIp(ip);
        }
    }

    public void removeIp(String ip)
    {
        if (ips.contains(ip))
        {
            ips.remove(ip);
        }
    }

    public void clearIPs()
    {
        ips.clear();
    }

    // Manual getters - Lombok @Getter not processing
    public UUID getUuid()
    {
        return uuid;
    }
    
    public void setUuid(UUID uuid)
    {
        this.uuid = uuid;
    }
    
    public String getRankId()
    {
        return rankId;
    }

    public void setRankId(String rankId)
    {
        this.rankId = rankId == null ? null : rankId.toLowerCase();
    }

    public String getName()
    {
        return name;
    }

    public List<String> getIps()
    {
        return ips;
    }

    public String getConfigKey()
    {
        return configKey;
    }

    public boolean isActive()
    {
        return active;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public Date getLastLogin()
    {
        return lastLogin;
    }

    public void setLastLogin(Date lastLogin)
    {
        this.lastLogin = lastLogin;
    }

    public String getLoginMessage()
    {
        return loginMessage;
    }

    public void setLoginMessage(String loginMessage)
    {
        this.loginMessage = loginMessage;
    }

    public void setActive(boolean active)
    {
        this.active = active;
    }

    @Override
    public boolean isValid()
    {
        // rankId is deliberately not required: an unset id means "whatever fills the admin-default
        // role", which the registry resolves, so an older record without one is still valid.
        return configKey != null
                && name != null
                && !ips.isEmpty()
                && lastLogin != null;
    }
}

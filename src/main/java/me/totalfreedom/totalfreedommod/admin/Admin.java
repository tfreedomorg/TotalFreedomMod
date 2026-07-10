package me.totalfreedom.totalfreedommod.admin;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import me.totalfreedom.totalfreedommod.event.admin.AdminAddEvent;
import me.totalfreedom.totalfreedommod.event.admin.AdminManagementEvent;
import me.totalfreedom.totalfreedommod.event.admin.AdminRankUpdateEvent;
import me.totalfreedom.totalfreedommod.rank.CustomRank;
import me.totalfreedom.totalfreedommod.rank.Displayable;
import me.totalfreedom.totalfreedommod.rank.Rank;
import me.totalfreedom.totalfreedommod.util.ConfigInterfaces.ConfigLoadable;
import me.totalfreedom.totalfreedommod.util.ConfigInterfaces.ConfigSavable;
import me.totalfreedom.totalfreedommod.util.ConfigInterfaces.Validatable;
import me.totalfreedom.totalfreedommod.util.FUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

public class Admin implements ConfigLoadable, ConfigSavable, Validatable
{

    private UUID uuid;
    private String configKey;
    private String name;
    private boolean active = true;
    private Rank rank = Rank.SUPER_ADMIN;
    private String customRankId = null;
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
                .append("- Rank: ").append(rank.getName()).append("\n")
                .append("- Custom Rank: ").append(customRankId != null ? customRankId : "none").append("\n")
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
        rank = Rank.findRank(cs.getString("rank"));

        ips.clear();
        ips.addAll(cs.getStringList("ips"));
        lastLogin = FUtil.stringToDate(cs.getString("last_login"));
        loginMessage = cs.getString("login_message", null);
        customRankId = cs.getString("custom_rank", null);
    }

    @Override
    public void saveTo(ConfigurationSection cs)
    {
        Preconditions.checkArgument(isValid(), "Could not save admin entry: " + name + ". Entry not valid!");
        cs.set("username", name);
        cs.set("active", active);
        cs.set("rank", rank.toString());
        cs.set("ips", Lists.newArrayList(ips));
        cs.set("last_login", FUtil.dateToString(lastLogin));
        cs.set("login_message", loginMessage);
        cs.set("custom_rank", customRankId);
    }

    public boolean isAtLeast(Rank pRank)
    {
        return rank.isAtLeast(pRank);
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
    
    public Rank getRank()
    {
        return rank;
    }

    public String getCustomRankId()
    {
        return customRankId;
    }

    public void setCustomRankId(String customRankId)
    {
        this.customRankId = customRankId;
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

    public void setRank(Rank rank)
    {
        this.rank = rank;
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
        return configKey != null
                && name != null
                && rank != null
                && !ips.isEmpty()
                && lastLogin != null;
    }

    public void fireGenericManagementEvent(CommandSender by, AdminManagementEvent.Action action)
    {
        new AdminManagementEvent(this, by, action).callEvent();
    }

    public void fireAddEvent(CommandSender by, boolean newEntry)
    {
        new AdminAddEvent(this, by, newEntry).callEvent();
    }

    public void fireRankUpdateEvent(CommandSender by, Rank rank, CustomRank customRank)
    {
        new AdminRankUpdateEvent(this, by, rank, customRank).callEvent();
    }
}

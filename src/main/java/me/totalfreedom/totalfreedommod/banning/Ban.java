package me.totalfreedom.totalfreedommod.banning;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.ConfigInterfaces.ConfigLoadable;
import me.totalfreedom.totalfreedommod.util.ConfigInterfaces.Validatable;
import me.totalfreedom.totalfreedommod.util.FUtil;

import com.google.common.collect.Lists;

public class Ban implements ConfigLoadable, Validatable
{

    public static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd \'at\' HH:mm:ss z");

    // UUID support for SQL storage
    private UUID uuid = null;
    private UUID bannedByUuid = null;
    private String username = null;
    private final List<String> ips = Lists.newArrayList();
    private String by = null;
    private String reason = null; // Unformatted, &[0-9,a-f] instead of ChatColor
    private long expiryUnix = -1;

    public UUID getUuid()
    {
        return uuid;
    }

    public void setUuid(UUID uuid)
    {
        this.uuid = uuid;
    }

    public UUID getBannedByUuid()
    {
        return bannedByUuid;
    }

    public void setBannedByUuid(UUID bannedByUuid)
    {
        this.bannedByUuid = bannedByUuid;
    }

    public String getUsername()
    {
        return username;
    }

    public void setUsername(String username)
    {
        this.username = username;
    }

    public List<String> getIps()
    {
        return ips;
    }

    public String getBy()
    {
        return by;
    }

    public void setBy(String by)
    {
        this.by = by;
    }

    public String getReason()
    {
        return reason;
    }

    public void setReason(String reason)
    {
        this.reason = reason;
    }

    public long getExpiryUnix()
    {
        return expiryUnix;
    }

    public void setExpiryUnix(long expiryUnix)
    {
        this.expiryUnix = expiryUnix;
    }

    // SQL repository alias accessors
    public String getBannedBy()
    {
        return by;
    }

    public void setBannedBy(String bannedBy)
    {
        this.by = bannedBy;
    }

    public Date getExpireAt()
    {
        return expiryUnix > 0 ? FUtil.getUnixDate(expiryUnix) : null;
    }
    
    public void setExpireAt(Date expireAt)
    {
        this.expiryUnix = expireAt != null ? FUtil.getUnixTime(expireAt) : -1;
    }
    
    public void setIps(List<String> newIps)
    {
        ips.clear();
        if (newIps != null)
        {
            ips.addAll(newIps);
        }
    }

    public Ban()
    {
    }

    public Ban(String username, String ip, String by, Date expire, String reason)
    {
        this(username,
                new String[]
                {
                    ip
                },
                by,
                expire,
                reason);
    }

    public Ban(String username, String[] ips, String by, Date expire, String reason)
    {
        this.username = username;
        if (ips != null)
        {
            this.ips.addAll(Arrays.asList(ips));
        }
        dedupeIps();
        this.by = by;
        this.expiryUnix = FUtil.getUnixTime(expire);
        this.reason = reason;
    }

    //
    // For player IP
    public static Ban forPlayerIp(Player player, CommandSender by)
    {
        return forPlayerIp(player, by, null, null);
    }

    public static Ban forPlayerIp(Player player, CommandSender by, Date expiry, String reason)
    {
        return new Ban(null, new String[]
        {
            player.getAddress().getAddress().getHostAddress()
        }, by.getName(), expiry, reason);
    }

    public static Ban forPlayerIp(String ip, CommandSender by, Date expiry, String reason)
    {
        return new Ban(null, ip, by.getName(), expiry, reason);
    }

    //
    // For player name
    public static Ban forPlayerName(Player player, CommandSender by, Date expiry, String reason)
    {
        return forPlayerName(player.getName(), by, expiry, reason);
    }

    public static Ban forPlayerName(String player, CommandSender by, Date expiry, String reason)
    {
        return new Ban(player,
                (String[]) null,
                by.getName(),
                expiry,
                reason);
    }

    //
    // For player
    public static Ban forPlayer(Player player, CommandSender by)
    {
        return forPlayerName(player, by, null, null);
    }

    public static Ban forPlayer(Player player, CommandSender by, Date expiry, String reason)
    {
        return new Ban(player.getName(),
                player.getAddress().getAddress().getHostAddress(),
                by.getName(),
                expiry,
                reason);
    }

    public static Ban forPlayerFuzzy(Player player, CommandSender by, Date expiry, String reason)
    {
        return new Ban(player.getName(),
                FUtil.getFuzzyIp(player.getAddress().getAddress().getHostAddress()),
                by.getName(),
                expiry,
                reason);
    }

    public boolean hasUsername()
    {
        return username != null && !username.isEmpty();
    }

    public boolean addIp(String ip)
    {
        return ips.add(ip);
    }

    public boolean removeIp(String ip)
    {
        return ips.remove(ip);
    }

    public boolean hasIps()
    {
        return !ips.isEmpty();
    }

    public boolean hasExpiry()
    {
        return expiryUnix > 0;
    }

    public Date getExpiryDate()
    {
        return FUtil.getUnixDate(expiryUnix);
    }

    public boolean isExpired()
    {
        return hasExpiry() && expiryUnix < FUtil.getUnixTime();
    }

    public Component bakeKickMessage()
    {
        Component message = Component.text("You" + (!hasUsername() ? "r IP address is" : " are")
                + " temporarily banned from this server.", NamedTextColor.GOLD)
                .append(Component.text("\nAppeal at ", NamedTextColor.GOLD))
                .append(Component.text(ConfigEntry.SERVER_BAN_URL.getString(), NamedTextColor.BLUE));

        if (reason != null)
        {
            message = message
                    .append(Component.text("\nReason: ", NamedTextColor.RED))
                    .append(FUtil.colorizeWithLinks(reason, NamedTextColor.GOLD));
        }

        if (by != null)
        {
            message = message
                    .append(Component.text("\nBanned by: ", NamedTextColor.RED))
                    .append(Component.text(by, NamedTextColor.GOLD));
        }

        if (getExpiryUnix() != 0)
        {
            message = message
                    .append(Component.text("\nExpires: ", NamedTextColor.RED))
                    .append(Component.text(DATE_FORMAT.format(FUtil.getUnixDate(expiryUnix)), NamedTextColor.GOLD));
        }

        return message;
    }

    @Override
    public boolean equals(Object object)
    {
        if (object == null)
        {
            return false;
        }

        if (!(object instanceof Ban))
        {
            return false;
        }

        final Ban ban = (Ban) object;
        if (hasIps() != ban.hasIps()
                || hasUsername() != ban.hasUsername())
        {
            return false;
        }

        if (hasIps() && !(getIps().equals(ban.getIps())))
        {
            return false;
        }

        return !(hasUsername() && !(getUsername().equalsIgnoreCase(ban.getUsername())));
    }

    @Override
    public int hashCode()
    {
        int hash = 7;
        hash = 79 * hash + (this.username != null ? this.username.toLowerCase().hashCode() : 0);
        hash = 79 * hash + (this.ips != null ? this.ips.hashCode() : 0);
        return hash;
    }

    @Override
    public void loadFrom(ConfigurationSection cs)
    {
        this.username = cs.getString("username", null);
        this.ips.clear();
        this.ips.addAll(cs.getStringList("ips"));
        this.by = cs.getString("by", null);
        this.reason = cs.getString("reason", null);
        this.expiryUnix = cs.getLong("expiry_unix", 0);
        dedupeIps();
    }

    @Override
    public boolean isValid()
    {
        return username != null || !ips.isEmpty();
    }

    private void dedupeIps()
    {

        Set<String> uniqueIps = new HashSet<>();

        Iterator<String> it = ips.iterator();
        while (it.hasNext())
        {
            if (!uniqueIps.add(it.next()))
            {
                it.remove();
            }
        }

    }
}

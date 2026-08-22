package me.totalfreedom.totalfreedommod.banning;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

/**
 * Model class representing a permanent ban.
 * Used for SQL database storage.
 */
public class PermBan
{
    @Getter
    @Setter
    private UUID uuid;

    @Getter
    @Setter
    private String username;

    @Getter
    @Setter
    private String reason;

    @Getter
    private final List<String> ips = new ArrayList<>();

    public PermBan()
    {
    }

    public PermBan(String username, String reason)
    {
        this.username = username;
        this.reason = reason;
    }

    public PermBan(UUID uuid, String username, String reason)
    {
        this.uuid = uuid;
        this.username = username;
        this.reason = reason;
    }

    public void addIp(String ip)
    {
        if (ip != null && !ips.contains(ip))
        {
            ips.add(ip);
        }
    }

    public void addIps(List<String> newIps)
    {
        if (newIps != null)
        {
            for (String ip : newIps)
            {
                addIp(ip);
            }
        }
    }

    public void setIps(List<String> newIps)
    {
        ips.clear();
        if (newIps != null)
        {
            ips.addAll(newIps);
        }
    }

    public boolean removeIp(String ip)
    {
        return ips.remove(ip);
    }

    public boolean hasIps()
    {
        return !ips.isEmpty();
    }

    public boolean hasUuid()
    {
        return uuid != null;
    }

    public boolean hasUsername()
    {
        return username != null && !username.isEmpty();
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        PermBan permBan = (PermBan) obj;

        if (uuid != null && permBan.uuid != null)
        {
            return uuid.equals(permBan.uuid);
        }

        if (username != null && permBan.username != null)
        {
            return username.equalsIgnoreCase(permBan.username);
        }

        return false;
    }

    @Override
    public int hashCode()
    {
        if (uuid != null)
        {
            return uuid.hashCode();
        }
        return username != null ? username.toLowerCase().hashCode() : 0;
    }
}

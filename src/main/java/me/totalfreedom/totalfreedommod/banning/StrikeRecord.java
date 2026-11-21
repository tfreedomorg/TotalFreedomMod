package me.totalfreedom.totalfreedommod.banning;

import org.bukkit.configuration.ConfigurationSection;

public class StrikeRecord
{

    private final String ip;
    private int count;
    private long lastStrikeUnix;
    private String lastUsername;

    public StrikeRecord(String ip)
    {
        this.ip = ip;
    }

    public String getIp()
    {
        return ip;
    }

    public int getCount()
    {
        return count;
    }

    public void setCount(int count)
    {
        this.count = count;
    }

    public long getLastStrikeUnix()
    {
        return lastStrikeUnix;
    }

    public void setLastStrikeUnix(long lastStrikeUnix)
    {
        this.lastStrikeUnix = lastStrikeUnix;
    }

    public String getLastUsername()
    {
        return lastUsername;
    }

    public void setLastUsername(String lastUsername)
    {
        this.lastUsername = lastUsername;
    }

    public StrikeRecord(String ip, int count, long lastStrikeUnix, String lastUsername)
    {
        this.ip = ip;
        this.count = count;
        this.lastStrikeUnix = lastStrikeUnix;
        this.lastUsername = lastUsername;
    }

    public int effectiveCount(int decayHours)
    {
        if (decayHours <= 0 || lastStrikeUnix <= 0L)
        {
            return count;
        }
        final long ageSeconds = (System.currentTimeMillis() / 1000L) - lastStrikeUnix;
        if (ageSeconds > (long) decayHours * 3600L)
        {
            return 0;
        }
        return count;
    }

    public void loadFrom(ConfigurationSection cs)
    {
        this.count = cs.getInt("count", 0);
        this.lastStrikeUnix = cs.getLong("last_strike_unix", 0L);
        this.lastUsername = cs.getString("last_username", null);
    }

}

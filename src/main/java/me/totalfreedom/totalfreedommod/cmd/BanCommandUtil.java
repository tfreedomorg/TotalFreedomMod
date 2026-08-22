package me.totalfreedom.totalfreedommod.cmd;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.entity.Player;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.banning.Ban;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.util.FUtil;

final class BanCommandUtil
{
    private BanCommandUtil()
    {
    }

    static PlayerData getData(TotalFreedomMod plugin, String name, Player online)
    {
        return online != null ? plugin.pl.getData(online) : plugin.pl.getData(name);
    }

    static String getCanonicalName(String input, Player online, PlayerData data)
    {
        if (online != null)
        {
            return online.getName();
        }
        if (data != null)
        {
            return data.getUsername();
        }
        return input;
    }

    static List<String> getIps(Player online, PlayerData data)
    {
        Set<String> ips = new LinkedHashSet<>();
        if (data != null)
        {
            ips.addAll(data.getIps());
        }
        if (online != null && online.getAddress() != null)
        {
            ips.add(online.getAddress().getAddress().getHostAddress());
        }
        return new ArrayList<>(ips);
    }

    static void addBanIp(Ban ban, String ip)
    {
        ban.addIp(ip);
        if (Boolean.TRUE.equals(ConfigEntry.RANGE_BAN_IPS.getBoolean()))
        {
            ban.addIp(FUtil.getFuzzyIp(ip));
        }
    }

    static void addRangeIpIfEnabled(Ban ban, String ip)
    {
        if (Boolean.TRUE.equals(ConfigEntry.RANGE_BAN_IPS.getBoolean()))
        {
            ban.addIp(FUtil.getFuzzyIp(ip));
        }
    }

    static Ban createFullBan(String name, List<String> ips, org.bukkit.command.CommandSender sender, java.util.Date expiry, String reason)
    {
        Ban ban = Ban.forPlayerName(name, sender, expiry, reason);
        for (String ip : ips)
        {
            addBanIp(ban, ip);
        }
        return ban;
    }

    static Set<Ban> findLinkedBans(TotalFreedomMod plugin, String target, Player online, PlayerData data)
    {
        Set<Ban> bans = new LinkedHashSet<>();
        Ban byName = plugin.bm.getByUsername(getCanonicalName(target, online, data));
        if (byName != null)
        {
            bans.add(byName);
        }
        Ban byIp = plugin.bm.getByIp(target);
        if (byIp != null)
        {
            bans.add(byIp);
        }
        Set<String> ips = new LinkedHashSet<>(getIps(online, data));
        for (Ban ban : new ArrayList<>(bans))
        {
            ips.addAll(ban.getIps());
        }
        for (String ip : ips)
        {
            Ban exact = plugin.bm.getByIp(ip);
            if (exact != null)
            {
                bans.add(exact);
            }
            Ban fuzzy = plugin.bm.getByIp(FUtil.getFuzzyIp(ip));
            if (fuzzy != null)
            {
                bans.add(fuzzy);
            }
        }
        return bans;
    }
}

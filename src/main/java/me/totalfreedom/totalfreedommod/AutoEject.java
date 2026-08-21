package me.totalfreedom.totalfreedommod;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.banning.Ban;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;

public class AutoEject extends FreedomService
{

    public AutoEject(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
    }

    @Override
    protected void onStop()
    {
    }

    public void autoEject(Player player, String kickMessage)
    {
        final Boolean enabled = ConfigEntry.AUTOEJECT_ENABLED.getBoolean();
        if (enabled != null && !enabled)
        {
            FLog.info("AutoEject suppressed (disabled): " + player.getName() + " - " + kickMessage);
            return;
        }

        final String ip = player.getAddress().getAddress().getHostAddress();
        final int strike = plugin.sl.recordStrikeAndGet(ip, player.getName());

        FLog.info("AutoEject -> name: " + player.getName() + " - player ip: " + ip + " - strike: " + strike);

        player.setOp(false);
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().clear();

        final int timeout;
        if (strike >= 3)
        {
            timeout = timeoutMinutes(ConfigEntry.AUTOEJECT_STRIKE_THREE_TIMEOUT, -1);
        }
        else if (strike == 2)
        {
            timeout = timeoutMinutes(ConfigEntry.AUTOEJECT_STRIKE_TWO_TIMEOUT, 10);
        }
        else
        {
            timeout = timeoutMinutes(ConfigEntry.AUTOEJECT_STRIKE_ONE_TIMEOUT, 5);
        }
        applyStrike(player, kickMessage, timeout);
    }

    private int timeoutMinutes(ConfigEntry entry, int fallback)
    {
        final Integer v = entry.getInteger();
        return v == null ? fallback : v;
    }

    private void applyStrike(Player player, String kickMessage, int timeoutMinutes)
    {
        if (timeoutMinutes == 0)
        {
            FUtil.bcastMsg(player.getName() + " has been kicked.", NamedTextColor.RED);
            player.kick(FUtil.colorizeWithLinks(kickMessage));
            return;
        }

        if (timeoutMinutes < 0)
        {
            if (Boolean.TRUE.equals(ConfigEntry.RANGE_BAN_IPS.getBoolean()))
            {
                plugin.bm.addBan(Ban.forPlayerFuzzy(player, Bukkit.getConsoleSender(), null, kickMessage));
            }
            else
            {
                plugin.bm.addBan(Ban.forPlayer(player, Bukkit.getConsoleSender(), null, kickMessage));
            }
            FUtil.bcastMsg(player.getName() + " has been banned.", NamedTextColor.RED);
            player.kick(FUtil.colorizeWithLinks(kickMessage));
            return;
        }

        final Calendar cal = new GregorianCalendar();
        cal.add(Calendar.MINUTE, timeoutMinutes);
        final Date expires = cal.getTime();

        FUtil.bcastMsg(player.getName() + " has been banned for " + timeoutMinutes + " minutes.", NamedTextColor.RED);
        plugin.bm.addBan(Ban.forPlayer(player, Bukkit.getConsoleSender(), expires, kickMessage));
        player.kick(FUtil.colorizeWithLinks(kickMessage));
    }
}

package me.totalfreedom.totalfreedommod;

import me.totalfreedom.totalfreedommod.banning.StrikeList;

import me.totalfreedom.api.FreedomAPI;
import me.totalfreedom.api.IAutoEject;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.function.Supplier;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import me.totalfreedom.totalfreedommod.banning.Ban;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;
import org.jetbrains.annotations.NotNull;

public class AutoEject extends FreedomService implements IAutoEject
{

    public AutoEject(FreedomAPI plugin)
    {
        super(plugin);
    }

    @Override
    public void onStart()
    {
    }

    @Override
    public void onStop()
    {
    }

    @Override
    public void autoEject(Player player, String kickMessage)
    {
        eject(player, "legacy", "triggered a legacy ejector", kickMessage);
    }

    @Override
    public boolean eject(@NotNull Player player,
                      @NotNull String cause,
                      @NotNull String causeDescription,
                      @NotNull String kickMessage)
    {
        // Double-check to make sure that we're not ejecting someone who shouldn't be able to
        if (!canEject(player, cause))
        {
            FLog.info("AutoEject suppressed: {} - {}", player.getName(), kickMessage);
            return false;
        }

        // Fetch IPs and strike number
        final String ip = player.getAddress().getAddress().getHostAddress();
        // FIXME: Why are we doing strikes by usernames? Shouldn't it be UUIDs instead?
        final int strikeNumber = plugin.services().require(StrikeList.class).recordStrikeAndGet(ip, player.getName());

        // Log what just happened
        FLog.info("[AutoEject]"
                + (" name: " + player.getName() + " -")
                + (server.isLoggingIPs() ? (" ip: " + player.getName() + " -") : "")
                + (" strike: " + strikeNumber + " -")
                + (" cause: " + cause));

        // Broadcast it too
        FUtil.bcastMsg("<red><username> <description> and has been <sanction>.",
                Placeholder.unparsed("username", player.getName()),
                Placeholder.parsed("description", causeDescription),
                Placeholder.unparsed("sanction", strikeNumber == 0 ? "kicked"
                        : "banned for " + banDurationByStrikes(strikeNumber) + " minutes"));

        // Perform the sanction
        sanction(player, kickMessage, strikeNumber);
        return true;
    }

    @Override
    public boolean ejectIf(@NotNull Player player,
                        @NotNull String cause,
                        @NotNull String causeDescription,
                        @NotNull String kickMessage,
                        @NotNull Supplier<Boolean> condition)
    {
        if (!canEject(player, cause))
        {
            return false;
        }

        final Boolean ejectCheck = condition.get();
        if (ejectCheck != null && ejectCheck)
        {
            return eject(player, cause, causeDescription, kickMessage);
        }

        return false;
    }

    @Override
    public boolean canEject(@NotNull Player player,
                            @NotNull String cause)
    {
        final Boolean enabled = ConfigEntry.AUTOEJECT_ENABLED.getBoolean();
        return enabled != null && enabled
                && !plugin.ranks().hasPermission(player, "tfm.autoeject.bypass." + cause);
    }

    private void sanction(Player player, String kickMessage, int strike)
    {
        // Should we even be doing this?
        if (server.isPrimaryThread())
        {
            player.setOp(false);
            player.setGameMode(GameMode.SURVIVAL);
            player.getInventory().clear();
        }

        // Ban the player if they have any existing strikes
        if (strike > 0)
        {
            final Calendar cal = new GregorianCalendar();
            cal.add(Calendar.MINUTE, banDurationByStrikes(strike));
            final Date expires = cal.getTime();
            plugin.bans().addBan(Ban.forPlayer(player, Bukkit.getConsoleSender(), expires, kickMessage));
        }

        // Kick them
        player.kick(FUtil.colorizeWithLinks(kickMessage));
    }

    private int banDurationByStrikes(int strike)
    {
        if (strike == 0)
        {
            return 0;
        }

        return timeoutMinutes(switch (strike)
        {
            case 1 -> ConfigEntry.AUTOEJECT_STRIKE_ONE_TIMEOUT;
            case 2 -> ConfigEntry.AUTOEJECT_STRIKE_TWO_TIMEOUT;
            default -> ConfigEntry.AUTOEJECT_STRIKE_THREE_TIMEOUT;
        }, strike * 5);
    }

    private int timeoutMinutes(ConfigEntry entry, int fallback)
    {
        final Integer v = entry.getInteger();
        return v == null ? fallback : v;
    }
}

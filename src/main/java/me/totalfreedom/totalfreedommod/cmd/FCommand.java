package me.totalfreedom.totalfreedommod.cmd;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import me.totalfreedom.totalfreedommod.PluginProvider;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.admin.Admin;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.player.FPlayer;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.util.FTask;
import me.totalfreedom.totalfreedommod.util.FUtil;

/**
 * Base class for command declarations in the new command framework.
 * <p>
 * Subclasses declare their metadata with
 * {@link me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command @Command} /
 * {@link me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission @Permission}
 * and their handlers with
 * {@link me.totalfreedom.totalfreedommod.cmd.internal.annotation.Callback @Callback} +
 * {@link me.totalfreedom.totalfreedommod.cmd.internal.annotation.Subcommand @Subcommand}.
 * <p>
 * Unlike FreedomCommand, there is no per-invocation mutable state. Handlers receive the sender as
 * their first parameter, so every helper takes the sender explicitly.
 */
public abstract class FCommand
{

    public static final Component YOU_ARE_OP = Component.text("You are now op!", NamedTextColor.YELLOW);
    public static final Component YOU_ARE_NOT_OP = Component.text("You are no longer op!", NamedTextColor.YELLOW);
    public static final Component NOT_FROM_CONSOLE = Component.text("This command may not be used from the console.", NamedTextColor.GRAY);
    public static final Component PLAYER_NOT_FOUND = Component.text("Player not found!", NamedTextColor.GRAY);

    // Eventually the above components will be converted to use this same MM string format.
    public static final String ADMIN_PROTECTED_MESSAGE = "<red>This command cannot be used on other admins.";

    protected final TotalFreedomMod plugin()
    {
        return PluginProvider.get();
    }

    protected final Server server()
    {
        return plugin().getServer();
    }

    @Deprecated
    protected boolean isConsole(CommandSender sender)
    {
        return !(sender instanceof Player);
    }

    @Deprecated
    protected void checkConsole(CommandSender sender)
    {
        if (!isConsole(sender))
        {
            throw new CommandFailException("This command can only be used from the console.");
        }
    }

    @Deprecated
    protected void checkPlayer(CommandSender sender)
    {
        if (isConsole(sender))
        {
            throw new CommandFailException("This command can only be used by players.");
        }
    }

    protected void adminAction(CommandSender sender, String action, Object... refs)
    {
        FUtil.adminAction(sender, MessageUtils.parse(String.format(action, refs)));
    }

    protected void adminAction(CommandSender sender, String action, TagResolver... resolvers)
    {
        FUtil.adminAction(sender, MessageUtils.parse(action, resolvers));
    }

    protected ConfigEntry getConfigEntry(final String value)
    {
        return ConfigEntry.valueOf(value.toUpperCase(Locale.ROOT));
    }

    @Deprecated
    protected boolean noPerms()
    {
        throw new CommandFailException("You do not have permission to use this command.");
    }

    protected Player getPlayer(String name)
    {
        if (name == null || name.isEmpty())
        {
            throw new InvalidParameterException("String cannot be Null-or-Empty");
        }

        final Player player = server().getPlayerExact(name);
        if (player != null)
        {
            return player;
        }

        name = name.toLowerCase();
        for (Player p : server().getOnlinePlayers())
        {
            if (p.getName().toLowerCase().equals(name))
            {
                return p;
            }
        }

        final List<Player> matches = new ArrayList<>();
        for (Player p : server().getOnlinePlayers())
        {
            if (p.getName().toLowerCase().startsWith(name))
            {
                matches.add(p);
            }
        }

        if (matches.size() == 1)
        {
            return matches.get(0);
        }

        throw new CommandFailException("That player cannot be found!");
    }

    protected void msg(final CommandSender sender, final String message, TagResolver... refs)
    {
        MessageUtils.send(sender, message, refs);
    }

    protected void kickPlayer(final Player player, final String message)
    {
        player.kick(MessageUtils.parse(message));
    }

    protected void kickPlayer(final Player player, final String message, final TagResolver... refs)
    {
        player.kick(MessageUtils.parse(message, refs));
    }

    protected void smitePlayer(final Player player)
    {
        final Location targetPos = player.getLocation();
        final World world = player.getWorld();
        for (int x = -1; x <= 1; x++)
        {
            for (int z = -1; z <= 1; z++)
            {
                final Location strike_pos = new Location(world, targetPos.getBlockX() + x, targetPos.getBlockY(), targetPos.getBlockZ() + z);
                world.strikeLightning(strike_pos);
            }
        }
    }

    protected boolean isAdmin(CommandSender sender)
    {
        return plugin().al.isAdmin(sender);
    }

    /**
     * Whether {@code target} is an admin, and so out of reach of a disciplinary or destructive
     * command. Tells {@code sender} why when it is, so a caller only has to return.
     */
    protected boolean isProtectedAdmin(final CommandSender sender, final Player target)
    {
        if (target == null || !plugin().al.isAdmin(target))
            return false;

        msg(sender, ADMIN_PROTECTED_MESSAGE);
        return true;
    }

    /**
     * The same test for the commands whose target is a bare name and may be offline or unknown.
     */
    protected boolean isProtectedAdminByName(final CommandSender sender, final String targetName)
    {
        if (targetName == null || targetName.isEmpty())
            return false;

        final Player online = server().getPlayerExact(targetName);
        if (online != null)
            return isProtectedAdmin(sender, online);

        final Admin listed = plugin().al.getEntryByName(targetName);
        if (listed == null || !listed.isActive())
            return false;

        msg(sender, ADMIN_PROTECTED_MESSAGE);
        return true;
    }

    /**
     * The same test for an address, so that an IP ban cannot reach an admin through one of theirs.
     */
    protected boolean isProtectedAdminByIp(final CommandSender sender, final String ip)
    {
        if (ip == null || ip.isEmpty())
            return false;

        final Admin listed = plugin().al.getEntryByIpFuzzy(ip);
        if (listed == null || !listed.isActive())
            return false;

        msg(sender, ADMIN_PROTECTED_MESSAGE);
        return true;
    }

    protected Admin getAdmin(CommandSender sender)
    {
        return plugin().al.getAdmin(sender);
    }

    protected Admin getAdmin(Player player)
    {
        return plugin().al.getAdmin(player);
    }

    protected PlayerData getData(Player player)
    {
        return plugin().pl.getData(player);
    }

    protected BukkitTask sync(final Runnable task, long delayInTicks)
    {
        // Guarded so a throwing command task is reported against the command name rather than as an
        // anonymous "Task #<id> generated an exception" line.
        final Runnable guarded = FTask.guard(getClass().getSimpleName() + "/sync", task);

        if (delayInTicks == 0L)
            return server().getScheduler().runTask(plugin(), guarded);

        return server().getScheduler().runTaskLater(plugin(), guarded, delayInTicks); // naturally runs in ticks
    }

    protected ScheduledTask async(final Consumer<ScheduledTask> consumer, final long delayInTicks)
    {
        final Consumer<ScheduledTask> guarded = FTask.guardAsync(getClass().getSimpleName() + "/async", consumer);

        if (delayInTicks == 0L)
            return server().getAsyncScheduler().runNow(plugin(), guarded);

        return server().getAsyncScheduler().runDelayed(plugin(), guarded, delayInTicks * 50L, TimeUnit.MILLISECONDS); // for some reason does not, and allows time unit selection, so we handle that appropriately to keep the expected usage.
    }

    protected FPlayer fplayer(final Player player)
    {
        return plugin().pl.getPlayer(player);
    }
}

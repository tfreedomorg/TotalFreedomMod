package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.FuzzyMatch;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.world.CustomWorld;
import me.totalfreedom.totalfreedommod.world.WorldTime;
import me.totalfreedom.totalfreedommod.world.WorldWeather;

@Command(name = "manageworld",
         description = "Manage a TFM-generated world's guest list, weather, time, or wipe flag.",
         usage = "/manageworld <guest <add|remove|purge|list> <world> [player]|time <world> <time>|weather <world> <weather>|wipe <world>>",
         aliases = {"mw", "worlds", "tfw", "tfworlds"})
@Permission(permission = "tfm.world.manage") // default source is BOTH
public class Command_manageworld extends FCommand
{
    private List<String> worldCandidates(String partial)
    {
        return FuzzyMatch.filter(plugin().gs.available().stream().sorted().toList(), partial);
    }

    /** Null (with a message already sent) if {@code world} has no loaded profile. */
    private CustomWorld requireWorld(CommandSender sender, String world)
    {
        if (!plugin().gs.available().contains(world))
        {
            msg(sender, "<red>No such managed world: <world>.", Placeholder.unparsed("world", world));
            return null;
        }
        return plugin().wm.get(world);
    }

    @Completer(value = "guest add", position = 0)
    public List<String> completeGuestAddWorld(CommandSender sender, String partial)
    {
        return worldCandidates(partial);
    }

    @Completer(value = "guest add", position = 1)
    public List<String> completeGuestAddPlayer(CommandSender sender, String partial)
    {
        return NameCandidates.online(server(), partial);
    }

    @Completer(value = "guest remove", position = 0)
    public List<String> completeGuestRemoveWorld(CommandSender sender, String partial)
    {
        return worldCandidates(partial);
    }

    @Completer(value = "guest remove", position = 1)
    public List<String> completeGuestRemovePlayer(CommandSender sender, String partial)
    {
        return NameCandidates.online(server(), partial);
    }

    @Completer(value = "guest list", position = 0)
    public List<String> completeGuestListWorld(CommandSender sender, String partial)
    {
        return worldCandidates(partial);
    }

    @Completer(value = "guest purge", position = 0)
    public List<String> completeGuestPurgeWorld(CommandSender sender, String partial)
    {
        return worldCandidates(partial);
    }

    @Completer(value = "time", position = 0)
    public List<String> completeTimeWorld(CommandSender sender, String partial)
    {
        return worldCandidates(partial);
    }

    @Completer(value = "weather", position = 0)
    public List<String> completeWeatherWorld(CommandSender sender, String partial)
    {
        return worldCandidates(partial);
    }

    @Completer(value = "wipe", position = 0)
    public List<String> completeWipeWorld(CommandSender sender, String partial)
    {
        return worldCandidates(partial);
    }

    @Callback
    @Subcommand("guest add")
    @Permission(source = SourceType.ONLY_IN_GAME, permission = "tfm.world.manage")
    public void addGuest(Player sender, String world, Player target)
    {
        CustomWorld customWorld = requireWorld(sender, world);
        if (customWorld == null)
            return;

        if (customWorld.addGuest(target, sender))
        {
            adminAction(sender, "<aqua>Guest added to <world>: <target>",
                    Placeholder.unparsed("world", world),
                    Placeholder.unparsed("target", target.getName()));
            return;
        }
        msg(sender, "<red>Could not add player to guest list.");
    }

    @Callback
    @Subcommand("guest list")
    @Permission(permission = "tfm.world.manage")
    public void listGuests(CommandSender sender, String world)
    {
        CustomWorld customWorld = requireWorld(sender, world);
        if (customWorld == null)
            return;

        if (customWorld.hasGuests())
        {
            msg(sender, "<aqua><world> guest list: <list>",
                    Placeholder.unparsed("world", world),
                    Placeholder.parsed("list", customWorld.guestListToString()));
            return;
        }

        msg(sender, "<red>There are no guests for <world>.", Placeholder.unparsed("world", world));
    }

    @Callback
    @Subcommand("guest remove")
    @Permission(source = SourceType.ONLY_IN_GAME, permission = "tfm.world.manage")
    public void removeGuest(CommandSender sender, String world, Player target)
    {
        CustomWorld customWorld = requireWorld(sender, world);
        if (customWorld == null)
            return;

        if (customWorld.removeGuest(target))
        {
            adminAction(sender, "<aqua>Guest removed from <world>: <target>",
                    Placeholder.unparsed("world", world),
                    Placeholder.unparsed("target", target.getName()));
        }
        else
        {
            msg(sender, "<gray>Can't find guest entry for <target> in <world>.",
                    Placeholder.unparsed("target", target.getName()),
                    Placeholder.unparsed("world", world));
        }
    }

    @Callback
    @Subcommand("guest purge")
    @Permission(permission = "tfm.world.manage")
    public void purgeGuests(CommandSender sender, String world)
    {
        CustomWorld customWorld = requireWorld(sender, world);
        if (customWorld == null)
            return;

        customWorld.purgeGuestList();
        adminAction(sender, "<aqua><world> guest list purged.", Placeholder.unparsed("world", world));
    }

    @Callback
    @Subcommand("time")
    @Permission(permission = "tfm.world.manage")
    public void setTime(CommandSender sender, String world, WorldTime time)
    {
        CustomWorld customWorld = requireWorld(sender, world);
        if (customWorld == null)
            return;

        customWorld.setTimeOfDay(time);
        msg(sender, "<gray><world> time set to <time>.",
                Placeholder.unparsed("world", world),
                Placeholder.unparsed("time", time.name()));
    }

    @Callback
    @Subcommand("weather")
    @Permission(permission = "tfm.world.manage")
    public void setWeather(CommandSender sender, String world, WorldWeather weather)
    {
        CustomWorld customWorld = requireWorld(sender, world);
        if (customWorld == null)
            return;

        customWorld.setWeatherMode(weather);
        msg(sender, "<aqua><world> weather set to <weather>.",
                Placeholder.unparsed("world", world),
                Placeholder.unparsed("weather", weather.name()));
    }

    @Callback
    @Subcommand("wipe")
    @Permission(source = SourceType.ONLY_CONSOLE, permission = "tfm.admin.senior.wipeworld")
    public void wipe(CommandSender sender, String world)
    {
        if (!plugin().gs.available().contains(world))
        {
            msg(sender, "<red>No such managed world: <world>.", Placeholder.unparsed("world", world));
            return;
        }

        plugin().sf.setSavedFlag("do_wipe_" + world, true);

        MessageUtils.broadcast("<gray>Server is going offline for a wipe of <world>.", Placeholder.unparsed("world", world));

        server().getOnlinePlayers().forEach(player ->
                kickPlayer(player, "Server is going offline for a world wipe, come back in a few minutes."));

        server().shutdown();
    }
}

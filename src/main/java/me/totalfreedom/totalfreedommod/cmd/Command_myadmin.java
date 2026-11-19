package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;
import java.util.stream.Stream;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;

import me.totalfreedom.api.cmd.FCommand;
import me.totalfreedom.api.cmd.SourceType;
import me.totalfreedom.api.cmd.annotation.*;
import me.totalfreedom.totalfreedommod.player.PlayerData;

@Permission(source = SourceType.ONLY_IN_GAME, permission = "tfm.admin.myadmin")
@Command(name = "myadmin", description = "Manage my admin entry", usage = "/myadmin <clearips | clearip <ip> | setlogin <message> | clearlogin | display <title | rank | auto>>")
public class Command_myadmin extends FCommand
{
    @Callback
    @Subcommand("setlogin")
    public void setLoginMessage(Player player, @Greedy String message)
    {
        final PlayerData entry = getData(player);
        //removed conversion method. we want to enforce <tags> because tags are peak.

        entry.setLoginMessage(message);
        plugin().players().saveData(entry);

        msg(
            player, // removing the tabs in the string block because they are also processed by MessageUtils.
            """
            Your login message is now:
            > <login>\
            """,
            MessageUtils.component("login", plugin().ranks().formatLoginMessage(player))
        );
    }

    /**
     * Forgets every trusted address except the one this session is on. Only a verified session
     * holds an admin rank to run this with, so that address is already trusted.
     */
    @Callback
    @Subcommand("clearips")
    public void clearIps(Player player)
    {
        final PlayerData entry = getData(player);
        final int forgotten = entry.getTrustedIps().size() - 1;

        entry.setTrustedIps(List.of(player.getAddress().getAddress().getHostAddress()));
        plugin().players().saveData(entry);
        msg(player, "<gray>Forgot <count> other trusted address(es). Only this one is trusted now.",
                Formatter.number("count", Math.max(forgotten, 0)));
    }

    @Callback
    @Subcommand("clearlogin")
    public void clearLogin(Player player)
    {
        final PlayerData entry = getData(player);

        entry.setLoginMessage(null);
        plugin().players().saveData(entry);
        msg(player, "<gray>Your login message has been removed.");
    }

    @Callback
    @Subcommand("display")
    public void display(Player player, String choice)
    {
        final String pick = choice.toLowerCase();

        switch (pick)
        {
            case "auto" -> plugin().titles().setDisplayedTitle(player, null);
            case "rank" -> plugin().titles().setDisplayedTitle(player, PlayerData.DISPLAY_RANK);
            default ->
            {
                if (!plugin().titles().getHeldTitleIds(player).contains(pick))
                {
                    msg(player, "<red>You don't hold a title called <title>.", MessageUtils.unparsed("title", choice));
                    return;
                }

                plugin().titles().setDisplayedTitle(player, pick);
            }
        }

        msg(player, "<gray>You now appear as <display>.", MessageUtils.component("display", plugin().ranks().getDisplay(player).getColoredName()));
    }

    @Completer(value = "display", position = 0)
    public List<String> completeDisplay(Player player, String partial)
    {
        final String prefix = partial == null ? "" : partial.toLowerCase();

        return Stream.concat(Stream.of("auto", "rank"), plugin().titles().getHeldTitleIds(player).stream())
                     .filter(option -> option.startsWith(prefix))
                     .sorted()
                     .toList();
    }
}

package me.totalfreedom.totalfreedommod.cmd;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.dispatch.RemoteDispatchContext;
import me.totalfreedom.totalfreedommod.display.Displayable;
import me.totalfreedom.totalfreedommod.util.PlayerListUtil;

@Command(name = "list", description = "Lists the real names of all online players.", usage = "/list [-a | -i | -f]", aliases = {"who"})
@Permission(permission = "tfm.player.list")
public class Command_list extends FCommand
{
    private enum ListFilter
    {
        PLAYERS, ADMINS, FAMOUS_PLAYERS, IMPOSTORS
    }

    @Callback
    public void list(CommandSender sender, @Switch("a") boolean admins, @Switch("i") boolean impostors, @Switch("f") boolean famous)
    {
        if (!(sender instanceof Player) && !RemoteDispatchContext.isActive())
        {
            msg(sender, PlayerListUtil.buildPlainList());
            return;
        }

        ListFilter filter;
        if (admins)
        {
            filter = ListFilter.ADMINS;
        }
        else if (impostors)
        {
            filter = ListFilter.IMPOSTORS;
        }
        else if (famous)
        {
            filter = ListFilter.FAMOUS_PLAYERS;
        }
        else
        {
            filter = ListFilter.PLAYERS;
        }

        final List<Player> admList = new ArrayList<>();
        final List<Player> players = new ArrayList<>();
        final List<Player> filteredPlayers = new ArrayList<>();
        final List<Player> visiblePlayers = plugin().vs.visiblePlayersFor(sender);

        for (Player player : visiblePlayers)
        {
            if (filter == ListFilter.PLAYERS)
            {
                if (plugin().al.isAdmin(player))
                {
                    admList.add(player);
                }
                else
                {
                    players.add(player);
                }
                continue;
            }

            if (filter == ListFilter.ADMINS && plugin().al.isAdmin(player))
            {
                filteredPlayers.add(player);
                continue;
            }

            if (filter == ListFilter.IMPOSTORS && plugin().al.isAdminImpostor(player))
            {
                filteredPlayers.add(player);
                continue;
            }

            if (filter == ListFilter.FAMOUS_PLAYERS
                    && ConfigEntry.FAMOUS_PLAYERS.getList().contains(player.getName().toLowerCase()))
            {
                filteredPlayers.add(player);
            }
        }

        Comparator<Player> byName = Comparator.comparing(p -> p.getName(), String.CASE_INSENSITIVE_ORDER);
        List.of(admList, players, filteredPlayers).forEach(list -> list.sort(byName));

        msg(
            sender,
            "<dark_gray>----- <aqua><bold><title></bold></aqua> <dark_gray>-----",
            Placeholder.unparsed("title", getHeader(filter))
        );
        msg(
            sender,
            "<gray>Online: <green><online><dark_gray>/<green><max>",
            Formatter.number("online", visiblePlayers.size()),
            Formatter.number("max", server().getMaxPlayers())
        );

        if (filter == ListFilter.PLAYERS)
        {
            sendPlayerLine(sender, "Admins", admList, NamedTextColor.RED);
            sendPlayerLine(sender, "Players", players, NamedTextColor.AQUA);
        }
        else
        {
            sendPlayerLine(sender, getCategoryName(filter), filteredPlayers, NamedTextColor.AQUA);
        }

        msg(sender, "<dark_gray>-------------------------");
    }

    private void sendPlayerLine(CommandSender sender, String title, List<Player> players, NamedTextColor titleColor)
    {
        Component titleComponent = Component.text(title + " (" + players.size() + "): ", titleColor);

        if (players.isEmpty())
        {
            msg(sender, "<title><gray>None", MessageUtils.component("title", titleComponent));
            return;
        }

        List<Component> segments = players.stream()
            .map(player ->
            {
                Displayable display = plugin().rm.getDisplay(player);
                return MessageUtils.parse(
                    "<tag> <name>",
                    MessageUtils.component("tag", display.getColoredTag()),
                    MessageUtils.component("name", Component.text(player.getName(), display.getColor()))
                );
            })
            .toList();

        msg(
            sender,
            "<title><names>",
            MessageUtils.component("title", titleComponent),
            MessageUtils.joinedComponents("names", segments)
        );
    }

    private String getHeader(ListFilter filter)
    {
        return switch (filter)
        {
            case ADMINS -> "Online Admins";
            case IMPOSTORS -> "Online Impostors";
            case FAMOUS_PLAYERS -> "Online Famous Players";
            case PLAYERS -> "Online Players";
        };
    }

    private String getCategoryName(ListFilter filter)
    {
        return switch (filter)
        {
            case ADMINS -> "Admins";
            case IMPOSTORS -> "Impostors";
            case FAMOUS_PLAYERS -> "Famous Players";
            case PLAYERS -> "Players";
        };
    }
}

package me.totalfreedom.totalfreedommod.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.dispatch.RemoteDispatchContext;
import me.totalfreedom.totalfreedommod.rank.Displayable;
import me.totalfreedom.totalfreedommod.rank.Rank;
import me.totalfreedom.totalfreedommod.util.AdventureUtil;
import me.totalfreedom.totalfreedommod.util.PlayerListUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

@CommandPermissions(level = Rank.IMPOSTOR, source = SourceType.BOTH, permission = "tfm.player.list")
@CommandParameters(description = "Lists the real names of all online players.", usage = "/<command> [-a | -i | -f]", aliases = "who")
public class Command_list extends FreedomCommand
{

    private enum ListFilter
    {

        PLAYERS,
        ADMINS,
        FAMOUS_PLAYERS,
        IMPOSTORS
    }

    @Override
    public boolean run(CommandSender sender, Player playerSender, Command cmd, String commandLabel, String[] args, boolean senderIsConsole)
    {
        if (args.length > 1)
        {
            return false;
        }

        if (senderIsConsole && !RemoteDispatchContext.isActive())
        {
            msg(PlayerListUtil.buildPlainList(), NamedTextColor.WHITE);
            return true;
        }

        final ListFilter listFilter;

        if (args.length == 1)
        {
            switch (args[0].toLowerCase())
            {
                case "-a":
                    listFilter = ListFilter.ADMINS;
                    break;

                case "-i":
                    listFilter = ListFilter.IMPOSTORS;
                    break;

                case "-f":
                    listFilter = ListFilter.FAMOUS_PLAYERS;
                    break;

                default:
                    return false;
            }
        }
        else
        {
            listFilter = ListFilter.PLAYERS;
        }

        final List<Player> admins = new ArrayList<>();
        final List<Player> players = new ArrayList<>();
        final List<Player> filteredPlayers = new ArrayList<>();

        final boolean showVanished = senderIsConsole || plugin.al.isAdmin(sender);
        int onlineCount = 0;

        for (Player player : server.getOnlinePlayers())
        {
            if (!showVanished && plugin.vm.isVanished(player))
            {
                continue;
            }

            onlineCount++;

            if (listFilter == ListFilter.PLAYERS)
            {
                if (plugin.al.isAdmin(player))
                {
                    admins.add(player);
                }
                else
                {
                    players.add(player);
                }

                continue;
            }

            if (listFilter == ListFilter.ADMINS && plugin.al.isAdmin(player))
            {
                filteredPlayers.add(player);
                continue;
            }

            if (listFilter == ListFilter.IMPOSTORS && plugin.al.isAdminImpostor(player))
            {
                filteredPlayers.add(player);
                continue;
            }

            if (listFilter == ListFilter.FAMOUS_PLAYERS
                    && ConfigEntry.FAMOUS_PLAYERS.getList().contains(player.getName().toLowerCase()))
            {
                filteredPlayers.add(player);
            }
        }

        admins.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        players.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        filteredPlayers.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));

        Component header = Component.text("----- ", NamedTextColor.DARK_GRAY)
                .append(Component.text(getHeader(listFilter), NamedTextColor.AQUA)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text(" -----", NamedTextColor.DARK_GRAY));

        Component statistics = Component.text("Online: ", NamedTextColor.GRAY)
                .append(Component.text(String.valueOf(onlineCount), NamedTextColor.GREEN))
                .append(Component.text("/", NamedTextColor.DARK_GRAY))
                .append(Component.text(String.valueOf(server.getMaxPlayers()), NamedTextColor.GREEN));

        sendFormatted(sender, header, senderIsConsole);
        sendFormatted(sender, statistics, senderIsConsole);

        if (listFilter == ListFilter.PLAYERS)
        {
            sendFormatted(sender, buildPlayerLine("Admins", admins, NamedTextColor.RED), senderIsConsole);
            sendFormatted(sender, buildPlayerLine("Players", players, NamedTextColor.AQUA), senderIsConsole);
        }
        else
        {
            sendFormatted(sender, buildPlayerLine(getCategoryName(listFilter), filteredPlayers, NamedTextColor.AQUA), senderIsConsole);
        }

        sendFormatted(sender, Component.text("-------------------------", NamedTextColor.DARK_GRAY), senderIsConsole);

        return true;
    }

    private Component buildPlayerLine(String title, List<Player> players, NamedTextColor titleColor)
    {
        Component line = Component.text(title + " (" + players.size() + "): ", titleColor);

        if (players.isEmpty())
        {
            return line.append(Component.text("None", NamedTextColor.GRAY));
        }

        for (int i = 0; i < players.size(); i++)
        {
            final Player player = players.get(i);
            final Displayable display = plugin.rm.getDisplay(player);

            if (i > 0)
            {
                line = line.append(Component.text(", ", NamedTextColor.DARK_GRAY));
            }

            line = line.append(display.getColoredTag())
                    .append(Component.space())
                    .append(Component.text(player.getName(), display.getColor()));
        }

        return line;
    }

    private void sendFormatted(CommandSender sender, Component message, boolean senderIsConsole)
    {
        if (senderIsConsole)
        {
            sender.sendMessage(AdventureUtil.stripColor(AdventureUtil.componentToLegacy(message)));
            return;
        }

        sender.sendMessage(message);
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

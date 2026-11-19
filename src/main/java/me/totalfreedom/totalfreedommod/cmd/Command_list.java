package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;
import java.util.function.Predicate;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.api.cmd.FCommand;
import me.totalfreedom.api.cmd.annotation.*;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.dispatch.RemoteDispatchContext;
import me.totalfreedom.api.display.Displayable;
import me.totalfreedom.totalfreedommod.rank.CustomRank;
import me.totalfreedom.totalfreedommod.util.PlayerListUtil;

@Command(name = "list", description = "Lists the real names of all online players.", usage = "/list [-a | -i | -f]", aliases = {"who"})
@Permission(permission = "tfm.player.list")
public class Command_list extends FCommand
{
    @Callback
    public void list(CommandSender sender, @Switch("a") boolean admins, @Switch("i") boolean impostors, @Switch("f") boolean famous)
    {
        if (!(sender instanceof Player) && !RemoteDispatchContext.isActive())
        {
            msg(sender, PlayerListUtil.buildPlainList());
            return;
        }

        final List<Player> visible = plugin().vanish().visiblePlayersFor(sender);

        msg(
            sender,
            "<dark_gray>----- <aqua><bold><title></bold></aqua> <dark_gray>-----",
            Placeholder.unparsed("title", header(admins, impostors, famous))
        );
        msg(
            sender,
            "<gray>Online: <green><online><dark_gray>/<green><max>",
            Formatter.number("online", visible.size()),
            Formatter.number("max", server().getMaxPlayers())
        );

        if (admins)
            sendPlayerLine(sender, "Admins", visible, plugin().ranks()::isAdmin, NamedTextColor.AQUA);
        else if (impostors)
            sendPlayerLine(sender, "Impostors", visible, plugin().players()::isImpostor, NamedTextColor.AQUA);
        else if (famous)
            sendPlayerLine(sender, "Famous Players", visible, PlayerListUtil::isFamous, NamedTextColor.AQUA);
        else
        {
            sendPlayerLine(sender, "Admins", visible, plugin().ranks()::isAdmin, NamedTextColor.RED);
            sendPlayerLine(sender, "Players", visible, Predicate.not(plugin().ranks()::isAdmin), NamedTextColor.AQUA);
        }

        msg(sender, "<dark_gray>-------------------------");
    }

    /**
     * Sends one labelled line of the players matching {@code membership}.
     * <p>
     * Membership follows who the player is. Their displayed title or rank only affects the tag.
     */
    private void sendPlayerLine(CommandSender sender, String label, List<Player> visible, Predicate<Player> membership, NamedTextColor labelColor)
    {
        final List<Player> members = visible.stream()
                                            .filter(membership)
                                            .sorted(plugin().ranks().byDisplayOrder())
                                            .toList();

        final Component labelComponent = Component.text(String.format("%s (%d): ", label, members.size()), labelColor);

        if (members.isEmpty())
        {
            msg(sender, "<label><gray>None", MessageUtils.component("label", labelComponent));
            return;
        }

        final List<Component> segments = members.stream()
                                                .map(this::entry)
                                                .toList();

        msg(
            sender,
            "<label><names>",
            MessageUtils.component("label", labelComponent),
            MessageUtils.joinedComponents("names", segments)
        );
    }

    /**
     * A player's displayed tag followed by their name in the colour of their rank.
     */
    private Component entry(Player player)
    {
        final Displayable display = plugin().ranks().getDisplay(player);
        final CustomRank rank = plugin().ranks().getEffectiveRank(player);
        final NamedTextColor nameColor = rank == null ? NamedTextColor.WHITE : rank.getColor();

        return MessageUtils.parse(
            "<tag> <name>",
            MessageUtils.component("tag", display.getColoredTag()),
            MessageUtils.component("name", Component.text(player.getName(), nameColor))
        );
    }

    private String header(boolean admins, boolean impostors, boolean famous)
    {
        if (admins)
            return "Online Admins";

        if (impostors)
            return "Online Impostors";

        return famous ? "Online Famous Players" : "Online Players";
    }
}

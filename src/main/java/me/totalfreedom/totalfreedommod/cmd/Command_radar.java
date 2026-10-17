package me.totalfreedom.totalfreedommod.cmd;

import java.util.Comparator;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Callback;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;

@Command(name = "radar", description = "Shows nearby people sorted by distance.", usage = "/radar [radius]")
@Permission(permission = "tfm.player.radar", source = SourceType.ONLY_IN_GAME)
public class Command_radar extends FCommand
{
    @Callback
    public void showNearbyPlayers(Player sender)
    {
        showNearbyPlayersInRange(sender, 200);
    }

    @Callback
    public void showNearbyPlayersInRange(Player sender, Integer limit)
    {
        final int radius = Math.clamp(limit, 1, 200);
        final Location center = sender.getLocation();
        final List<Player> nearbyPlayers = center.getWorld()
                                                 .getPlayers()
                                                 .stream()
                                                 .filter(player -> !player.equals(sender))
                                                 .filter(player -> player.getLocation().distance(center) <= radius)
                                                 .sorted(Comparator.comparingDouble(player -> player.getLocation().distance(center)))
                                                 .toList();

        if (nearbyPlayers.isEmpty())
        {
            msg(sender,"<yellow>You are the only player in this world. (<green>Forever alone...<yellow>)"); //lol
            return;
        }

        msg(
            sender,
            "<yellow>People nearby in <world>:<newline><list:'<newline>'>",
            Placeholder.unparsed("world", center.getWorld().getName()),
            Formatter.joining(
                        "list",
                            nearbyPlayers.stream()
                                            .map(player -> MessageUtils.parse(
                                                                            "<player> - <distance> blocks",
                                                                            Placeholder.unparsed("player", player.getName()),
                                                                            Formatter.number("distance", Math.round(player.getLocation().distance(center)))
                                                                    )
                                            )
                                            .toList()
            ));
    }
}
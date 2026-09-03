package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;

import me.totalfreedom.api.cmd.FCommand;
import me.totalfreedom.api.cmd.SourceType;
import me.totalfreedom.api.cmd.annotation.Callback;
import me.totalfreedom.api.cmd.annotation.Command;
import me.totalfreedom.api.cmd.annotation.Permission;
import me.totalfreedom.totalfreedommod.blocking.packet.CrashPacketService;
import me.totalfreedom.totalfreedommod.util.FUtil;

@Command(name = "expel", description = "Push people away from you.", usage = "/expel [radius] [strength]")
@Permission(permission = "tfm.fun.expel", source = SourceType.ONLY_IN_GAME)
public class Command_expel extends FCommand
{
    /**
     * How long move_guard is told to ignore a pushed player. The launch decays to walking pace well inside
     * this; without it the 100 blocks/second ceiling ejects anybody pushed by /expel
     */
    private static final long PUSH_GRACE_MS = 3000L;

    @Callback
    public void expel(Player sender)
    {
        expelWithRadius(sender, 20.0, 5.0);
    }

    @Callback
    public void expelWithRadius(Player sender, Double radius)
    {
        expelWithRadius(sender, radius, 5.0);
    }

    @Callback
    public void expelWithRadius(Player sender, Double radius, Double strength)
    {
        radius = Math.clamp(radius, 1.0, 100.0);
        strength = Math.clamp(strength, 1.0, 100.0);

        final CrashPacketService packets = plugin().services().require(CrashPacketService.class);
        final Vector senderPos = sender.getLocation().toVector();
        final double finalStrength = strength;

        final List<Component> sent = sender.getWorld().getNearbyPlayers(sender.getLocation(), radius)
                .stream().filter(player -> !player.equals(sender)).map(player ->
                {
                    final Location targetPos = player.getLocation();
                    final Vector targetPosVec = targetPos.toVector();

                    player.getWorld().createExplosion(targetPos, 0.0f, false);
                    FUtil.setFlying(player, false);
                    packets.graceMovement(player.getUniqueId(), PUSH_GRACE_MS);
                    player.setVelocity(targetPosVec.subtract(senderPos).normalize().multiply(finalStrength));

                    return player.displayName()
                            .colorIfAbsent(NamedTextColor.WHITE)
                            .hoverEvent(HoverEvent.showText(Component.text(player.getName())));
                }).toList();

        if (sent.isEmpty())
        {
            msg(sender, "<gray>No players pushed.");
        }
        else
        {
            msg(sender, "<gray>Pushed <size> players: <players>",
                    Formatter.number("size", sent.size()),
                    Formatter.joining("players", Component.join(JoinConfiguration.commas(true), sent)));
        }
    }
}

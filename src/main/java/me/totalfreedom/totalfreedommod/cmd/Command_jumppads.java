package me.totalfreedom.totalfreedommod.cmd;

import java.util.stream.Stream;

import org.bukkit.command.CommandSender;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.fun.Jumppads;

@Permission(level = Rank.SUPER_ADMIN, source = SourceType.BOTH, permission = "tfm.fun.jumppads")
@Command(name = "jumppads", description = "Manage jumppads", usage = "/<command> <<on | off> | info | mode <mode> | strength <strength>>", aliases = {"launchpads", "jp"})
public class Command_jumppads extends FCommand
{
    @Callback
    public void setEnabled(CommandSender sender, @Resolve("Boolean") boolean status)
    {
        adminAction(sender, "<aqua><choice:Enabling:Disabling> Jumppads", Formatter.booleanChoice("choice", status));
        plugin().jp.setMode(status ? Jumppads.JumpPadMode.NORMAL : Jumppads.JumpPadMode.OFF);
    }

    @Callback
    @Subcommand("strength")
    public void setStrength(CommandSender sender, Float value)
    {
        value = Math.clamp(value, 1F, 10F);

        adminAction(sender, "<aqua>Setting Jumppads strength to: <value>", Formatter.number("value", value));
        plugin().jp.setStrength((value / 10) + 0.1F);
    }

    @Callback
    @Subcommand("mode")
    public void getModes(CommandSender sender)
    {
        msg(
            sender, 
            """
            <gray>The current Jumppads mode is <white><mode><gray>.
            <gray>Possible modes: <modes>
            """,
            Placeholder.unparsed("mode", plugin().jp.getMode().name()),
            MessageUtils.joinedList("modes", Stream.of(Jumppads.JumpPadMode.values())
                                                        .map(m -> m.name())
                                                        .toList(), NamedTextColor.WHITE)
        );
    }

    @Callback
    @Subcommand("mode")
    public void setMode(CommandSender sender, Jumppads.JumpPadMode mode)
    {
        if (mode == Jumppads.JumpPadMode.OFF)
        {
            setEnabled(sender, false);
            return;
        }

        adminAction(
                    sender, 
                    "<aqua><status:Setting:Enabling and setting> Jumppads to <mode>.", 
                    Formatter.booleanChoice("status", plugin().jp.getMode().isOn()), 
                    Placeholder.unparsed("mode", mode.getLabel()));

        plugin().jp.setMode(mode);
    }

    @Callback
    @Subcommand("info")
    public void showInfo(CommandSender sender)
    {
        final Jumppads.JumpPadMode mode = plugin().jp.getMode();

        msg(
            sender,
            """
            <blue>Jumppads: <jump:Enabled:Disabled>
            <blue>Sideways: <sideways:Enabled:Disabled>
            <blue>Strength: <strength>
            """,
            Formatter.booleanChoice("jump", mode.isOn()),
            Formatter.booleanChoice("sideways", mode == Jumppads.JumpPadMode.NORMAL_AND_SIDEWAYS),
            Formatter.number("strength", plugin().jp.getStrength() * 10 - 1)
        );
    }
}
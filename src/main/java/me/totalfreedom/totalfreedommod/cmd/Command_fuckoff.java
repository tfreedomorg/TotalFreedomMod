package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Permission(source = SourceType.ONLY_IN_GAME, permission = "tfm.admin.senior.fuckoff")
@Command(name = "fuckoff", description = "You'll never even see it coming.", usage = "/fuckoff <on [radius (default=25)] | off>")
public class Command_fuckoff extends FCommand
{

    private static final double DEFAULT_RADIUS = 25.0;
    private static final double MIN_RADIUS = 1.0;
    private static final double MAX_RADIUS = 50.0;

    @Callback
    @Subcommand("on")
    public void enableDefaultRadius(final Player sender)
    {
        enable(sender, DEFAULT_RADIUS);
    }

    @Callback
    @Subcommand("on")
    public void enableWithRadius(final Player sender, final @Resolve("Double") double radius)
    {
        enable(sender, radius);
    }

    @Callback
    @Subcommand("off")
    public void disable(final Player sender)
    {
        plugin().fo.disable(sender);
        msg(sender, "<gray>Fuckoff disabled.");
    }

    private void enable(final Player sender, final double radius)
    {
        final double clamped = Math.clamp(radius, MIN_RADIUS, MAX_RADIUS);

        plugin().fo.enable(sender, clamped);

        msg(sender, "<gray>Fuckoff enabled with radius <radius>.", Formatter.number("radius", clamped));
    }
}

package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.bukkit.entity.Player;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.player.SpyMode;

@Command(name = "cmdspy", description = "Spy on commands", usage = "/cmdspy [ops | admins | all | off]", aliases = {"commandspy", "cspy"})
@Permission(permission = "tfm.admin.cmdspy", source = SourceType.ONLY_IN_GAME)
public class Command_cmdspy extends FCommand
{
    // doing this to show ajax why i don't like using var keyword :)
    @Callback
    public void toggle(final Player player)
    {
        final var pd = plugin().pl.getPlayer(player);
        commandSpy(player, pd.cmdspyEnabled() ? SpyMode.OFF : SpyMode.ALL);
    }

    @Callback
    public void commandSpy(final Player player, final SpyMode mode) // should auto resolve enums
    {
        final var fp = plugin().pl.getPlayer(player);
        final var pd = plugin().pl.getData(player);

        fp.setCommandSpyMode(mode);
        pd.setCommandSpyMode(mode); // Why is this in two separate locations? This is unnecessary caching.
        plugin().pl.saveAsync();

        switch (mode)
        {
            case OFF -> msg(player, "<gray>CommandSpy disabled.");
            case ADMINS -> msg(player, "<gray>CommandSpy set to <green>ADMINS</green> mode. You will only see admins' commands.");
            case OPS -> msg(player, "<gray>CommandSpy set to <green>OPS</green> mode. You will only see OPs' commands.");
            case ALL -> msg(player, "<gray>CommandSpy set to <green>ALL</green> mode. You will see both OPs' and admins' commands.");
        }
    }

    @Completer(value = "", position = 0)
    public List<String> completeMode(final Player player, final String partial)
    {
        final String lower = partial.toLowerCase(Locale.ROOT);

        return Stream.of("ops", "admins", "all", "off")
                     .filter(mode -> mode.startsWith(lower))
                     .toList();
    }
}

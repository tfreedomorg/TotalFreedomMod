package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.bukkit.entity.Player;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.player.SpyMode;

@Command(name = "signspy", description = "Spy on sign edits", usage = "/signspy [ops | admins | all | off]", aliases = {"sspy"})
@Permission(permission = "tfm.admin.signspy", source = SourceType.ONLY_IN_GAME)
public class Command_signspy extends FCommand
{
    @Callback
    public void toggle(final Player player)
    {
        final PlayerData data = plugin().pl.getData(player);
        signSpy(player, data.isSignSpy() ? SpyMode.OFF : SpyMode.OPS);
    }

    @Callback
    public void signSpy(final Player player, final SpyMode mode)
    {
        final PlayerData data = plugin().pl.getData(player);

        data.setSignSpyMode(mode);
        plugin().pl.saveAsync();
        
        switch (mode)
        {
        case OFF -> msg(player, "<gray>SignSpy disabled.");
        case OPS -> msg(player, "<gray>SignSpy set to <green>OPS</green> mode. You will only see non-admins' sign edits.");
        case ADMINS -> msg(player, "<gray>SignSpy set to <green>ADMINS</green> mode. You will only see admins' sign edits.");
        case ALL -> msg(player, "<gray>SignSpy set to <green>ALL</green> mode. You will see both non-admins' and admins' sign edits.");
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

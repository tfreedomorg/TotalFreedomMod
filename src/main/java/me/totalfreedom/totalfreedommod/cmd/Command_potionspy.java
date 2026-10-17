package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.bukkit.entity.Player;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.player.SpyMode;

@Command(name = "potionspy", description = "Spy on potion usage", usage = "/potionspy [ops | admins | all | off]", aliases = {"potspy"})
@Permission(permission = "tfm.admin.potspy", source = SourceType.ONLY_IN_GAME)
public class Command_potionspy extends FCommand
{
    @Callback
    public void toggle(final Player player)
    {
        final PlayerData data = plugin().pl.getData(player);
        potionSpy(player, data.isPotionSpy() ? SpyMode.OFF : SpyMode.ALL);
    }

    @Callback
    public void potionSpy(final Player player, final SpyMode mode)
    {
        final PlayerData data = plugin().pl.getData(player);

        data.setPotionSpyMode(mode);
        plugin().pl.saveAsync();

        switch (mode)
        {
        case OFF -> msg(player, "<gray>PotionSpy disabled.");
        case OPS -> msg(player, "<gray>PotionSpy set to <green>OPS</green> mode. You will only see non-admins' potions.");
        case ADMINS -> msg(player, "<gray>PotionSpy set to <green>ADMINS</green> mode. You will only see admins' potions.");
        case ALL -> msg(player, "<gray>PotionSpy set to <green>ALL</green> mode. You will see both non-admins' and admins' potions.");
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

package me.totalfreedom.totalfreedommod.cmd;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Callback;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Subcommand;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.util.AdventureUtil;
import me.totalfreedom.totalfreedommod.util.FUtil;

@Command(name = "nicknyan", description = "Essentials Interface Command - Nyanify your nickname.", usage = "/<command> <<nick> | off>")
@Permission(source = SourceType.ONLY_IN_GAME, permission = "tfm.player.nicknyan")
public class Command_nicknyan extends FCommand
{
    @Callback
    @Subcommand("off")
    public void clear(Player player)
    {
        getData(player).setNickname(null);
        msg(player, "<gray>Nickname cleared.");
    }

    @Callback
    public void nyanify(Player player, String nickname)
    {
        final PlayerData data = getData(player);

        final Component colorized = FUtil.colorize(nickname.trim());
        final String nickPlain = AdventureUtil.componentToPlainText(colorized).trim();

        if (!nickPlain.matches("^[a-zA-Z_0-9]+$"))
        {
            msg(player, "<red>That nickname contains invalid characters.");
            return;
        }
        else if (nickPlain.length() < 4 || nickPlain.length() > 30)
        {
            msg(player, "<red>Your nickname must be between 4 and 30 characters long.");
            return;
        }

        for (final Player other : server().getOnlinePlayers())
        {
            if (other.getUniqueId().equals(player.getUniqueId()))
            {
                continue;
            }
            if (other.getName().equalsIgnoreCase(nickPlain) || AdventureUtil.componentToPlainText(other.displayName()).trim().equalsIgnoreCase(nickPlain))
            {
                msg(player, "<red>That nickname is already in use.");
                return;
            }
        }

        final List<Component> letters = new ArrayList<>();
        nickPlain.chars().forEach(c -> letters.add(Component.text((char) c, FUtil.randomChatColor())));
        final Component newNick = Component.join(JoinConfiguration.noSeparators(), letters);

        data.setNickname(newNick);

        msg(player, "<nick>", MessageUtils.component("nick", Component.text("Your nickname is now: ").append(newNick)));
    }
}

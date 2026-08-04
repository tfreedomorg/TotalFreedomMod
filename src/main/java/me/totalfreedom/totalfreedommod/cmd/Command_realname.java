package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Callback;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Completer;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Greedy;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.rank.Rank;
import me.totalfreedom.totalfreedommod.util.AdventureUtil;
import net.kyori.adventure.text.Component;

import org.bukkit.command.CommandSender;

@Command(name = "realname", description = "Finds the real name of a nicknamed player", usage = "/<command> <nickname..>")
@Permission(level = Rank.OP, permission = "tfm.player.realname")
public class Command_realname extends FCommand
{
    @Completer(value = "", position = 0, scope = Completer.Scope.ARGUMENT)
    public List<String> completeNickname(CommandSender sender, String partial)
    {
        return NameCandidates.onlineNicknames(plugin(), server(), partial);
    }

    @Callback
    public void realname(CommandSender sender, @Greedy String nickname)
    {
        final boolean foundOne = server().getOnlinePlayers()
                .stream()
                .reduce(false, (found, player) ->
                    {
                        final PlayerData data = plugin().pl.getData(player);
                        final Component playerNickname = data.getNickname();
                        if (playerNickname != null)
                        {
                            final String plainNick = AdventureUtil.componentToPlainText(playerNickname);
                            if (plainNick.contains(nickname))
                            {
                                msg(sender, "<nickname><gray> is <name>.", MessageUtils.component("nickname", data.getDisplayedNickname()),
                                        MessageUtils.unparsed("name", player.getName()));
                                return true;
                            }
                        }
                        return found;
                    }, (a, b) -> a || b);

        if (!foundOne)
        {
            msg(sender, "<gray>Could not find a player with such a nickname.");
        }
    }
}

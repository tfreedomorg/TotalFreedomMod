package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.player.FPlayer;
import me.totalfreedom.totalfreedommod.rank.Rank;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

@Command(name = "adminchat",
    description = "AdminChat - Talk privately with other admins. Using the command by itself will toggle AdminChat on and off for all messages.",
    usage = "/<command> [message...]",
    aliases = {"o", "ac"})
@Permission(level = Rank.SUPER_ADMIN, source = SourceType.BOTH, permission = "tfm.admin.adminchat")
public class Command_adminchat extends FCommand
{

    @Callback
    @Permission(level = Rank.SUPER_ADMIN, permission = "tfm.admin.adminchat", source = SourceType.ONLY_IN_GAME)
    public void toggle(Player sender)
    {
        final FPlayer fplayer = plugin().pl.getPlayer(sender);
        boolean mode = !fplayer.inAdminChat();

        fplayer.setAdminChat(mode);
        msg(sender, "<gray>Toggled Admin Chat <mode:on:off>.", Formatter.booleanChoice("mode", mode));
    }

    @Completer(value = "", position = 0)
    public List<String> completeMessage(CommandSender sender, String partial)
    {
        return NameCandidates.onlineTyped(server(), partial);
    }
    
    @Callback
    public void sendMessage(CommandSender sender, @Greedy String message)
    {
        plugin().cm.adminChat(sender, message);
    }
}

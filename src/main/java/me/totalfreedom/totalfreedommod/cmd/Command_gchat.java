package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Callback;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Completer;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Greedy;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;

@Command(name = "gchat", description = "Send a chat message as someone else.", usage = "/gchat <player> <message>")
@Permission(permission = "tfm.admin.gchat")
public class Command_gchat extends FCommand
{
    @Completer(value = "", position = 1)
    public List<String> completeMessage(CommandSender sender, String partial)
    {
        return NameCandidates.onlineTyped(server(), partial);
    }

    @Callback
    public void sendMessageAsSomeoneElse(CommandSender sender, Player player, @Greedy String message)
    {
        if (message.startsWith("/"))
        {
            if (!(CommandRegistry.getByName("gcmd") instanceof Command_gcmd gcmd))
            {
                msg(sender, "<gray>We were going to redirect you to use /gcmd instead, but apparently that isn't registered. Please contact a developer!");
                return;
            }

            gcmd.runAsOtherPlayer(sender, player, message.substring(1));
            return;
        }

        if (isProtectedAdmin(sender, player))
            return;

        msg(sender, "<gray>Sending chat as <yellow><name><gray>: <white><message>",
                Placeholder.unparsed("name", player.getName()),
                MessageUtils.parsed("message", message)); // support tags in gchat

        player.chat(message);

        msg(sender, "<green>Chat message sent.");
    }
}

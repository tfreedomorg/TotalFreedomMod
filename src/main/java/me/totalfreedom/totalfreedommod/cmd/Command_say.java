package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import org.bukkit.command.CommandSender;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.rank.Rank;
import me.totalfreedom.totalfreedommod.util.ChatMentionUtil;
import me.totalfreedom.totalfreedommod.util.FUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

@Command(name = "say", description = "Broadcasts the given message as the console, includes sender name.", usage = "/say <message>")
@Permission(permission = "tfm.admin.say", level = Rank.SUPER_ADMIN)
public class Command_say extends FCommand
{
    @Completer(value = "", position = 0)
    public List<String> completeMessage(CommandSender sender, String partial)
    {
        return NameCandidates.onlineTyped(server(), partial);
    }
    
    @Callback
    public void say(CommandSender sender, @Greedy String message)
    {
        final Component broadcast = MessageUtils.parse("<light_purple>[Server:<name>] ", Placeholder.unparsed("name", sender.getName()))
                .append(ChatMentionUtil.highlightAndPing(plugin(), FUtil.colorizeWithLinks(message, NamedTextColor.LIGHT_PURPLE), true));

        FUtil.bcastMsg(broadcast);
        plugin().db.sendBroadcastMessage(sender.getName(), MessageUtils.toPlainText(broadcast), ConfigEntry.DISCORD_SAY_MESSAGE);
    }
}

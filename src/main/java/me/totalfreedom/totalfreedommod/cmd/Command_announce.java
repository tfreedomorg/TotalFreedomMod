package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import org.bukkit.command.CommandSender;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.rank.Rank;

@Command(name = "announce", description = "Make an announcement", usage = "/announce <message>")
@Permission(level = Rank.SUPER_ADMIN, permission = "tfm.admin.announce")
public class Command_announce extends FCommand 
{
    @Completer(value = "", position = 0)
    public List<String> completeContent(CommandSender sender, String partial)
    {
        return NameCandidates.onlineTyped(server(), partial);
    }

    @Callback
    public void broadcast(CommandSender sender, @Greedy String content)
    {
        plugin().an.announce(content); // TODO: Trace this all the way to the root, and modify to use new MessageUtils
    }
}

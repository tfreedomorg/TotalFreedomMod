package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import org.bukkit.command.CommandSender;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.util.FUtil;

@Command(name = "rawsay", description = "Broadcasts the given message. Supports colors.", usage = "/rawsay <message>")
@Permission(permission = "tfm.admin.senior.rawsay")
public class Command_rawsay extends FCommand
{
    @Completer(value = "", position = 0)
    public List<String> completeMessage(CommandSender sender, String partial)
    {
        return NameCandidates.onlineTyped(server(), partial);
    }

    @Callback
    public void rawsay(CommandSender sender, @Greedy String message)
    {
        FUtil.bcastMsg(MessageUtils.parse(message));
    }
}

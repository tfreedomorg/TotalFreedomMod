package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.command.CommandSender;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.dispatch.RemoteDispatchContext;
import me.totalfreedom.totalfreedommod.util.FUtil;

@Command(name = "consolesay", description = "Send a chat message with chat formatting over SSH.", usage = "/<command> <message>", aliases = {"csay"})
@Permission(source = SourceType.ONLY_CONSOLE, permission = "tfm.admin.consolesay")
public class Command_consolesay extends FCommand
{
    @Callback
    public void sendMessage(final CommandSender sender, final @Greedy String message)
    {
        final String prefix = RemoteDispatchContext.isActive() ? "" : "<gray>[CONSOLE]</gray>";

        FUtil.bcastMsg(
            "<prefix><white><open><red><name><white><close> <message>", // [CONSOLE] <identity> message
            MessageUtils.parsed("prefix", prefix),
            Placeholder.unparsed("open", "<"),
            Placeholder.unparsed("close", ">"),
            Placeholder.unparsed("name", sender.getName()),
            Placeholder.unparsed("message", message)
        );
    }
}

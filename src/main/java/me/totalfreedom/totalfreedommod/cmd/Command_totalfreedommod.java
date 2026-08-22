package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.command.CommandSender;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Callback;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Subcommand;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.discord.DiscordBridge;
import me.totalfreedom.totalfreedommod.player.FPlayer;

/*
 * See https://github.com/TotalFreedom/License - This file may not be edited or removed.
 */
@Command(name = "totalfreedommod", description = "Shows information about TotalFreedomMod or reloads it", usage = "/totalfreedommod [reload]", aliases = {"tfm"})
@Permission(permission = "tfm.server.info")
public class Command_totalfreedommod extends FCommand
{
    @Subcommand("reload")
    @Permission(permission = "tfm.server.reload")
    @Callback
    public void reloadPlugin(CommandSender sender)
    {
        if (!plugin().al.isAdmin(sender))
        {
            showPluginInformation(sender);
            return;
        }

        plugin().config.load();
        FPlayer.refreshConfig();
        plugin().csr.load();
        DiscordBridge.reloading = true;
        try
        {
            plugin().services.stop();
            plugin().services.start();
        }
        finally
        {
            DiscordBridge.reloading = false;
        }

        msg(
            sender, 
            "<gray><name> v<version> reloaded.", 
            Placeholder.unparsed("name", TotalFreedomMod.pluginName), 
            Placeholder.unparsed("version", TotalFreedomMod.pluginVersion));
    }

    @Callback
    public void showPluginInformation(CommandSender sender)
    {
        TotalFreedomMod.BuildProperties build = TotalFreedomMod.build;
        msg(
            sender,
            """
                <gold>TotalFreedomMod for 'Total Freedom', the original all-op server.
                <gold>Running on <server>.
                <gold>Created by Madgeek1450 and Prozza.
                <gold>Version <blue><codename> - <version> Build <number> <gold>(<blue><head><gold>)
                <gold>Compiled <blue><date> <gold>by <blue><author>
                <green>Visit <aqua>https://github.com/tfreedomorg/totalfreedommod <green>for more information.        
            """,
            Placeholder.unparsed("server", ConfigEntry.SERVER_NAME.getString()),
            Placeholder.unparsed("codename", build.codename),
            Placeholder.unparsed("version", build.version),
            Placeholder.unparsed("number", build.number),
            Placeholder.unparsed("head", build.head),
            Placeholder.unparsed("date", build.date),
            Placeholder.unparsed("author", build.author)
        );
    }
}
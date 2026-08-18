package me.totalfreedom.totalfreedommod.cmd;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Callback;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Subcommand;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.discord.DiscordBridge;
import me.totalfreedom.totalfreedommod.framework.FatalServiceStartupException;
import me.totalfreedom.totalfreedommod.player.FPlayer;
import me.totalfreedom.totalfreedommod.rank.Rank;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;

/*
 * See https://github.com/TotalFreedom/License - This file may not be edited or removed.
 */
@Command(name = "totalfreedommod", description = "Shows information about TotalFreedomMod or reloads it", usage = "/totalfreedommod [reload]", aliases = {"tfm"})
@Permission(permission = "tfm.server.info", level = Rank.NON_OP)
public class Command_totalfreedommod extends FCommand
{
    @Subcommand("reload")
    @Permission(level = Rank.SUPER_ADMIN, permission = "tfm.server.info")
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
        if (!plugin().pbl.validateStorage())
        {
            msg(sender, "<red>TFM was not reloaded because player-blocks.yml is invalid.");
            return;
        }

        DiscordBridge.reloading = true;
        plugin().pbe.beginServiceReload();
        boolean fatalFailure = false;
        try
        {
            plugin().services.stop();
            plugin().services.start();
        }
        catch (FatalServiceStartupException ex)
        {
            fatalFailure = true;
            plugin().pbe.endServiceReload();
            DiscordBridge.reloading = false;
            msg(sender, "<red>TFM could not reload safely and has been disabled. Check the console.");
            plugin().getServer().getPluginManager().disablePlugin(plugin());
            return;
        }
        finally
        {
            if (!fatalFailure)
            {
                plugin().pbe.endServiceReload();
                DiscordBridge.reloading = false;
            }
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

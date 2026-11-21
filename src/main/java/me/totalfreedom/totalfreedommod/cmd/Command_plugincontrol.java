package me.totalfreedom.totalfreedommod.cmd;

import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Permission(source = SourceType.ONLY_CONSOLE, permission = "tfm.admin.senior.plugincontrol")
@Command(name = "plugincontrol", aliases = "plc", description = "Manage plugins", usage = "/<command> <<enable | disable | reload> <pluginname>> | list>")
public class Command_plugincontrol extends FCommand
{
    private PluginManager pluginManager()
    {
        return server().getPluginManager();
    }

    @Callback
    @Subcommand("list")
    public void listPlugins(CommandSender sender)
    {
        for (final Plugin otherPlugin : pluginManager().getPlugins())
        {
            final PluginMeta meta = otherPlugin.getPluginMeta();
            final String template = "<gray>- <status:'<green><name></green>':'<red><name></red>'><gray> v<version>";

            if (meta.getAuthors().isEmpty())
            {
                msg(sender, template,
                        Formatter.booleanChoice("status", otherPlugin.isEnabled()),
                        Placeholder.unparsed("name", meta.getDisplayName()),
                        Placeholder.unparsed("version", meta.getVersion()));
            }
            else
            {
                msg(sender, template + " by <authors>",
                        Formatter.booleanChoice("status", otherPlugin.isEnabled()),
                        Placeholder.unparsed("name", meta.getDisplayName()),
                        Placeholder.unparsed("version", meta.getVersion()),
                        MessageUtils.joinedList("authors", meta.getAuthors(), NamedTextColor.WHITE));
            }
        }
    }

    @Callback
    @Subcommand("enable")
    public void enablePlugin(CommandSender sender, @Resolve("Plugin") Plugin otherPlugin)
    {
        if (otherPlugin.equals(plugin()))
        {
            msg(sender, "<red>I'm pretty sure the TotalFreedomMod is enabled already.");
            return;
        }

        if (otherPlugin.isEnabled())
        {
            msg(sender, "<gray><name> is already enabled.", Placeholder.unparsed("name", otherPlugin.getName()));
            return;
        }

        pluginManager().enablePlugin(otherPlugin);

        if (otherPlugin.isEnabled())
        {
            msg(sender, "<green><name><white> has been successfully enabled.", Placeholder.unparsed("name", otherPlugin.getName()));
        }
        else
        {
            msg(sender, "<red>A problem occurred whilst attempting to enable <name>.", Placeholder.unparsed("name", otherPlugin.getName()));
        }
    }

    @Callback
    @Subcommand("disable")
    public void disablePlugin(CommandSender sender, @Resolve("Plugin") Plugin otherPlugin)
    {
        if (otherPlugin.getName().equalsIgnoreCase(plugin().getName()))
        {
            msg(sender, "<red>Did you really think that was going to work?");
            return;
        }

        if (!otherPlugin.isEnabled())
        {
            msg(sender, "<gray><name> is already disabled.", Placeholder.unparsed("name", otherPlugin.getName()));
            return;
        }

        pluginManager().disablePlugin(otherPlugin);

        msg(sender, "<gray><name> has been disabled.", Placeholder.unparsed("name", otherPlugin.getName()));
    }

    @Callback
    @Subcommand("reload")
    public void reloadPlugin(CommandSender sender, @Resolve("Plugin") Plugin otherPlugin)
    {
        if (otherPlugin.getName().equalsIgnoreCase(plugin().getName()))
        {
            if (!(CommandRegistry.getByName("totalfreedommod") instanceof Command_totalfreedommod tfmCommand))
            {
                msg(sender, "<red>Sorry, we were unable to get the /totalfreedommod command to reload it. Please contact a developer for assistance.");
                return;
            }

            tfmCommand.reloadPlugin(sender);
            return;
        }

        pluginManager().disablePlugin(otherPlugin);
        pluginManager().enablePlugin(otherPlugin);

        msg(sender, "<gray><name> has been reloaded.", Placeholder.unparsed("name", otherPlugin.getName()));
    }
}

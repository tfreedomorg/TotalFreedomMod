package me.totalfreedom.totalfreedommod.httpd.module;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.SimpleCommandMap;

import me.totalfreedom.totalfreedommod.PluginProvider;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.cmd.CommandRegistry;
import me.totalfreedom.totalfreedommod.cmd.FCommand;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;
import me.totalfreedom.totalfreedommod.display.Displayable;
import me.totalfreedom.totalfreedommod.httpd.NanoHTTPD;
import me.totalfreedom.totalfreedommod.rank.CustomRank;

import com.google.common.collect.Lists;

import static me.totalfreedom.totalfreedommod.httpd.HTMLGenerationTools.heading;
import static me.totalfreedom.totalfreedommod.httpd.HTMLGenerationTools.paragraph;

public class Module_help extends HTTPDModule
{

    public Module_help(TotalFreedomMod plugin, NanoHTTPD.HTTPSession session)
    {
        super(plugin, session);
    }

    @Override
    public String getBody()
    {
        final CommandMap map = server.getCommandMap();
        if (!(map instanceof SimpleCommandMap))
        {
            return paragraph("Error loading commands.");
        }

        final StringBuilder responseBody = new StringBuilder()
                .append(heading("Command Help", 1))
                .append(paragraph(
                        "This page is an automatically generated listing of all plugin commands that are currently live on the server. "
                        + "Please note that it does not include vanilla server commands."));

        final Collection<Command> knownCommands = ((SimpleCommandMap) map).getCommands();
        final Map<String, List<Command>> commandsByPlugin = new HashMap<>();

        for (Command command : knownCommands)
        {
            String pluginName = "Bukkit";
            if (command instanceof PluginIdentifiableCommand)
            {
                pluginName = ((PluginIdentifiableCommand) command).getPlugin().getName();
            }

            commandsByPlugin.computeIfAbsent(pluginName, k -> Lists.newArrayList()).add(command);
        }

        final Iterator<Map.Entry<String, List<Command>>> it = commandsByPlugin.entrySet().iterator();
        while (it.hasNext())
        {
            final Map.Entry<String, List<Command>> entry = it.next();
            final String pluginName = entry.getKey();
            final List<Command> commands = entry.getValue();

            Collections.sort(commands, new CommandComparator());

            responseBody.append(heading(pluginName, 2)).append("<ul>\r\n");

            Displayable lastTfmCommandLevel = null;
            for (Command command : commands)
            {
                if (!TotalFreedomMod.pluginName.equals(pluginName))
                {
                    responseBody.append(buildDescription(command));
                    continue;
                }

                FCommand fc = CommandRegistry.getByName(command.getName());
                Permission perm = fc == null ? null : fc.getClass().getAnnotation(Permission.class);
                if (perm == null)
                {
                    responseBody.append(buildDescription(command));
                    continue;
                }

                // The tier is derived from ranks.json rather than declared, so a command whose node
                // no rank grants has no heading to sit under and is listed without one.
                Displayable tfmCommandLevel = requiredRank(perm);
                if (tfmCommandLevel == null)
                {
                    responseBody.append(buildDescription(command));
                    continue;
                }

                if (lastTfmCommandLevel == null || lastTfmCommandLevel != tfmCommandLevel)
                {
                    responseBody.append("</ul>\r\n").append(heading(tfmCommandLevel.getName(), 3)).append("<ul>\r\n");
                }
                lastTfmCommandLevel = tfmCommandLevel;
                responseBody.append(buildDescription(command));
            }

            responseBody.append("</ul>\r\n");
        }

        return responseBody.toString();
    }

    /**
     * The least privileged rank that grants {@code perm}'s node, which is the tier the command
     * actually requires, or {@code null} when no rank grants it.
     */
    private static CustomRank requiredRank(Permission perm)
    {
        final TotalFreedomMod plugin = PluginProvider.get();

        return plugin == null || plugin.rm == null
                ? null
                : plugin.rm.getRegistry().requiredFor(perm.permission()).orElse(null);
    }

    private static String buildDescription(Command command)
    {
        // Fall back to annotation data from the FCommand registry.
        String usage = command.getUsage();
        String description = command.getDescription();

        FCommand fc = CommandRegistry.getByName(command.getName());
        var meta = fc == null
                ? null
                : fc.getClass().getAnnotation(me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command.class);
        if (meta != null)
        {
            if (usage == null || usage.isBlank())
            {
                usage = meta.usage();
            }
            if (description == null || description.isBlank())
            {
                description = meta.description();
            }
        }

        if (usage == null)
        {
            usage = "";
        }
        if (description == null)
        {
            description = "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(
                "<li><span class=\"commandName\">{$CMD_NAME}</span> - Usage: <span class=\"commandUsage\">{$CMD_USAGE}</span>"
                .replace("{$CMD_NAME}", escapeHtml(command.getName().trim()))
                .replace("{$CMD_USAGE}", escapeHtml(usage.trim())));

        if (!command.getAliases().isEmpty())
        {
            sb.append(
                    " - Aliases: <span class=\"commandAliases\">{$CMD_ALIASES}</span>"
                    .replace("{$CMD_ALIASES}", escapeHtml(String.join(", ",
                            command.getAliases()))));
        }

        sb.append(
                "<br><span class=\"commandDescription\">{$CMD_DESC}</span></li>\r\n"
                .replace("{$CMD_DESC}", escapeHtml(description.trim())));

        return sb.toString();
    }

    @Override
    public String getTitle()
    {
        return "TotalFreedomMod :: Command Help";
    }

    @Override
    public String getStyle()
    {
        return ".commandName{font-weight:bold;}.commandDescription{padding-left:15px;}li{margin:.15em;padding:.15em;}";
    }

    public static class CommandComparator implements Comparator<Command>
    {

        @Override
        public int compare(Command a, Command b)
        {
            FCommand ca = CommandRegistry.getByName(a.getName());
            FCommand cb = CommandRegistry.getByName(b.getName());

            Permission pa = ca == null ? null : ca.getClass().getAnnotation(Permission.class);
            Permission pb = cb == null ? null : cb.getClass().getAnnotation(Permission.class);

            if (pa == null || pb == null)
            {
                return a.getName().compareTo(b.getName());
            }

            final CustomRank ra = requiredRank(pa);
            final CustomRank rb = requiredRank(pb);

            if (ra == null || rb == null)
            {
                return a.getName().compareTo(b.getName());
            }

            return Integer.compare(ra.getLevel(), rb.getLevel());
        }
    }
}

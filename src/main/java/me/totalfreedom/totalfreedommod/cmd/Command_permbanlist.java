package me.totalfreedom.totalfreedommod.cmd;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.command.CommandSender;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Callback;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;

@Command(name = "permbanlist", aliases = "pbanlist", description = "Shows all permanently banned players and IP addresses.", usage = "/<command> [page]")
@Permission(permission = "tfm.admin.banlist")
public class Command_permbanlist extends FCommand
{
    private static final int ENTRIES_PER_PAGE = 10;

    @Callback
    public void showFirstPage(CommandSender sender)
    {
        showPage(sender, 1);
    }

    @Callback
    public void showPage(CommandSender sender, int page)
    {
        final List<String> permbanNames = new ArrayList<>(plugin().pm.getPermbannedNames());
        permbanNames.sort(String.CASE_INSENSITIVE_ORDER);

        if (permbanNames.isEmpty())
        {
            msg(sender, "<red>There are currently no permanently banned players.");
            return;
        }

        final int totalPages = (int) Math.ceil((double) permbanNames.size() / ENTRIES_PER_PAGE);

        if (page < 1 || page > totalPages)
        {
            msg(sender, "<red>Invalid page. Please choose a page from 1 to <total>.", Formatter.number("total", totalPages));
            return;
        }

        final int startIndex = (page - 1) * ENTRIES_PER_PAGE;
        final int endIndex = Math.min(startIndex + ENTRIES_PER_PAGE, permbanNames.size());
        final List<String> pageEntries = permbanNames.subList(startIndex, endIndex);

        msg(sender, "<dark_red>Permbans (<page>/<total>): <white><entries>",
                Formatter.number("page", page),
                Formatter.number("total", totalPages),
                Formatter.joining("entries", pageEntries.stream()
                                                             .map(name -> MessageUtils.parse(name + "<gray>, <white>"))
                                                             .toList()));

        if (page < totalPages)
        {
            msg(sender, "<gray>Use /permbanlist <next> to view the next page.", Formatter.number("next", page + 1));
        }
    }
}

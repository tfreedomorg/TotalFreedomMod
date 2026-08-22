package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;
import java.util.Set;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Callback;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Completer;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Subcommand;
import me.totalfreedom.totalfreedommod.title.Title;

/**
 * Grants, revokes and inspects titles.
 * <p>
 * Granting is guarded by {@code tfm.manage.titles} rather than by the node the title itself
 * carries: a title is a way to hand out a capability, so being able to hand out any of them is a
 * strictly larger privilege than holding one.
 */
@Command(
        name = "title",
        description = "View and manage player titles.",
        usage = "/title [list | info <title> | of <player> | grant <player> <title> | revoke <player> <title>]",
        aliases = {"titles"}
)
@Permission(permission = "tfm.player.titles")
public class Command_title extends FCommand
{

    @Callback
    public void list(CommandSender sender)
    {
        final List<Title> titles = plugin().tm.getTitlesSorted();

        if (titles.isEmpty())
        {
            msg(sender, "<gray>No titles are configured.");
            return;
        }

        msg(sender, "<gold>Titles:");
        titles.forEach(title -> msg(sender, "<line>",
                MessageUtils.component("line", Component.text("  ")
                        .append(title.getColoredTag())
                        .append(Component.text(" "))
                        .append(title.getColoredName())
                        .append(Component.text(" (" + title.getId() + ")", NamedTextColor.DARK_GRAY)))));
    }

    @Callback
    @Subcommand("list")
    public void listExplicit(CommandSender sender)
    {
        list(sender);
    }

    @Callback
    @Subcommand("info")
    public void info(CommandSender sender, String titleId)
    {
        final Title title = plugin().tm.getTitle(titleId);

        if (title == null)
        {
            msg(sender, "<red>No such title: <title>", Placeholder.unparsed("title", titleId));
            return;
        }

        msg(sender, "<line>", MessageUtils.component("line", Component.text("Title: ", NamedTextColor.GOLD)
                .append(title.getColoredTag())
                .append(Component.text(" "))
                .append(title.getColoredName())));
        msg(sender, "<gray>  ID: <id>", Placeholder.unparsed("id", title.getId()));
        msg(sender, "<gray>  Display weight: <weight>", Placeholder.unparsed("weight", String.valueOf(title.getWeight())));

        if (title.getPermissions().isEmpty())
        {
            msg(sender, "<gray>  Grants: <dark_gray><italic>nothing (display only)");
            return;
        }

        msg(sender, "<gray>  Grants:");
        title.getPermissions()
             .stream()
             .sorted()
             .forEach(permission -> msg(sender, "<gray>    - <node>", Placeholder.unparsed("node", permission)));
    }

    @Callback
    @Subcommand("of")
    public void of(CommandSender sender, Player target)
    {
        final List<Title> held = plugin().tm.getHeldTitles(target);

        if (held.isEmpty())
        {
            msg(sender, "<gray><player> holds no titles.", Placeholder.unparsed("player", target.getName()));
            return;
        }

        msg(sender, "<gray><player>'s titles:", Placeholder.unparsed("player", target.getName()));
        held.forEach(title -> msg(sender, "<line>",
                MessageUtils.component("line", Component.text("  ")
                        .append(title.getColoredTag())
                        .append(Component.text(" "))
                        .append(title.getColoredName()))));
    }

    @Callback
    @Subcommand("grant")
    @Permission(permission = "tfm.manage.titles")
    public void grant(CommandSender sender, Player target, String titleId)
    {
        if (sender instanceof Player granter && granter.getUniqueId().equals(target.getUniqueId()))
        {
            msg(sender, "<red>You cannot grant yourself a title.");
            return;
        }
        
        final Title title = plugin().tm.getTitle(titleId);

        if (title == null)
        {
            msg(sender, "<red>No such title: <title>", Placeholder.unparsed("title", titleId));
            return;
        }

        if (!plugin().tm.grantTitle(target, title.getId()))
        {
            msg(sender, "<red><player> already holds that title.",
                    Placeholder.unparsed("player", target.getName()));
            return;
        }

        adminAction(sender, "<aqua>Granted <title> to <player>",
                Placeholder.unparsed("title", title.getName()),
                Placeholder.unparsed("player", target.getName()));

        msg(target, "<green>You have been granted the <title> title.",
                Placeholder.unparsed("title", title.getName()));
    }

    @Callback
    @Subcommand("revoke")
    @Permission(permission = "tfm.manage.titles")
    public void revoke(CommandSender sender, Player target, String titleId)
    {
        if (!plugin().tm.revokeTitle(target, titleId))
        {
            msg(sender, "<red><player> does not hold that title.",
                    Placeholder.unparsed("player", target.getName()));
            return;
        }

        adminAction(sender, "<aqua>Revoked <title> from <player>",
                Placeholder.unparsed("title", titleId),
                Placeholder.unparsed("player", target.getName()));

        msg(target, "<gray>Your <title> title has been revoked.",
                Placeholder.unparsed("title", titleId));
    }

    @Completer(value = "info", position = 0)
    public List<String> completeInfo(CommandSender sender, String partial)
    {
        return matching(plugin().tm.getTitleIds(), partial);
    }

    @Completer(value = "grant", position = 1)
    public List<String> completeGrant(CommandSender sender, String partial, List<String> priorArgs)
    {
        final Player target = server().getPlayerExact(priorArgs.get(0));

        if (target == null)
            return List.of();

        final Set<String> held = plugin().tm.getHeldTitleIds(target);
        final List<String> grantable = plugin().tm.getTitleIds()
                                                  .stream()
                                                  .filter(id -> !held.contains(id))
                                                  .toList();

        return matching(grantable, partial);
    }

    /**
     * Completes only titles the target already holds, so revoking offers the set that can actually
     * be revoked rather than every title that exists.
     */
    @Completer(value = "revoke", position = 1)
    public List<String> completeRevoke(CommandSender sender, String partial, List<String> priorArgs)
    {
        final Player target = server().getPlayerExact(priorArgs.get(0));

        return target == null ? List.of() : matching(plugin().tm.getHeldTitleIds(target), partial);
    }

    private static List<String> matching(Iterable<String> candidates, String partial)
    {
        final String prefix = partial == null ? "" : partial.toLowerCase();

        return java.util.stream.StreamSupport.stream(candidates.spliterator(), false)
                .filter(id -> id.startsWith(prefix))
                .sorted()
                .toList();
    }

}

package me.totalfreedom.totalfreedommod.cmd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.admin.Admin;
import me.totalfreedom.totalfreedommod.cmd.internal.FuzzyMatch;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.rank.CustomRank;
import me.totalfreedom.totalfreedommod.rank.RankRole;

@Command(
        name = "rankconfig",
        description = "Configure custom ranks.",
        usage = "/rankconfig [list | create <id> | edit <rank> | delete <rank> | set <rank> <property> <value> | setrank <player> <rank> | reload | save]",
        aliases = {"rankconf", "rankcfg"}
)
@Permission(permission = "tfm.manage.ranks")
public class Command_rankconfig extends FCommand
{
    private static final List<String> COLOR_NAMES = Stream.concat(
            NamedTextColor.NAMES.keys().stream(),
            Stream.of("purple", "orange", "grey", "dark_grey", "cyan", "dark_cyan", "pink", "magenta"))
        .sorted()
        .toList();
        
    @Callback
    public void menu(CommandSender sender)
    {
        requireManageRanks(sender);
        msg(sender, "<menu>", MessageUtils.component("menu", plugin().rm.buildMainMenu()));
    }

    @Callback
    @Subcommand("list")
    public void list(CommandSender sender)
    {
        menu(sender);
    }

    @Callback
    @Subcommand("create")
    public void create(CommandSender sender, String id)
    {
        requireManageRanks(sender);

        final String rankId = id.toLowerCase();
        if (plugin().rm.hasCustomRank(rankId))
        {
            msg(sender, "<red>A rank with that ID already exists!");
            return;
        }

        final CustomRank rank = new CustomRank(rankId);
        plugin().rm.setCustomRank(rank);

        adminAction(sender, "<aqua>Created new rank: <rank>", Placeholder.unparsed("rank", rank.getName()));
        msg(sender, "<menu>", MessageUtils.component("menu", plugin().rm.buildEditMenu(rank)));
    }

    @Callback
    @Subcommand("edit")
    public void edit(CommandSender sender, String rank)
    {
        requireManageRanks(sender);

        final CustomRank target = plugin().rm.getCustomRank(rank.toLowerCase());
        if (target == null)
        {
            msg(sender, "<red>Rank not found: <rank>", Placeholder.unparsed("rank", rank));
            return;
        }

        msg(sender, "<menu>", MessageUtils.component("menu", plugin().rm.buildEditMenu(target)));
    }

    @Completer(value = "edit", position = 0)
    public List<String> completeEdit(CommandSender sender, String partial)
    {
        return rankIdCandidates(partial);
    }

    @Callback
    @Subcommand("delete")
    public void delete(CommandSender sender, String rank)
    {
        requireManageRanks(sender);

        final String rankId = rank.toLowerCase();
        final CustomRank target = plugin().rm.getCustomRank(rankId);
        if (target == null)
        {
            msg(sender, "<red>Rank not found: <rank>", Placeholder.unparsed("rank", rankId));
            return;
        }

        plugin().rm.removeCustomRank(rankId);
        adminAction(sender, "<red>Deleted rank: <rank>", Placeholder.unparsed("rank", target.getName()));
        msg(sender, "<menu>", MessageUtils.component("menu", plugin().rm.buildMainMenu()));
    }

    @Completer(value = "delete", position = 0)
    public List<String> completeDelete(CommandSender sender, String partial)
    {
        return rankIdCandidates(partial);
    }

    @Callback
    @Subcommand("set")
    public void set(CommandSender sender, String rank, Property property, @Greedy String value)
    {
        requireManageRanks(sender);

        final String rankId = rank.toLowerCase();
        final CustomRank target = plugin().rm.getCustomRank(rankId);
        if (target == null)
        {
            msg(sender, "<red>Rank not found: <rank>", Placeholder.unparsed("rank", rankId));
            return;
        }

        switch (property)
        {
            case NAME -> target.setName(value);
            case ABBREVIATION -> target.setAbbreviation(value);
            case PREFIX -> target.setPrefix(value);
            case DETERMINER -> target.setDeterminer(value);
            case COLOR -> target.setColor(parseColor(value));
            case ADMIN -> target.setAdmin(isTruthy(value));
            case ADDPERM -> target.addPermission(value);
            case REMPERM -> target.removePermission(value);
            case LEVEL ->
            {
                try
                {
                    target.setLevel(Integer.parseInt(value.trim()));
                }
                catch (NumberFormatException e)
                {
                    msg(sender, "<red>Invalid number: <value>", Placeholder.unparsed("value", value));
                    return;
                }
            }
            case INHERIT ->
            {
                final String trimmed = value.trim();
                if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("none"))
                {
                    target.setInheritFrom(null);
                }
                else if (!plugin().rm.hasCustomRank(trimmed.toLowerCase()))
                {
                    msg(sender, "<red>Rank '<rank>' does not exist!", Placeholder.unparsed("rank", trimmed));
                    return;
                }
                else
                {
                    target.setInheritFrom(trimmed.toLowerCase());
                }
            }
        }

        plugin().rm.setCustomRank(target);
        msg(
            sender,
            "<green>Updated <property> for rank <rank>",
            Placeholder.unparsed("property", property.name().toLowerCase()),
            Placeholder.unparsed("rank", target.getName())
        );

        if (sender instanceof Player)
        {
            msg(sender, "<menu>", MessageUtils.component("menu", plugin().rm.buildEditMenu(target)));
        }
    }

    @Completer(value = "set", position = 0)
    public List<String> completeSetRank(CommandSender sender, String partial)
    {
        return rankIdCandidates(partial);
    }

    @Completer(value = "set", position = 2, scope = Completer.Scope.ARGUMENT)
    public List<String> completeSetValue(CommandSender sender, String partial, List<String> priorArgs)
    {
        final Property property = parseProperty(priorArgs.get(1));
        if (property == null)
        {
            return List.of();
        }

        return switch (property)
        {
            case COLOR -> FuzzyMatch.filter(COLOR_NAMES, partial);
            case ADMIN -> FuzzyMatch.filter(List.of("true", "false"), partial);
            case INHERIT -> FuzzyMatch.filter(inheritCandidates(priorArgs.get(0)), partial);
            case REMPERM -> FuzzyMatch.filter(heldPermissions(priorArgs.get(0)), partial);
            default -> List.of();
        };
    }

    private List<String> inheritCandidates(String rankId)
    {
        final List<String> candidates = new ArrayList<>(List.of("none"));
        plugin().rm.getCustomRanksSorted()
                   .stream()
                   .map(CustomRank::getId)
                   .filter(id -> !id.equalsIgnoreCase(rankId.trim()))
                   .forEach(candidates::add);

        return candidates;
    }

    private List<String> heldPermissions(String rankId)
    {
        final CustomRank rank = plugin().rm.getCustomRank(rankId.trim().toLowerCase());
        return rank == null ? List.of() : rank.getPermissions().stream().sorted().toList();
    }

    private static Property parseProperty(String typed)
    {
        return Arrays.stream(Property.values())
                     .filter(property -> property.name().equalsIgnoreCase(typed.trim()))
                     .findFirst()
                     .orElse(null);
    }

    @Callback
    @Subcommand("setrank")
    public void setRank(CommandSender sender, Player target, String rank)
    {
        requireManageRanks(sender);

        final String rankId = rank.toLowerCase();
        if (rankId.equals("none") || rankId.equals("clear"))
        {
            final Admin admin = plugin().al.getAdmin(target);
            if (admin == null)
            {
                msg(sender, "<red>Player is not an admin, no custom rank to clear.");
                return;
            }

            admin.setRankId(plugin().rm.getRegistry().byRole(RankRole.ADMIN_DEFAULT)
                                       .map(CustomRank::getId)
                                       .orElse(null));
            plugin().al.saveAsync();
            msg(sender, "<green>Reset <player> to the baseline admin rank.", Placeholder.unparsed("player", target.getName()));
            return;
        }

        final CustomRank customRank = plugin().rm.getCustomRank(rankId);
        if (customRank == null)
        {
            msg(sender, "<red>Rank not found: <rank>", Placeholder.unparsed("rank", rankId));
            return;
        }

        final Admin admin = plugin().al.getAdmin(target);
        if (admin == null)
        {
            msg(sender, "<red>Player must be an admin to have a custom rank assigned.");
            msg(sender, "<gray>Add them to the admin list first using /saconfig add <player>", Placeholder.unparsed("player", target.getName()));
            return;
        }

        admin.setRankId(rankId);
        plugin().al.saveAsync();

        adminAction(
            sender,
            "<aqua>Set <player>'s custom rank to <rank>",
            Placeholder.unparsed("player", target.getName()),
            Placeholder.unparsed("rank", customRank.getName())
        );
    }

    @Completer(value = "setrank", position = 1)
    public List<String> completeSetRankRank(CommandSender sender, String partial)
    {
        return rankIdCandidates(partial);
    }

    @Callback
    @Subcommand("reload")
    public void reload(CommandSender sender)
    {
        requireManageRanks(sender);

        plugin().rm.loadRanks();
        adminAction(sender, "<aqua>Reloaded rank configuration");
        msg(sender, "<green>Ranks reloaded from file.");
        msg(sender, "<menu>", MessageUtils.component("menu", plugin().rm.buildMainMenu()));
    }

    @Callback
    @Subcommand("save")
    public void save(CommandSender sender)
    {
        requireManageRanks(sender);

        plugin().rm.saveRanks();
        msg(sender, "<green>Ranks saved to file.");
    }

    private List<String> rankIdCandidates(String partial)
    {
        final List<String> ids = plugin().rm
                                   .getCustomRanksSorted()
                                   .stream()
                                   .map(CustomRank::getId)
                                   .toList();
                                   
        return FuzzyMatch.filter(ids, partial);
    }

    private void requireManageRanks(CommandSender sender)
    {
        if (!plugin().rm.canManageRanks(sender))
        {
            throw new CommandFailException("You do not have permission to manage ranks.");
        }
    }

    private static boolean isTruthy(String value)
    {
        final String trimmed = value.trim();
        return trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("yes") || trimmed.equalsIgnoreCase("y");
    }

    private static NamedTextColor parseColor(String name)
    {
        return switch (name.toLowerCase().replace("_", ""))
        {
            case "black" -> NamedTextColor.BLACK;
            case "darkblue" -> NamedTextColor.DARK_BLUE;
            case "darkgreen" -> NamedTextColor.DARK_GREEN;
            case "darkaqua", "darkcyan" -> NamedTextColor.DARK_AQUA;
            case "darkred" -> NamedTextColor.DARK_RED;
            case "darkpurple", "purple" -> NamedTextColor.DARK_PURPLE;
            case "gold", "orange" -> NamedTextColor.GOLD;
            case "gray", "grey" -> NamedTextColor.GRAY;
            case "darkgray", "darkgrey" -> NamedTextColor.DARK_GRAY;
            case "blue" -> NamedTextColor.BLUE;
            case "green" -> NamedTextColor.GREEN;
            case "aqua", "cyan" -> NamedTextColor.AQUA;
            case "red" -> NamedTextColor.RED;
            case "lightpurple", "pink", "magenta" -> NamedTextColor.LIGHT_PURPLE;
            case "yellow" -> NamedTextColor.YELLOW;
            default -> NamedTextColor.WHITE;
        };
    }

    private enum Property
    {
        NAME, ABBREVIATION, LEVEL, COLOR, DETERMINER, ADMIN, PREFIX, INHERIT, ADDPERM, REMPERM
    }
}

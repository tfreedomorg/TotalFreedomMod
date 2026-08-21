package me.totalfreedom.totalfreedommod.cmd;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.PluginProvider;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.player.FPlayer;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.rank.CustomRank;
import me.totalfreedom.totalfreedommod.util.AdventureUtil;

@Command(name = "tag", description = "Sets yourself a prefix", usage = "/tag [-s[ave]] <set <tag..> | list | off | clear <player> | clearall>")
@Permission(permission = "tfm.player.tag")
public class Command_tag extends FCommand
{
    public static final int MAX_TAG_LENGTH = 20;

    /**
     * This is the exact same logic as /nickname. I'd put it in FCommand but honestly these two classes are the only two that need it.
     */
    public static final List<String> FORBIDDEN_WORDS = List.of(
        "admin", "owner", "moderator", "developer", "console"
    );

    @Callback
    @Subcommand("list")
    public void listTags(CommandSender sender)
    {
        msg(sender, "<gray>Tags for all online players:");

        for (final Player player : server().getOnlinePlayers())
        {
            final FPlayer playerdata = plugin().pl.getPlayer(player);
            if (playerdata.getTag() != null)
            {
                msg(
                    sender,
                    "<gray><player>: <tag>",
                    Placeholder.unparsed("player", player.getName()),
                    MessageUtils.component("tag", playerdata.getTag())
                );
            }
        }
    }

    @Callback
    @Subcommand("set")
    public void setTag(CommandSender sender, @Greedy String tag, @Switch("s") boolean shortSave, @Switch("save") boolean longSave)
    {
        if (!(sender instanceof Player playerSender))
        {
            msg(sender, "<gray>\"/tag set\" can't be used from the console.");
            return;
        }

        final Component processed = AdventureUtil.format(tag);
        final String rawTag = AdventureUtil.componentToPlainText(processed).toLowerCase().trim();

        if (rawTag.isEmpty())
        {
            msg(sender, "<gray>Your tag cannot be empty.");
            return;
        }

        if (rawTag.length() > MAX_TAG_LENGTH)
        {
            msg(sender, "<gray>That tag is too long (Max is <max> characters).", Formatter.number("max", MAX_TAG_LENGTH));
            return;
        }

        if (!isAdmin(sender) && containsForbidden(rawTag))
        {
            msg(sender, "<gray>That tag contains a forbidden word.");
            return;
        }

        final FPlayer player = plugin().pl.getPlayer(playerSender);
        player.setTag(tag);

        msg(sender, "<gray>Tag set to '<tag>'.", MessageUtils.component("tag", player.getTag()));

        if (shortSave || longSave)
        {
            if (!plugin().pl.saveCurrentTag(playerSender))
            {
                msg(sender, "<gray>Could not save your tag.");
                return;
            }

            msg(sender, "<gray>Your tag has been saved and will persist until cleared.");
        }
    }

    @Callback
    @Subcommand("clearall")
    public void clearAllTags(CommandSender sender)
    {
        if (!isAdmin(sender))
        {
            throw new CommandFailException("You do not have permission to use this command.");
        }

        adminAction(sender, "<aqua>Removing all tags");

        final String clearedTagValue = getClearedTagValue();
        int count = 0;

        for (final Player player : server().getOnlinePlayers())
        {
            final FPlayer playerdata = plugin().pl.getPlayer(player);
            final PlayerData data = plugin().pl.getData(player);

            if (hasTag(playerdata.getInternalTag()) || hasTag(data.getSavedTag()))
            {
                count++;
                playerdata.setTag(clearedTagValue);
                plugin().pl.clearSavedTag(player);
            }
        }

        msg(sender, "<gray><count> tag(s) removed.", Formatter.number("count", count));
    }

    @Callback
    @Subcommand("off")
    public void removeTag(CommandSender sender)
    {
        if (!(sender instanceof Player player))
        {
            msg(sender, "<gray>\"/tag off\" can't be used from the console. Use \"/tag clear <player>\" or \"/tag clearall\" instead.");
            return;
        }

        clearPlayerTag(player);
        msg(sender, "<gray>Your tag has been removed.");
    }

    @Completer(value = "clear", position = 0)
    public List<String> completeClearTarget(CommandSender sender, String partial)
    {
        return NameCandidates.online(server(), partial);
    }

    @Callback
    @Subcommand("clear")
    public void clearTag(CommandSender sender, String playerName)
    {
        if (!isAdmin(sender))
        {
            throw new CommandFailException("You do not have permission to use this command.");
        }

        final Player player = getPlayer(playerName);
        clearPlayerTag(player);
        msg(sender, "<gray>Removed <player>'s tag.", Placeholder.unparsed("player", player.getName()));
    }

    private String getClearedTagValue()
    {
        final Boolean enforcePrefixConfig = ConfigEntry.VAULT_CHAT_ENFORCE_PREFIX.getBoolean();
        final boolean enforcePrefix = enforcePrefixConfig != null && enforcePrefixConfig;
        return !enforcePrefix ? "" : null;
    }

    private static boolean hasTag(String tag)
    {
        return tag != null && !tag.isEmpty();
    }

    private void clearPlayerTag(Player player)
    {
        plugin().pl.getPlayer(player).setTag(getClearedTagValue());
        plugin().pl.clearSavedTag(player);
    }

    public static boolean containsForbidden(String plainText)
    {
        final List<String> terms = new ArrayList<>(FORBIDDEN_WORDS);

        PluginProvider.get()
                      .rm
                      .getCustomRanksSorted()
                      .stream()
                      .filter(CustomRank::isAdmin)
                      .filter(r -> !r.getTag().isEmpty())
                      .forEach(r -> terms.add(r.getTag()));

        final Pattern forbidden = Pattern.compile(
            terms.stream().map(Pattern::quote).collect(Collectors.joining("|")),
            Pattern.CASE_INSENSITIVE
        );

        return forbidden.matcher(plainText).find();
    }
}

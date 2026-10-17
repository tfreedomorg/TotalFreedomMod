package me.totalfreedom.totalfreedommod.cmd;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.PluginProvider;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.rank.CustomRank;
import me.totalfreedom.totalfreedommod.util.AdventureUtil;
import me.totalfreedom.totalfreedommod.util.FUtil;

@Command(
        name = "nickname",
        description = "Manages player nicknames",
        usage = "/nickname <<nickname..> | set <player> <nickname..> | clean | clear [player] | clearall>",
        aliases = {"nick"}
)
@Permission(permission = "tfm.player.nickname")
public class Command_nickname extends FCommand
{
    public static final int MAX_NICKNAME_LENGTH = 30;

    /**
     * Staff rank tags (e.g. "[SA]", "[SrA]") are pulled live from {@link Rank}/{@link CustomRank}
     * in {@link #containsForbidden} instead of being hardcoded here, so this list never drifts
     * out of sync with whatever ranks are actually configured.
     */
    public static final List<String> FORBIDDEN_WORDS = List.of(
        "admin", "owner", "moderator", "mod", "dev", "developer", "console"
    );

    @Callback
    public void setOwn(CommandSender sender, @Greedy String nickname)
    {
        if (!(sender instanceof Player player))
        {
            msg(sender, "<gray>The console may not have a nickname.");
            return;
        }

        applyNickname(sender, player, nickname, isAdmin(sender));
    }

    @Callback
    @Subcommand("set")
    public void setOther(CommandSender sender, Player player, @Greedy String nickname)
    {
        if (!isAdmin(sender))
        {
            msg(sender, "<gray>You may not change someone else's nickname.");
            return;
        }

        applyNickname(sender, player, nickname, true);
    }

    private void applyNickname(CommandSender sender, Player player, String nickname, boolean isAdmin)
    {
        final String strippedInput = nickname.trim().replace('§', '&');
        if (!AdventureUtil.hasVisibleText(strippedInput))
        {
            return;
        }

        final Component colorizedNickname = FUtil.colorize(strippedInput);
        final String plainNickname = AdventureUtil.componentToPlainText(colorizedNickname).trim();
        if (!AdventureUtil.hasVisibleText(plainNickname))
        {
            return;
        }

        final String rawNickname = plainNickname.toLowerCase();

        if (rawNickname.length() > MAX_NICKNAME_LENGTH)
        {
            msg(sender, "<gray>That nickname is too long (Max is <max> characters).", Formatter.number("max", MAX_NICKNAME_LENGTH));
            return;
        }

        if (!isAdmin && containsForbidden(rawNickname))
        {
            msg(sender, "<gray>That tag contains a forbidden word.");
            return;
        }

        final PlayerData data = plugin().pl.getData(player);

        // Plaintext match to own username with no styling is equivalent to no nickname.
        if (plainNickname.equalsIgnoreCase(player.getName()) && !AdventureUtil.hasVisualStyle(colorizedNickname))
        {
            if (data.hasCustomNickname())
            {
                clearNickname(player);
                sendClearedMessage(sender, player);
            }
            return;
        }

        data.setNickname(colorizedNickname);

        if (player.equals(sender))
        {
            msg(sender, "<gray>Nickname set to '<nickname>'.", MessageUtils.component("nickname", data.getDisplayedNickname()));
        }
        else
        {
            msg(
                sender,
                "<gray><player>'s nickname set to '<nickname>'.",
                Placeholder.unparsed("player", player.getName()),
                MessageUtils.component("nickname", data.getDisplayedNickname()) // this instead of Placeholder.unparsed because it does not process <tags>.
            );
        }
    }

    @Callback
    @Subcommand("clear")
    public void clearOwn(CommandSender sender)
    {
        if (!(sender instanceof Player player))
        {
            msg(sender, "<gray>The console may not have a nickname.");
            return;
        }

        clearNickname(player);
        sendClearedMessage(sender, player);
    }

    @Callback
    @Subcommand("clear")
    public void clearOther(CommandSender sender, Player player)
    {
        if (!isAdmin(sender))
        {
            throw new CommandFailException("You do not have permission to use this command.");
        }

        clearNickname(player);
        sendClearedMessage(sender, player);
    }

    @Callback
    @Subcommand("off")
    public void off(CommandSender sender)
    {
        clearOwn(sender);
    }

    @Callback
    @Subcommand("clearall")
    public void clearAll(CommandSender sender)
    {
        if (!isAdmin(sender))
        {
            throw new CommandFailException("You do not have permission to use this command.");
        }

        adminAction(sender, "<red>Removing all nicknames");

        final long count = server().getOnlinePlayers()
                .stream()
                .map(p -> plugin().pl.getData(p))
                .filter(d -> d.getNickname() != null)
                .reduce(0L, (total, d) ->
                    {
                        d.setNickname(null);
                        return total + 1;
                    }, Long::sum);

        msg(sender, "<gray><count> nickname(s) removed.", Formatter.number("count", count));
    }

    @Callback
    @Subcommand("clean")
    @Permission(permission = "tfm.admin.nickclean")
    public void clean(CommandSender sender)
    {
        adminAction(sender, "<aqua>Cleaning all nicknames");

        final long count = server().getOnlinePlayers().stream()
            .map(p -> plugin().pl.getData(p))
            .filter(data -> data.getNickname() != null && data.hasCustomNickname())
            .filter(data ->
            {
                Component before = data.getNickname();
                Component cleaned = cleanComponent(before);

                if (before.equals(cleaned))
                {
                    return false;
                }

                data.setNickname(cleaned);

                msg(
                    sender,
                    "<gray><user>: \"<before>\" -> \"<after>\"",
                    Placeholder.unparsed("user", data.getUsername()),
                    MessageUtils.component("before", before),
                    MessageUtils.component("after", cleaned)
                );
                return true;
            })
            .count();

        msg(sender, "<gray><count> nickname(s) were cleaned.", Formatter.number("count", count));
    }

    private void sendClearedMessage(CommandSender sender, Player player)
    {
        if (player.equals(sender))
        {
            msg(sender, "<gray>Removed your nickname.");
        }
        else
        {
            msg(sender, "<gray>Removed <player>'s nickname.", Placeholder.unparsed("player", player.getName()));
        }
    }

    private void clearNickname(Player player)
    {
        plugin().pl
                .getData(player)
                .setNickname(null);
    }

    private Component cleanComponent(Component component)
    {
        final Style rawStyle = component.style()
            .decoration(TextDecoration.OBFUSCATED, TextDecoration.State.FALSE)
            .decoration(TextDecoration.STRIKETHROUGH, TextDecoration.State.FALSE)
            .decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE)
            .decoration(TextDecoration.UNDERLINED, TextDecoration.State.FALSE);

        final Style style = NamedTextColor.BLACK.equals(rawStyle.color()) ? rawStyle.color(null) : rawStyle;

        return component.style(style).children(
                    component.children()
                             .stream()
                             .map(this::cleanComponent)
                             .toList()
                    );
    }

    public static boolean containsForbidden(String plainText)
    {
        final List<String> terms = new ArrayList<>(FORBIDDEN_WORDS);

        PluginProvider.get()
                      .rm
                      .getCustomRanksSorted()
                      .stream()
                      .filter(r -> r.isAdmin()) // for some reason, this Rank::isAdmin is giving me some sort of null warning. Same as above.
                      .filter(r -> !r.getTag().isEmpty())
                      .forEach(r -> terms.add(r.getTag()));

        final Pattern forbidden = Pattern.compile(
            terms.stream().map(Pattern::quote).collect(Collectors.joining("|")),
            Pattern.CASE_INSENSITIVE
        );

        return forbidden.matcher(plainText).find();
    }
}

package me.totalfreedom.totalfreedommod.cmd;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.banning.Ban;
import me.totalfreedom.totalfreedommod.cmd.internal.FuzzyMatch;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.cmd.resolver.DateOffsetArgumentResolver;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.util.FUtil;

@Command(name = "tempban", aliases = {"tban", "noob"},
        description = "Temporarily bans an online or previously known player.",
        usage = "/<command> [-s] [-rb] <player> [duration] [reason]")
@Permission(permission = "tfm.admin.ban")
public class Command_tempban extends FCommand
{
    private static final String DEFAULT_DURATION = "5m";

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd 'at' HH:mm:ss z");

    @Callback
    public void tempBanDefault(CommandSender sender, String name, @Switch("s") boolean silent, @Switch("rb") boolean rollback)
    {
        tempBan(sender, name, defaultExpiry(), null, silent, rollback);
    }

    /**
     * Takes everything after the player name as one greedy argument so the duration can be
     * omitted. 
     * <p>
     * Brigadier cannot route an optional duration followed by a free-form reason; both
     * are string-like, so the sibling nodes would overlap and resolution would be order-dependent
     * so the tail is split here instead.
     */
    @Callback
    public void tempBanWithArgs(CommandSender sender, String name, @Greedy String args, @Switch("s") boolean silent, @Switch("rb") boolean rollback)
    {
        final String tail = args.strip();
        final int split = tail.indexOf(' ');
        final String head = split == -1 ? tail : tail.substring(0, split);

        final Date parsed = FUtil.parseDateOffset(head);
        final String c = (split == -1) ? null : tail.substring(split + 1).strip();
        final String reason = (parsed) == null ? tail : c;

        tempBan(
            sender,
            name,
            parsed != null ? parsed : defaultExpiry(),
            reason == null || reason.isEmpty() ? null : reason,
            silent,
            rollback
        );
    }

    private static Date defaultExpiry()
    {
        return FUtil.parseDateOffset(DEFAULT_DURATION);
    }

    /**
     * The target is a plain {@code String} rather than a {@code Player} so that offline and
     * previously-known names can be banned, which means it gets no suggestions of its own.
     */
    @Completer(value = "", position = 0)
    public List<String> completeTarget(CommandSender sender, String partial)
    {
        return NameCandidates.online(server(), partial);
    }

    /**
     * The tail is a greedy string, so it carries no resolver suggestions of its own.
     */
    @Completer(value = "", position = 1)
    public List<String> completeDuration(CommandSender sender, String partial)
    {
        return FuzzyMatch.filter(DateOffsetArgumentResolver.COMMON_OFFSETS, partial);
    }

    private void tempBan(CommandSender sender, String name, Date expiry, String reason, boolean silent, boolean rollback)
    {
        final Player player = server().getPlayer(name);
        final PlayerData data = BanCommandUtil.getData(plugin(), name, player);
        final String canonicalName = BanCommandUtil.getCanonicalName(name, player, data);

        if (isProtectedAdminByName(sender, canonicalName))
            return;

        if (plugin().bm.getByUsername(canonicalName) != null)
        {
            msg(sender, "<gray><player> is already banned.", Placeholder.unparsed("player", canonicalName));
            return;
        }

        final Ban ban = (player == null && data == null)
                ? Ban.forPlayerName(canonicalName, sender, expiry, reason)
                : BanCommandUtil.createFullBan(canonicalName, BanCommandUtil.getIps(player, data), sender, expiry, reason);

        plugin().bm.addBan(ban);

        if (!silent)
        {
            adminAction(sender, "<red>Temporarily banning <player> until <until><include_reason:\" - Reason: <yellow><reason>\":\"\">",
                        Placeholder.unparsed("player", canonicalName),
                        Placeholder.unparsed("until", dateFormat.format(expiry)),
                        Formatter.booleanChoice("include_reason", reason != null && !reason.isEmpty()),
                        Placeholder.unparsed("reason", reason != null ? reason : "")
                    );

            plugin().db.sendTBanMessage(sender.getName(), canonicalName, reason, dateFormat.format(expiry));
        }

        if (rollback)
            plugin().cpb.rollback(canonicalName);

        if (data != null && plugin().al.getEntryByName(canonicalName) == null)
        {
            data.setStrikes(data.getStrikes() + 1);
            plugin().pl.saveData(data);
        }

        final List<String> ips = ban.getIps();
        server().getOnlinePlayers()
                .stream()
                .filter(suspect -> suspect.equals(player)
                        || (suspect.getAddress() != null && ips.contains(Objects.requireNonNull(suspect.getAddress()).getAddress().getHostAddress())))
                .forEach(target ->
                {
                    if (!silent)
                        smitePlayer(target);
                    
                    target.kick(ban.bakeKickMessage());
                });
    }
}

package me.totalfreedom.totalfreedommod.cmd;

import me.totalfreedom.totalfreedommod.discord.DiscordBridge;

import me.totalfreedom.totalfreedommod.bridge.CoreProtectBridge;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.banning.Ban;
import me.totalfreedom.totalfreedommod.cmd.internal.FuzzyMatch;
import me.totalfreedom.api.cmd.FCommand;
import me.totalfreedom.api.cmd.annotation.*;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.api.player.PlayerData;
import me.totalfreedom.totalfreedommod.util.FUtil;

@Command(name = "tempban", aliases = {"tban", "noob"},
        description = "Temporarily bans an online or previously known player.",
        usage = "/<command> [-s] [-rb] <player> [duration] [reason]")
@Permission(permission = "tfm.admin.ban")
public class Command_tempban extends FCommand
{
    private static final String DEFAULT_DURATION = "5m";
    private static final long MAX_DURATION_MS = TimeUnit.HOURS.toMillis(24);

    /**
     * Offered in place of the resolver's full offset list, which runs out to 1y. Anything
     * past 24h would only be capped back down.
     */
    private static final List<String> DURATIONS = List.of("30s", "5m", "15m", "30m", "1h", "6h", "12h", "24h");

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
        return FuzzyMatch.filter(DURATIONS, partial);
    }

    private void tempBan(CommandSender sender, String name, Date requested, String reason, boolean silent, boolean rollback)
    {
        final Date ceiling = new Date(System.currentTimeMillis() + MAX_DURATION_MS);
        final boolean capped = requested.after(ceiling);
        final Date expiry = capped ? ceiling : requested;

        if (capped)
            msg(sender, "<gray>Temporary bans are capped at 24 hours.");

        final Player player = server().getPlayer(name);
        final PlayerData data = BanCommandUtil.getData(plugin(), name, player);
        final String canonicalName = BanCommandUtil.getCanonicalName(name, player, data);

        if (isProtectedAdminByName(sender, canonicalName))
            return;

        if (plugin().bans().getByUsername(canonicalName) != null)
        {
            msg(sender, "<gray><player> is already banned.", Placeholder.unparsed("player", canonicalName));
            return;
        }

        final Ban ban = (player == null && data == null)
                ? Ban.forPlayerName(canonicalName, sender, expiry, reason)
                : BanCommandUtil.createFullBan(canonicalName, BanCommandUtil.getIps(player, data), sender, expiry, reason);

        plugin().bans().addBan(ban);

        if (!silent)
        {
            adminAction(sender, "<red>Temporarily banning <player> until <until><include_reason:\" - Reason: <yellow><reason>\":\"\">",
                        Placeholder.unparsed("player", canonicalName),
                        Placeholder.unparsed("until", dateFormat.format(expiry)),
                        Formatter.booleanChoice("include_reason", reason != null && !reason.isEmpty()),
                        Placeholder.unparsed("reason", reason != null ? reason : "")
                    );
            plugin().services()
					.require(DiscordBridge.class)
					.sendTBanMessage(sender.getName(), canonicalName, reason, dateFormat.format(expiry));
        }

        if (rollback)
            plugin().bridges().require(CoreProtectBridge.class).rollback(canonicalName);

        if (data != null && plugin().admins().getEntryByName(canonicalName) == null)
        {
            data.setStrikes(data.getStrikes() + 1);
            plugin().players().saveData(data);
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

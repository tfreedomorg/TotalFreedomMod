package me.totalfreedom.totalfreedommod.discord;

import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.object.command.ApplicationCommandInteractionOption;
import discord4j.core.object.command.ApplicationCommandInteractionOptionValue;
import discord4j.core.spec.InteractionApplicationCommandCallbackSpec;
import reactor.core.publisher.Mono;

import me.totalfreedom.api.FreedomAPI;
import me.totalfreedom.totalfreedommod.cmd.MessageUtils;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.PlayerListUtil;

/**
 * Slash command handlers for {@code /list}, {@code /link}, {@code /unlink}, {@code /verify}.
 * <p>
 * Two threading rules run through all of this. Repository work is JDBC and blocks, so it goes onto
 * {@code boundedElastic} rather than running where the interaction arrived; and anything reading
 * server state hops to the main thread through {@link DiscordBridge#mainThread()}. Neither was a
 * concern under JDA, whose listener threads tolerated both.
 */
public class DiscordCommands
{

    private final FreedomAPI plugin;
    private final DiscordBridge bridge;

    public DiscordCommands(FreedomAPI plugin, DiscordBridge bridge)
    {
        this.plugin = plugin;
        this.bridge = bridge;
    }

    /**
     * The command half of the connection. Each interaction gets its own error boundary: an escaping
     * error would terminate the stream, and Discord would then show every later command timing out
     * with nothing in the log to say why.
     */
    public Mono<Void> bind(final GatewayDiscordClient gateway)
    {
        return gateway.on(ChatInputInteractionEvent.class)
                      .flatMap(event -> handle(event).onErrorResume(thrown ->
                                                  {
                                                      FLog.warn("[Discord] /{} failed: {}",
                                                              event.getCommandName(),
                                                              DiscordConnection.describeFailure(thrown));
                                                      return Mono.empty();
                                                  }))
                      .then();
    }

    private Mono<Void> handle(final ChatInputInteractionEvent event)
    {
        return switch (event.getCommandName())
        {
            case "list" -> handleList(event);
            case "verify" -> handleVerify(event);
            case "link" -> handleLink(event);
            case "unlink" -> handleUnlink(event);
            default -> Mono.empty();
        };
    }

    private Mono<Void> handleList(final ChatInputInteractionEvent event)
    {
        final String filter = stringOption(event, "filter").orElse("");

        return event.deferReply()
                    .then(Mono.fromCallable(() -> "```\n" + buildList(filter) + "\n```")
                              .subscribeOn(bridge.mainThread()))
                    .flatMap(body -> event.createFollowup(body).then());
    }

    private String buildList(final String filter)
    {
        return switch (filter)
        {
            case "admins" -> PlayerListUtil.buildFilteredList("Admin", plugin.ranks()::isAdmin);
            case "impostors" -> PlayerListUtil.buildFilteredList("Impostor", plugin.players()::isImpostor);
            case "famous" -> PlayerListUtil.buildFilteredList("Famous Player", PlayerListUtil::isFamous);
            default -> PlayerListUtil.buildRankList();
        };
    }

    /**
     * Re-registers an impostor-flagged player as the admin their name belongs to.
     * <p>
     * The code only reaches the person in game, so possessing it proves nothing on its own. The
     * submitting Discord account must also be the one linked to that admin entry.
     */
    private Mono<Void> handleVerify(final ChatInputInteractionEvent event)
    {
        final String code = stringOption(event, "code").map(text -> text.trim().toUpperCase()).orElse("");
        final Optional<DiscordBridge.PendingVerification> pending = bridge.findPendingVerification(code);

        if (pending.isEmpty())
            return replyPrivately(event, "That code is unknown or expired. Rejoin the server for a fresh one.");

        final DiscordBridge.PendingVerification verification = pending.get();
        final String discordUserId = event.getInteraction().getUser().getId().asString();

        return Mono.fromCallable(() -> verify(code, verification, discordUserId))
                   .subscribeOn(bridge.mainThread())
                   .flatMap(outcome -> replyPrivately(event, outcome))
                   .onErrorResume(thrown ->
                   {
                    FLog.warn("[Discord] /verify failed: {}", DiscordConnection.describeFailure(thrown));
                    return replyPrivately(event, "Couldn't verify you, see the server log.");
                   });
    }

    private String verify(final String code, final DiscordBridge.PendingVerification verification, final String discordUserId)
    {
        final boolean linked = plugin.players()
                                     .getStaffByDiscordId(discordUserId)
                                     .map(PlayerData::getUuid)
                                     .filter(verification.adminUuid()::equals)
                                     .isPresent();

        if (!linked)
            return "That code isn't for your linked account.";

        if (!bridge.completeVerification(code, verification))
            return "That code was already used or has expired.";

        final Player player = Bukkit.getPlayer(verification.playerUuid());
        if (player == null)
            return "That player is no longer online. Rejoin the server for a fresh code.";

        final PlayerData admin = plugin.players().getData(player);
        if (!admin.isActiveStaff())
            return "That admin entry is no longer active.";

        // Same as /saconfig add: being online on the linked account vouches for this address.
        final String ip = player.getAddress().getAddress().getHostAddress();
        admin.setIp(ip);
        admin.trustIp(ip);
        admin.getFreezeData().setFrozen(false);
        plugin.players().saveData(admin);

        player.setOp(true);
        player.updateCommands();
        plugin.ranks().updatePlayerTeam(player);
        MessageUtils.send(player, "<green>You have been verified as an admin.");

        FLog.info("[Discord] Verified {} as admin.", player.getName());
        return String.format("Verified **%s**. You are an admin in game again.", player.getName());
    }

    private static Optional<String> stringOption(final ChatInputInteractionEvent event, final String name)
    {
        return event.getOption(name)
                    .flatMap(ApplicationCommandInteractionOption::getValue)
                    .map(ApplicationCommandInteractionOptionValue::asString);
    }

    private Mono<Void> handleLink(final ChatInputInteractionEvent event)
    {
        final Optional<String> code = stringOption(event, "code");

        if (code.isEmpty())
            return replyPrivately(event, "Missing `code` argument.");

        final Optional<UUID> pendingUuid = bridge.consumePendingLink(code.get().trim().toUpperCase());
        if (pendingUuid.isEmpty())
            return replyPrivately(event, "That code is unknown or expired. Run `/link` in-game to get a fresh one.");

        final UUID adminUuid = pendingUuid.get();
        final String discordUserId = event.getInteraction().getUser().getId().asString();

        // Player records belong to the main thread, so the lookup and the write both happen there.
        return Mono.fromCallable(() -> Optional.ofNullable(plugin.players().getData(adminUuid))
                                               .filter(PlayerData::isActiveStaff)
                                               .map(admin -> new LinkResult(admin.getUsername(), admin.getRankId().orElse("?"),
                                                       plugin.players().linkDiscord(admin, discordUserId))))
                   .subscribeOn(bridge.mainThread())
                   .flatMap(result ->
                   {
                       if (result.isEmpty())
                           return replyPrivately(event, "Internal error: admin record for the code is gone. Try again.");

                       final LinkResult linked = result.get();
                       FLog.info("[Discord] Linked admin {} to Discord user {} as {}.",
                               linked.username(), discordUserId, linked.identifier());
                       return replyPrivately(event, String.format("Linked as **%s** (%s). Your identifier is `%s`.",
                               linked.username(), linked.rankId(), linked.identifier()));
                   });
    }

    private Mono<Void> handleUnlink(final ChatInputInteractionEvent event)
    {
        final String discordUserId = event.getInteraction().getUser().getId().asString();

        return Mono.fromCallable(() -> removeLink(discordUserId))
                   .subscribeOn(bridge.mainThread())
                   .flatMap(outcome -> replyPrivately(event, switch (outcome)
                   {
                       case REMOVED -> "Link removed.";
                       case NOT_LINKED -> "You aren't linked.";
                   }));
    }

    private UnlinkOutcome removeLink(final String discordUserId)
    {
        final Optional<PlayerData> linked = plugin.players().getStaffByDiscordId(discordUserId);
        if (linked.isEmpty())
            return UnlinkOutcome.NOT_LINKED;

        plugin.players().unlinkDiscord(linked.get());
        FLog.info("[Discord] Unlinked Discord user {} from {}.", discordUserId, linked.get().getUsername());
        return UnlinkOutcome.REMOVED;
    }

    private static Mono<Void> replyPrivately(final ChatInputInteractionEvent event, final String message)
    {
        return event.reply(InteractionApplicationCommandCallbackSpec.builder()
                    .content(message)
                    .ephemeral(true)
                    .build());
    }

    private enum UnlinkOutcome
    {
        REMOVED,
        NOT_LINKED
    }

    /**
     * What a successful {@code /link} reports back, read on the main thread.
     */
    private record LinkResult(String username, String rankId, String identifier) {}
}

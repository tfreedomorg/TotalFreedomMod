package me.totalfreedom.totalfreedommod.discord;

import java.util.Optional;
import java.util.UUID;

import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.object.command.ApplicationCommandInteractionOption;
import discord4j.core.object.command.ApplicationCommandInteractionOptionValue;
import discord4j.core.spec.InteractionApplicationCommandCallbackSpec;
import reactor.core.publisher.Mono;

import me.totalfreedom.api.FreedomAPI;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.PlayerListUtil;

/**
 * Slash command handlers for {@code /list}, {@code /link}, {@code /unlink}.
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
            case "link" -> handleLink(event);
            case "unlink" -> handleUnlink(event);
            default -> Mono.empty();
        };
    }

    private Mono<Void> handleList(final ChatInputInteractionEvent event)
    {
        return event.deferReply()
                    .then(Mono.fromCallable(() -> "```\n" + PlayerListUtil.buildRankList() + "\n```")
                              .subscribeOn(bridge.mainThread()))
                    .flatMap(body -> event.createFollowup(body).then());
    }

    private Mono<Void> handleLink(final ChatInputInteractionEvent event)
    {
        final Optional<String> code = event.getOption("code")
                                           .flatMap(ApplicationCommandInteractionOption::getValue)
                                           .map(ApplicationCommandInteractionOptionValue::asString);

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

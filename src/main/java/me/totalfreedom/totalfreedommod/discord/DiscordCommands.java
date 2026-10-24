package me.totalfreedom.totalfreedommod.discord;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.object.command.ApplicationCommandInteractionOption;
import discord4j.core.object.command.ApplicationCommandInteractionOptionValue;
import discord4j.core.spec.InteractionApplicationCommandCallbackSpec;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.admin.Admin;
import me.totalfreedom.totalfreedommod.sql.adapter.DiscordLinkRepository;
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

    private final TotalFreedomMod plugin;
    private final DiscordBridge bridge;

    public DiscordCommands(TotalFreedomMod plugin, DiscordBridge bridge)
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
                                                      FLog.warning(String.format(
                                                                                  "[Discord] /%s failed: %s",
                                                                                  event.getCommandName(), 
                                                                                  DiscordConnection.describeFailure(thrown)
                                                                                ));
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
        final Optional<Admin> admin = Optional.ofNullable(plugin.al.getAdminByUuid(adminUuid));
        if (admin.isEmpty())
            return replyPrivately(event, "Internal error: admin record for the code is gone. Try again.");

        final Admin linkedAdmin = admin.get();
        final String discordUserId = event.getInteraction().getUser().getId().asString();

        return Mono.fromCallable(() -> persistLink(linkedAdmin, adminUuid, discordUserId))
                   .subscribeOn(Schedulers.boundedElastic())
                   .flatMap(saved -> saved
                                    ? replyPrivately(event,
                                            "Linked as **" + linkedAdmin.getName() + "** (" + linkedAdmin.getRankId() + ").")
                                    : replyPrivately(event, "Couldn't save the link — see server log. Try again."));
    }

    private boolean persistLink(final Admin admin, final UUID adminUuid, final String discordUserId)
    {
        final DiscordLinkRepository repo = plugin.dm.getDiscordLinkRepository();
        try
        {
            repo.deleteByAdminUuid(adminUuid);
            repo.deleteByDiscordUserId(discordUserId);
            repo.insert(adminUuid, discordUserId);
        }
        catch (SQLException ex)
        {
            FLog.warning("[Discord] /link failed for " + admin.getName() + ": " + ex.getMessage());
            return false;
        }

        DiscordLinkJsonSync.writeSnapshot(plugin, repo);
        FLog.info("[Discord] Linked admin " + admin.getName() + " ↔ Discord user " + discordUserId + ".");
        return true;
    }

    private Mono<Void> handleUnlink(final ChatInputInteractionEvent event)
    {
        final String discordUserId = event.getInteraction().getUser().getId().asString();

        return Mono.fromCallable(() -> removeLink(discordUserId))
                   .subscribeOn(Schedulers.boundedElastic())
                   .flatMap(outcome -> replyPrivately(event, switch (outcome)
                   {
                       case REMOVED -> "Link removed.";
                       case NOT_LINKED -> "You aren't linked.";
                       case FAILED -> "Couldn't remove the link — see server log.";
                   }));
    }

    private UnlinkOutcome removeLink(final String discordUserId)
    {
        final DiscordLinkRepository repo = plugin.dm.getDiscordLinkRepository();
        final boolean removed;
        try
        {
            removed = repo.deleteByDiscordUserId(discordUserId);
        }
        catch (SQLException ex)
        {
            FLog.warning("[Discord] /unlink failed for " + discordUserId + ": " + ex.getMessage());
            return UnlinkOutcome.FAILED;
        }

        if (!removed)
            return UnlinkOutcome.NOT_LINKED;

        DiscordLinkJsonSync.writeSnapshot(plugin, repo);
        FLog.info("[Discord] Unlinked Discord user " + discordUserId + ".");
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
        NOT_LINKED,
        FAILED
    }
}

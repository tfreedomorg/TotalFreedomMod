package me.totalfreedom.totalfreedommod.discord;

import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;
import java.util.Set;

import discord4j.common.util.Snowflake;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.message.ReactionAddEvent;
import discord4j.core.object.Embed;
import discord4j.core.object.emoji.Emoji;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.channel.GuildMessageChannel;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.core.spec.MessageCreateSpec;
import discord4j.core.spec.MessageEditSpec;
import discord4j.discordjson.json.MessageReferenceData;
import discord4j.discordjson.possible.Possible;
import discord4j.rest.util.AllowedMentions;
import discord4j.rest.util.Color;
import discord4j.core.retriever.EntityRetrievalStrategy;

import me.totalfreedom.api.FreedomAPI;
import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.util.FLog;

public final class DiscordReportRelay
{
    private static final Emoji CHECKMARK =
            Emoji.unicode("✅");

    private static final String REPORT_TITLE = "Player Report";
    private static final String PENDING = "Status: Pending";
    private static final String HANDLED = "Status: Handled";

    private final FreedomAPI plugin;
    private final DiscordBridge bridge;

    // Prevent simultaneous reactions from handling the same report
    private final Set<Snowflake> processing =
            ConcurrentHashMap.newKeySet();

    public DiscordReportRelay(FreedomAPI plugin, DiscordBridge bridge)
    {
        this.plugin = plugin;
        this.bridge = bridge;
    }

    public Mono<Void> bind(final GatewayDiscordClient gateway)
    {
        return gateway.on(ReactionAddEvent.class)
                .flatMap(event -> handleReaction(gateway, event)
                        .onErrorResume(thrown ->
                        {
                            FLog.warn("[Discord] Failed to handle report reaction: {}", DiscordConnection.describeFailure(thrown));
                            return Mono.empty();
                        }))
                .then();
    }

    public void sendReport(final String reporter, final String reportedPlayer, final String reason)
    {
        bridge.getSession().ifPresent(session ->
                session.reportsChannel().ifPresent(channelId ->
                {
                    final EmbedCreateSpec embed =
                            EmbedCreateSpec.builder()
                                    .title(REPORT_TITLE)
                                    .color(Color.of(0xF0B232))
                                    .addField("Reporter",
                                            reporter, true)
                                    .addField("Reported Player",
                                            reportedPlayer, true)
                                    .addField("Reason",
                                            limitReason(reason), false)
                                    .footer(PENDING, null)
                                    .timestamp(Instant.now())
                                    .build();

                    session.gateway()
                            .getChannelById(channelId)
                            .ofType(GuildMessageChannel.class)
                            .filter(channel -> channel.getGuildId()
                                    .equals(session.guildId()))
                            .flatMap(channel -> channel.createMessage(
                                    MessageCreateSpec.builder()
                                            .addEmbed(embed)
                                            .allowedMentions(
                                                    AllowedMentions.suppressAll())
                                            .build()))
                            .flatMap(message ->
                                    message.addReaction(CHECKMARK))
                            .subscribe(
                                    ignored -> {},
                                    thrown -> FLog.warn(
                                            "[Discord] Failed to relay report: {}",
                                            DiscordConnection.describeFailure(thrown)));
                }));
    }

    private Mono<Void> handleReaction(final GatewayDiscordClient gateway, final ReactionAddEvent event)
    {
        if (!CHECKMARK.equals(event.getEmoji()))
            return Mono.empty();

        if (gateway.getSelfId().equals(event.getUserId()))
            return Mono.empty();

        if (bridge.currentReportsChannel().filter(event.getChannelId()::equals).isEmpty())
            return Mono.empty();

        if (event.getMember().filter(member -> !member.isBot()).isEmpty())
            return Mono.empty();

        final Snowflake messageId = event.getMessageId();

        return Mono.defer(() ->
        {
            if (!processing.add(messageId))
                return Mono.empty();

            return gateway
                    .withRetrievalStrategy(EntityRetrievalStrategy.REST)
                    .getMessageById(event.getChannelId(), messageId)
                    .filter(message -> isPendingReport(gateway, message))
                    .flatMap(message -> completeReport(
                            message, event.getUserId()))
                    .doFinally(signal -> processing.remove(messageId));
        });
    }

    private boolean isPendingReport(final GatewayDiscordClient gateway, final Message message)
    {
        if (message.getAuthor()
                .filter(author -> author.getId()
                        .equals(gateway.getSelfId()))
                .isEmpty())
            return false;

        if (message.getEmbeds().size() != 1)
            return false;

        final Embed embed = message.getEmbeds().getFirst();

        return embed.getTitle()
                .filter(REPORT_TITLE::equals)
                .isPresent()
                && embed.getFooter()
                .map(Embed.Footer::getText)
                .filter(PENDING::equals)
                .isPresent();
    }

    private Mono<Void> completeReport(final Message message, final Snowflake handlerId)
    {
        final Embed original = message.getEmbeds().getFirst();

        final EmbedCreateSpec.Builder updated =
                EmbedCreateSpec.builder()
                        .title(REPORT_TITLE)
                        .color(Color.of(0x57F287))
                        .footer(HANDLED, null);

        original.getTimestamp().ifPresent(updated::timestamp);

        // Keep the original report information
        original.getFields().forEach(field ->
                updated.addField(
                        field.getName(),
                        field.getValue(),
                        field.isInline()));

        updated.addField(
                "Handled By",
                "<@" + handlerId.asString() + ">",
                false);

        return message.edit(
                        MessageEditSpec.builder()
                                .addEmbed(updated.build())
                                .build())
                .then(message.getChannel()
                        .flatMap(channel -> channel.createMessage(
                                MessageCreateSpec.builder()
                                        .content("✅ Report handled by <@"
                                                + handlerId.asString() + ">")
                                        .messageReference(Possible.of(
                                                MessageReferenceData.builder()
                                                        .messageId(message.getId().asLong())
                                                        .channelId(message.getChannelId().asLong())
                                                        .build()))
                                        .allowedMentions(
                                                AllowedMentions.builder()
                                                        .allowUser(handlerId)
                                                        .build())
                                        .build())))
                .then();
    }

    private static String limitReason(final String reason)
    {
        if (reason == null || reason.isBlank())
            return "No reason provided.";

        if (reason.length() > 1024)
            return reason.substring(0, 1021) + "...";

        return reason;
    }
}
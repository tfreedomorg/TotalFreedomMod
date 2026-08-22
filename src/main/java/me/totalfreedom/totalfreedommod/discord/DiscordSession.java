package me.totalfreedom.totalfreedommod.discord;

import java.util.Optional;

import discord4j.common.util.Snowflake;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.object.entity.channel.MessageChannel;
import reactor.core.publisher.Mono;

/**
 * The bridge publishes this through one volatile reference and never mutates it.
 * One reference means a reader either sees a whole connection or sees none, and reconnecting is a single assignment rather than five.
 * <p>
 * Channels are held as {@link Snowflake} ids rather than entity objects. Discord4J entities carry a
 * reference to the client that produced them, so holding one across a reconnect would leave a relay
 * writing through a gateway that no longer exists. 
 *
 * @param gateway          the client this connection belongs to
 * @param guildId          configured guild, already verified to exist
 * @param guildName        resolved once for log lines, so logging never needs a fetch
 * @param publicChannel    public chat relay channel, absent when unconfigured or unresolvable
 * @param adminchatChannel admin chat relay channel, absent when unconfigured or unresolvable
 * @param consoleChannel   console relay channel, absent when unconfigured or unresolvable
 * @param channelSummary   resolved channel names for the startup log line
 */
public record DiscordSession(
                             GatewayDiscordClient gateway,
                             Snowflake guildId,
                             String guildName,
                             Optional<Snowflake> publicChannel,
                             Optional<Snowflake> adminchatChannel,
                             Optional<Snowflake> consoleChannel,
                             String channelSummary)
{
    /**
     * Resolve one of this connection's channels for sending.
     * <p>
     * Empty rather than an error when the channel is unconfigured or has since been deleted.
     */
    public Mono<MessageChannel> channel(final Optional<Snowflake> id)
    {
        return id.map(gateway::getChannelById)
                 .orElseGet(Mono::empty)
                 .ofType(MessageChannel.class);
    }

    /**
     * Describes the resolved channels for the startup log line.
     */
    public String describeChannels()
    {
        return channelSummary;
    }
}

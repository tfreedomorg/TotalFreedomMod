package me.totalfreedom.totalfreedommod.discord;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.gateway.intent.Intent;
import discord4j.gateway.intent.IntentSet;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;

/**
 * Owns the bridge's gateway connection and its reconnect budget.
 * <p>
 * Attempts are spaced by {@code discord.reconnect.interval_seconds} and capped at
 * {@code discord.reconnect.max_attempts} <em>consecutive</em> failures. Any successful connection
 * resets the count, so a server that drops once an hour retries forever, while one that cannot
 * reach Discord at all stops after the cap and stays down until restarted rather than reconnecting
 * in a loop against a token or an outage that is not going to recover on its own.
 */
public final class DiscordConnection
{
    private static final int MIN_INTERVAL_SECONDS = 5;

    private final int maxAttempts;
    private final int retrySeconds;
    private final Duration retryInterval;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean givenUp = new AtomicBoolean();

    private volatile Optional<Disposable> subscription = Optional.empty();

    public DiscordConnection()
    {
        this.maxAttempts = Math.max(1, ConfigEntry.DISCORD_RECONNECT_MAX_ATTEMPTS.getInteger(5));
        this.retrySeconds = Math.max(MIN_INTERVAL_SECONDS,
                                     ConfigEntry.DISCORD_RECONNECT_INTERVAL_SECONDS.getInteger(30));
        this.retryInterval = Duration.ofSeconds(retrySeconds);
    }

    /**
     * Open the gateway and keep it open, reconnecting within budget.
     *
     * @param token   bot token
     * @param handler builds the per-connection work
     */
    public void start(final String token, final Function<GatewayDiscordClient, Mono<Void>> handler)
    {
        subscription = Optional.of(DiscordClient.builder(token)
                                                .build()
                                                .gateway()
                                                .setEnabledIntents(IntentSet.of(Intent.GUILD_MESSAGES,
                                                                                Intent.MESSAGE_CONTENT,
                                                                                Intent.DIRECT_MESSAGES))
                                                .withGateway(handler::apply)
                                                .then(Mono.error(DiscordConnection::disconnected))
                                                .retryWhen(budget())
                                                .doOnError(this::giveUp)
                                                .onErrorComplete()
                                                .subscribeOn(Schedulers.boundedElastic())
                                                .subscribe()
                                    );
    }

    /**
     * Disposing the subscription cancels the scope, and {@code withGateway} logs out as part of
     * unwinding it. Cancellation is not an error signal, so this does not reach the retry spec
     * even before {@link #stopping} is consulted.
     */
    public void stop()
    {
        stopping.set(true);

        final Optional<Disposable> current = subscription;
        subscription = Optional.empty();

        current.filter(active -> !active.isDisposed())
               .ifPresent(Disposable::dispose);
    }

    /**
     * A connection reached ready. Clears the budget so an unrelated drop later gets a full set of
     * attempts of its own.
     */
    public void reportConnected()
    {
        final int spent = consecutiveFailures.getAndSet(0);

        if (spent > 0)
            FLog.info(String.format("[Discord] Reconnected after %d failed attempt(s).", spent));
    }

    /**
     * Whether the budget is spent. The bridge stays down for the rest of this run once true.
     */
    public boolean hasGivenUp()
    {
        return givenUp.get();
    }

    public boolean isStopping()
    {
        return stopping.get();
    }

    /**
     * {@code fixedDelay} with an unbounded cap plus a filter, rather than a bounded cap, because
     * the cap has to count <em>consecutive</em> failures. Reactor's counter never resets for a
     * source that emits nothing, so the budget check lives in the filter against a counter the
     * bridge resets on every successful connection. 
     */
    private Retry budget()
    {
        return Retry.fixedDelay(Long.MAX_VALUE, retryInterval)
                    .filter(thrown ->
                    {
                        if (stopping.get() || givenUp.get())
                            return false;

                        final int attempt = consecutiveFailures.incrementAndGet();
                        if (attempt >= maxAttempts)
                            return false;

                        FLog.warning(String.format("[Discord] Connection failure %d/%d (%s); retrying in %ds.",
                                attempt, maxAttempts, describeFailure(thrown), retrySeconds));
                        return true;
                    });
    }

    private void giveUp(final Throwable thrown)
    {
        if (stopping.get() || !givenUp.compareAndSet(false, true))
            return;

        FLog.severe(String.format(
                                    """
                                    [Discord] Giving up after %d consecutive failed connection attempts %ds apart. 
                                    Last failure: %s.
                                    """,
                                     
                                    consecutiveFailures.get(), 
                                    retrySeconds, 
                                    describeFailure(thrown)
                                ));
    }

    private static Throwable disconnected()
    {
        return new IllegalStateException("gateway closed");
    }

    public static String describeFailure(final Throwable thrown)
    {
        return Optional.ofNullable(thrown)
                       .map(failure -> Optional.ofNullable(failure.getMessage())
                                               .filter(message -> !message.isBlank())
                                               .map(message -> String.format("%s: %s", failure.getClass().getSimpleName(), message))
                                               .orElseGet(() -> failure.getClass().getSimpleName()))
                       .orElse("unknown failure");
    }
}

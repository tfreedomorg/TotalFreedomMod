package me.totalfreedom.totalfreedommod.sql;

import java.time.Duration;

import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.util.FLog;

/**
 * Serialises one domain's persistence work onto a single ordered chain. Each queued unit runs
 * after the one before it, so a whole-list batch can never land behind a single-row update that
 * was requested later.
 * <p>
 * Shared by every SQL-primary/JSON-fallback domain: {@code AdminList}, {@code BanManager},
 * {@code PermbanList}, {@code StrikeList}, {@code RankManager}, {@code ProtectArea} and
 * {@code SavedFlags}.
 */
public final class PersistenceQueue
{
    private final String domain;
    private final Object lock = new Object();
    private Mono<Void> chain = Mono.empty();

    /**
     * @param domain name this queue uses when it logs, e.g. {@code "admin"}.
     */
    public PersistenceQueue(final String domain)
    {
        this.domain = domain;
    }

    /**
     * Append {@code work} to the chain and subscribe.
     */
    public void enqueue(final Mono<Void> work)
    {
        synchronized (lock)
        {
            final Mono<Void> queued = chain
                    .onErrorResume(ignored -> Mono.empty())
                    .then(work)
                    .cache();

            chain = queued;
            queued.doFinally(signal -> collapse(queued)).subscribe();
        }
    }

    /**
     * Wait for queued writes to land, up to {@code timeoutMs}. Called before a shutdown flush,
     * which would otherwise race the queue and let an older queued snapshot overwrite the state
     * just written.
     */
    public void await(final long timeoutMs)
    {
        final Mono<Void> pending;
        synchronized (lock)
        {
            pending = chain;
        }

        try
        {
            pending.block(Duration.ofMillis(timeoutMs));
        }
        catch (IllegalStateException ex)
        {
            // Reactor answers a blocking-read timeout with IllegalStateException.
            FLog.warning(String.format("Gave up after %dms waiting for pending %s writes (%s); flushing anyway",
                    timeoutMs, domain, ex.getMessage()));
        }
        catch (RuntimeException ex)
        {
            FLog.warning(String.format("A queued %s write failed before shutdown: %s", domain, ex.getMessage()));
        }
    }

    /**
     * Drop the retained chain once its tail completes. The chain is strictly
     * sequential, so a completed tail means every write before it is done.
     */
    private void collapse(final Mono<Void> completed)
    {
        synchronized (lock)
        {
            if (chain == completed)
                chain = Mono.empty();
        }
    }
}

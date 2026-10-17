package me.totalfreedom.totalfreedommod.sql;

import java.sql.SQLException;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import me.totalfreedom.totalfreedommod.util.FLog;

/**
 * This is a fair, non-blocking access controller that ensures our queries on the connection pool
 * don't overload our available connections.
 *
 * The {@link Semaphore} utilizes a FIFO waiting queue.
 * The query that has been waiting longest always receives the next available permit.
 * Permit release is guaranteed on completion, error, and cancellation.
 *
 * A permit is owned by a <i>thread</i>, not by a call, and acquisition is re-entrant: nested
 * acquires bump a depth counter and only the outermost release hands the permit back. A
 * repository operation takes one permit via {@link #guard(Callable)} and the statements it
 * issues re-enter it, so an operation never waits on a permit it is already holding.
 * <p>
 * {@link #acquireSync()} and its matching {@link #releaseSync()} must run on the same thread.
 */
public final class AccessController
{
    // Matches HikariCP's own default connection timeout.
    private static final long ACQUIRE_TIMEOUT_SECONDS = 30L;

    private final Semaphore semaphore;
    private final Scheduler scheduler;

    /** How many times the current thread has acquired without releasing. */
    private final ThreadLocal<int[]> holdDepth = ThreadLocal.withInitial(() -> new int[1]);

    /**
     * @param permits maximum number of concurrently executing queries.
     *                Should always match the HikariCP maximum pool size.
     * @param scheduler dedicated scheduler to run guarded work on, sized off the same pool.
     */
    public AccessController(final int permits, final Scheduler scheduler)
    {
        this.semaphore = new Semaphore(permits, true);
        this.scheduler = scheduler;
    }

    /**
     * Run a unit of database work on the scheduler holding exactly one permit, however many
     * statements it issues, released on completion, error, and cancellation alike. The permit
     * is taken on the thread that runs {@code work} so the nested acquires inside it re-enter.
     *
     * @return a {@link Mono} that is empty when {@code work} returns {@code null}.
     */
    public <T> Mono<T> guard(final Callable<T> work)
    {
        return Mono.fromCallable(() -> callGuarded(work)).subscribeOn(scheduler);
    }

    private <T> T callGuarded(final Callable<T> work) throws Exception
    {
        acquireSync();
        try
        {
            return work.call();
        }
        finally
        {
            releaseSync();
        }
    }

    /**
     * Acquire a permit for a synchronous unit of work, for the call sites that reach JDBC
     * directly instead of going through {@link #guard(Callable)}.
     * <p>
     * Returns immediately if this thread already holds the permit. Otherwise waits at most
     * {@link #ACQUIRE_TIMEOUT_SECONDS} rather than blocking forever.
     * Always paired with {@link #releaseSync()}, typically in a {@code finally} block.
     */
    public void acquireSync() throws SQLException
    {
        final int[] depth = holdDepth.get();
        if (depth[0] > 0)
        {
            depth[0]++;
            return;
        }

        final boolean acquired;
        try
        {
            acquired = semaphore.tryAcquire(ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for a permit", ex);
        }

        if (!acquired)
            throw new SQLException(String.format(
                    "Timed out after %ds waiting for a permit", ACQUIRE_TIMEOUT_SECONDS));

        depth[0] = 1;
    }

    /**
     * Release one level of this thread's hold. The permit goes back to the semaphore only when
     * the outermost hold is released.
     */
    public void releaseSync()
    {
        final int[] depth = holdDepth.get();
        if (depth[0] == 0)
        {
            // Releasing here would inflate the semaphore past the pool size.
            FLog.severe("AccessController.releaseSync() on a thread holding no permit; "
                    + "acquire and release must be paired on the same thread");
            holdDepth.remove();
            return;
        }

        if (--depth[0] == 0)
        {
            holdDepth.remove();
            semaphore.release();
        }
    }

    public int availablePermits()
    {
        return semaphore.availablePermits();
    }

    /** Number of queries waiting for permit. */
    public int queueLength()
    {
        return semaphore.getQueueLength();
    }
}

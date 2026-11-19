package me.totalfreedom.totalfreedommod.util;

import java.util.function.Consumer;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * Wraps scheduled work so that an escaping {@link Throwable} is reported against a human-readable
 * label instead of being swallowed by Bukkit's scheduler.
 * <p>
 * Labels follow {@code Service/operation}, e.g. {@code EntityWiper/itemDespawn}.
 */
public final class FTask
{

    private FTask() {}

    /**
     * @return {@code body} wrapped so that any throwable it raises is logged against {@code label}
     * rather than escaping into the scheduler.
     */
    public static Runnable guard(final String label, final Runnable body)
    {
        return () -> run(label, body);
    }

    /**
     * {@link #guard(String, Runnable)} for the Paper async scheduler, whose tasks receive the
     * {@link ScheduledTask} handle.
     */
    public static Consumer<ScheduledTask> guardAsync(final String label, final Consumer<ScheduledTask> body)
    {
        return task -> run(label, () -> body.accept(task));
    }

    /**
     * Runs {@code body} immediately, reporting any throwable against {@code label}. Use this inside
     * a {@link org.bukkit.scheduler.BukkitRunnable} body, where the runnable cannot be wrapped.
     */
    public static void run(final String label, final Runnable body)
    {
        try
        {
            body.run();
        }
        catch (Throwable thrown)
        {
            FLog.error("Scheduled task '{}' threw an uncaught {}", label, thrown.getClass().getName(), thrown);
        }
    }
}

package me.totalfreedom.totalfreedommod.util;

import java.util.function.Supplier;

/**
 * Lazily computes a value once and safely publishes it to concurrent callers. {@code null} is a
 * valid cached result.
 * <p>
 * The supplier must not call {@link #get()} on this same instance; that recurses instead of
 * deadlocking, since the lock is reentrant.
 *
 * @param <T> the type being worked out
 */
public class Lazy<T> implements Supplier<T>
{
    private final Supplier<T> delegate;
    private volatile boolean initialized = false;
    private T value;

    public Lazy(Supplier<T> delegate)
    {
        this.delegate = delegate;
    }

    /**
     * The value, working it out on the first call.
     * <p>
     * The volatile on {@code initialized} is load bearing. Setting it after {@code value} is what
     * makes {@code value} visible to other threads, which is what lets the first check run without
     * taking the lock. Dropping volatile off the flag, or moving it onto {@code value} instead,
     * breaks that.
     */
    @Override
    public T get()
    {
        if (!initialized)
            synchronized(this)
            {
                if (!initialized)
                {
                    value = delegate.get();
                    initialized = true;
                }
                
            }

        return value;
    }
}

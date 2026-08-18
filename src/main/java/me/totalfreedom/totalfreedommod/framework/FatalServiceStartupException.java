package me.totalfreedom.totalfreedommod.framework;

/**
 * Stops plugin activation when continuing without a service would violate a core safety or
 * privacy guarantee.
 */
public class FatalServiceStartupException extends RuntimeException
{
    public FatalServiceStartupException(final String message)
    {
        super(message);
    }

    public FatalServiceStartupException(final String message, final Throwable cause)
    {
        super(message, cause);
    }
}

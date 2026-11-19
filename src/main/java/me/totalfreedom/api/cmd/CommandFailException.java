package me.totalfreedom.api.cmd;

/**
 * Thrown from a command handler to abort execution and message the sender.
 * The dispatcher catches this, sends {@link #getMessage()} to the sender,
 * and treats the command as handled.
 */
public class CommandFailException extends RuntimeException
{

    private static final long serialVersionUID = -92333791173124L;

    private final transient String message;

    public CommandFailException(String message)
    {
        super(message);
        this.message = message;
    }

    public String getMessage()
    {
        return message;
    }
}

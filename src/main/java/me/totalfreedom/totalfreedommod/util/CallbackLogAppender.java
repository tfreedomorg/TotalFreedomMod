package me.totalfreedom.totalfreedommod.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

/**
 * Log4j2 appender that hands each formatted log line off to a {@link LogLineConsumer}.
 */
public class CallbackLogAppender extends AbstractAppender
{

    @FunctionalInterface
    public interface LogLineConsumer
    {
        void accept(String line, Level level);
    }

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("HH:mm:ss");

    private final LogLineConsumer consumer;
    private volatile String[] excludedLoggerPrefixes = {};

    public CallbackLogAppender(String name, LogLineConsumer consumer)
    {
        super(name, null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY);
        this.consumer = Objects.requireNonNull(consumer, "consumer");
    }

    /**
     * Drop events from loggers whose name starts with any of {@code prefixes}.
     *
     * @return this appender, so the exclusions can be set where it is constructed
     */
    public CallbackLogAppender excludeLoggers(String... prefixes)
    {
        this.excludedLoggerPrefixes = Optional.ofNullable(prefixes)
                .map(String[]::clone)
                .orElseGet(() -> new String[0]);
        return this;
    }

    @Override
    public void append(LogEvent event)
    {
        if (isExcluded(event.getLoggerName()))
        {
            return;
        }

        try
        {
            String timestamp;
            synchronized (DATE_FORMAT)
            {
                timestamp = DATE_FORMAT.format(new Date(event.getTimeMillis()));
            }
            String level = event.getLevel().name();
            String message = event.getMessage().getFormattedMessage();

            // Strip Minecraft color codes (§ followed by a character).
            message = message.replaceAll("§[0-9a-fk-or]", "");

            String logLine = "[" + timestamp + " " + level + "]: " + message;
            consumer.accept(logLine, event.getLevel());
        }
        catch (Exception ignored)
        {
        }
    }

    private boolean isExcluded(String loggerName)
    {
        return Optional.ofNullable(loggerName)
                .filter(name -> Stream.of(excludedLoggerPrefixes).anyMatch(name::startsWith))
                .isPresent();
    }
}

package me.totalfreedom.totalfreedommod.blocking.item;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

public class ConsoleSpamFilter extends FreedomService
{

    // Substrings matched against each log line; any hit is dropped before it reaches an appender.
    private static final String[] DENY_PATTERNS =
    {
        "Can't create item stack with properties",
        "to contain number, but got",
        "': Not a number"
    };

    private final SpamFilter filter = new SpamFilter();

    private boolean installed = false;

    public ConsoleSpamFilter(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        if (!ConfigEntry.CRASH_ITEMS_HIDE_CONSOLE_SPAM.getBoolean())
        {
            return;
        }

        LoggerConfig root = rootLoggerConfig();
        filter.start();
        root.addFilter(filter);
        ((LoggerContext) LogManager.getContext(false)).updateLoggers();
        installed = true;
    }

    @Override
    protected void onStop()
    {
        if (!installed)
        {
            return;
        }

        rootLoggerConfig().removeFilter(filter);
        if (filter.isStarted())
        {
            filter.stop();
        }
        ((LoggerContext) LogManager.getContext(false)).updateLoggers();
        installed = false;
    }

    private static LoggerConfig rootLoggerConfig()
    {
        Configuration configuration = ((LoggerContext) LogManager.getContext(false)).getConfiguration();
        return configuration.getLoggerConfig(LogManager.ROOT_LOGGER_NAME);
    }

    private static final class SpamFilter extends AbstractFilter
    {

        private SpamFilter()
        {
            super(Result.DENY, Result.NEUTRAL);
        }

        private Result evaluate(String message)
        {
            if (message != null)
            {
                for (String pattern : DENY_PATTERNS)
                {
                    if (message.contains(pattern))
                    {
                        return Result.DENY;
                    }
                }
            }
            return Result.NEUTRAL;
        }

        @Override
        public Result filter(LogEvent event)
        {
            return event == null ? Result.NEUTRAL : evaluate(event.getMessage().getFormattedMessage());
        }

        @Override
        public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t)
        {
            return msg == null ? Result.NEUTRAL : evaluate(msg.getFormattedMessage());
        }

        @Override
        public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t)
        {
            return msg == null ? Result.NEUTRAL : evaluate(String.valueOf(msg));
        }

        @Override
        public Result filter(Logger logger, Level level, Marker marker, String msg, Object... params)
        {
            return evaluate(msg);
        }
    }
}

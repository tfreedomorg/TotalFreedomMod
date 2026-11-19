package me.totalfreedom.totalfreedommod.util;

import me.totalfreedom.api.PluginProvider;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.Bukkit;
import org.slf4j.Logger;

public class FLog
{
    private static Logger plainLogger = null;
    private static ComponentLogger componentLogger = null;

    private FLog()
    {
    }

    public static void debug(String message)
    {
        plainLogger.debug(message);
    }

    public static void debug(String format, Object... args)
    {
        plainLogger.debug(format, args);
    }

    public static void info(String message)
    {
        plainLogger.info(message);
    }

    public static void info(String format, Object... args)
    {
        plainLogger.info(format, args);
    }

    public static void info(Component component)
    {
        componentLogger.info(component);
    }

    public static void warn(String message)
    {
        plainLogger.warn(message);
    }

    public static void warn(String format, Object... args)
    {
        plainLogger.warn(format, args);
    }

    public static void warn(Component component)
    {
        componentLogger.warn(component);
    }

    public static void error(String message)
    {
        plainLogger.error(message);
    }

    public static void error(String format, Object... args)
    {
        plainLogger.error(format, args);
    }

    public static void error(Component component)
    {
        componentLogger.error(component);
    }
    
    public static void fatal(String message)
    {
        plainLogger.error(message);
        Bukkit.getPluginManager().disablePlugin(PluginProvider.get());
    }
    
    public static void fatal(String format, Object... args)
    {
        plainLogger.error(format, args);
        Bukkit.getPluginManager().disablePlugin(PluginProvider.get());
    }

    public static void setLoggers(final Logger plainLogger, final ComponentLogger componentLogger)
    {
        FLog.plainLogger = plainLogger;
        FLog.componentLogger = componentLogger;
    }
}

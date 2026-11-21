package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.command.CommandSender;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.sql.ConnectionHandler.PoolStats;

@Command(name = "sqlstatus", description = "Show database connection pool health.", usage = "/sqlstatus")
@Permission(source = SourceType.BOTH, permission = "tfm.admin.sqlstatus")
public class Command_sqlstatus extends FCommand
{
    @Callback
    public void info(CommandSender sender)
    {
        if (plugin().dm == null || !plugin().dm.isInitialized())
        {
            msg(sender, "<red>Database is not initialized; every domain is running on its JSON fallback.");
            return;
        }

        final PoolStats stats = plugin().dm.getPoolStats();
        if (stats == null)
        {
            msg(sender, "<red>Could not read connection pool stats.");
            return;
        }

        msg(sender,
            """
            <gold>Database: <white><db_type>
            <gold>Pool: <green><db_actives> <white>active connections, <yellow><db_idle> <white>idle connections, <blue><db_total> <white>total connections (<red>max <db_max_pool_size><white>)
            <gold>Threads waiting on a connection: <white><thread_await>
            <gold>Fairness queue: <green><permits_open> <white>permit(s) available, <red><permits_waiting> <white>permit(s) waiting.
            """,
            Placeholder.unparsed("db_type", stats.databaseType()),
            Formatter.number("db_actives", stats.activeConnections()),
            Formatter.number("db_idle", stats.idleConnections()),
            Formatter.number("db_total", stats.totalConnections()),
            Formatter.number("db_max_pool_size", stats.maxPoolSize()),
            Formatter.number("thread_await", stats.threadsAwaitingConnection()),
            Formatter.number("permits_open", stats.availablePermits()),
            Formatter.number("permits_waiting", stats.queueLength())
        );
    }
}

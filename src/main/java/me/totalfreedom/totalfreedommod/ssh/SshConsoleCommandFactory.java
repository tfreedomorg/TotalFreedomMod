package me.totalfreedom.totalfreedommod.ssh;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.bukkit.Bukkit;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.dispatch.RemoteDispatchContext;
import me.totalfreedom.totalfreedommod.dispatch.RemoteDispatchSession;
import me.totalfreedom.totalfreedommod.util.CallbackLogAppender;
import me.totalfreedom.totalfreedommod.util.FLog;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;

public class SshConsoleCommandFactory implements CommandFactory
{
    private final TotalFreedomMod plugin;

    public SshConsoleCommandFactory(TotalFreedomMod plugin)
    {
        this.plugin = plugin;
    }

    @Override
    public Command createCommand(ChannelSession channel, String command)
    {
        return new SshConsoleCommand(plugin, command);
    }

    public static class SshConsoleCommand implements Command
    {
        private final TotalFreedomMod plugin;
        private final String command;

        private OutputStream out;
        private ExitCallback callback;

        public SshConsoleCommand(TotalFreedomMod plugin, String command)
        {
            this.plugin = plugin;
            this.command = command;
        }

        @Override
        public void setInputStream(InputStream in) {}

        @Override
        public void setOutputStream(OutputStream out)
        {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {}

        @Override
        public void setExitCallback(ExitCallback callback)
        {
            this.callback = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) throws IOException
        {
            String sshUsername = env.getEnv().get(Environment.ENV_USER);
            String resolvedName = resolveDisplayName(channel, sshUsername);

            // The session is what carries the user's identity into the permission gate, so it is
            // built unconditionally. ssh.show_user only decides whether that identity is also
            // shown in output. Gating the session on it let a cosmetic setting silently drop
            // authenticated admins back to the flat host_senders tier.
            SshAuthMethod method = channel.getSession().getAttribute(SshDaemon.AUTH_METHOD_KEY);
            String prefix = ConfigEntry.SSH_SHOW_USER.getBoolean() ? ConfigEntry.SSH_USER_PREFIX.getString() : null;
            String displayName = ConfigEntry.SSH_SHOW_USER.getBoolean()
                    ? (prefix == null ? "" : prefix) + resolvedName
                    : "CONSOLE";
            final RemoteDispatchSession session = new RemoteDispatchSession(
                    RemoteDispatchSession.Channel.SSH,
                    resolvedName,
                    displayName,
                    method == SshAuthMethod.PUBLIC_KEY || method == SshAuthMethod.KEY_TOKEN);

            PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true);

            CallbackLogAppender appender = new CallbackLogAppender(
                    "SshCmdAppender-" + resolvedName,
                    (line, level) ->
                    {
                        writer.println(line);
                        writer.flush();
                    });
            appender.start();
            ((Logger) LogManager.getRootLogger()).addAppender(appender);

            Executor mainExecutor = Bukkit.getScheduler().getMainThreadExecutor(plugin);
            CompletableFuture.runAsync(() ->
            {
                FLog.info("[SSH: " + resolvedName + "] " + command);
                String stripped = command.startsWith("/") ? command.substring(1) : command;
                RemoteDispatchContext.dispatch(session, stripped);
            }, mainExecutor).whenComplete((v, ex) ->
            {
                ((Logger) LogManager.getRootLogger()).removeAppender(appender);
                appender.stop();
                writer.flush();
                callback.onExit(ex != null ? 1 : 0);
            });
        }

        private String resolveDisplayName(ChannelSession channel, String fallback)
        {
            String identityId = channel.getSession().getAttribute(SshDaemon.IDENTITY_KEY);
            if (identityId == null || plugin.sd == null)
            {
                return fallback;
            }
            SshIdentityStore store = plugin.sd.getIdentityStore();
            if (store == null)
            {
                return fallback;
            }
            SshIdentity identity = store.get(identityId);
            return identity != null ? identity.identifier() : fallback;
        }

        @Override
        public void destroy(ChannelSession channel) {}
    }
}

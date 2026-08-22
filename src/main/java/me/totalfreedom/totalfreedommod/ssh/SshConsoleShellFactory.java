package me.totalfreedom.totalfreedommod.ssh;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.bukkit.Bukkit;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.dispatch.RemoteDispatchContext;
import me.totalfreedom.totalfreedommod.dispatch.RemoteDispatchSession;
import me.totalfreedom.totalfreedommod.util.CallbackLogAppender;
import me.totalfreedom.totalfreedommod.util.FLog;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.server.shell.ShellFactory;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.ExternalTerminal;

/**
 * Creates interactive console shell sessions for SSH clients.
 * Each session gets its own JLine LineReader with tab completion and log streaming.
 */
public class SshConsoleShellFactory implements ShellFactory
{

    private final TotalFreedomMod plugin;

    public SshConsoleShellFactory(TotalFreedomMod plugin)
    {
        this.plugin = plugin;
    }

    @Override
    public Command createShell(ChannelSession channel)
    {
        return new SshConsoleShell(plugin);
    }

    /**
     * An interactive console shell for a single SSH session.
     */
    public static class SshConsoleShell implements Command, Runnable
    {

        private final TotalFreedomMod plugin;

        private InputStream in;
        private OutputStream out;
        private ExitCallback callback;
        private Environment environment;
        private Thread thread;

        private Terminal terminal;
        private LineReader lineReader;
        private CallbackLogAppender logAppender;
        private RemoteDispatchSession sshSession;
        private Executor mainExecutor;

        public SshConsoleShell(TotalFreedomMod plugin)
        {
            this.plugin = plugin;
        }

        @Override
        public void setInputStream(InputStream in)
        {
            this.in = in;
        }

        @Override
        public void setOutputStream(OutputStream out)
        {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err)
        {
            // ignored, errors will be bounded to the output stream from console.
        }

        @Override
        public void setExitCallback(ExitCallback callback)
        {
            this.callback = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) throws IOException
        {
            this.environment = env;

            try
            {
                String termType = env.getEnv().getOrDefault(Environment.ENV_TERM, "xterm-256color");
                terminal = new ExternalTerminal(
                        "TFM-SSH",
                        termType,
                        in,
                        out,
                        StandardCharsets.UTF_8
                );

                lineReader = LineReaderBuilder.builder()
                        .terminal(terminal)
                        .completer(new SshCommandCompleter(plugin))
                        .build();

                final Terminal sessionTerminal = terminal;
                logAppender = new CallbackLogAppender(
                        "SshLogAppender-" + env.getEnv().get(Environment.ENV_USER),
                        (line, level) ->
                        {
                            sessionTerminal.writer().println(line);
                            sessionTerminal.writer().flush();
                        });
                logAppender.start();
                ((Logger) LogManager.getRootLogger()).addAppender(logAppender);

                String sshUsername = env.getEnv().get(Environment.ENV_USER);
                SshAuthMethod method = channel.getSession().getAttribute(SshDaemon.AUTH_METHOD_KEY);

                // The session is what carries the user's identity into the permission gate, so it
                // is built unconditionally. ssh.show_user only decides whether that identity is
                // also shown in output. Gating the session on it let a cosmetic setting silently
                // drop authenticated admins back to the flat host_senders tier.
                String resolvedName = resolveDisplayName(channel.getSession(), sshUsername);
                String prefix = ConfigEntry.SSH_USER_PREFIX.getString();
                String displayName = ConfigEntry.SSH_SHOW_USER.getBoolean()
                        ? (prefix == null ? "" : prefix) + resolvedName
                        : "CONSOLE";
                sshSession = new RemoteDispatchSession(
                        RemoteDispatchSession.Channel.SSH,
                        resolvedName,
                        displayName,
                        method == SshAuthMethod.PUBLIC_KEY || method == SshAuthMethod.KEY_TOKEN);

                mainExecutor = Bukkit.getScheduler().getMainThreadExecutor(plugin);

                thread = new Thread(this, "SSHD ConsoleShell " + sshUsername);
                thread.setDaemon(true);
                thread.start();
            }
            catch (Exception e)
            {
                throw new IOException("Error starting SSH shell", e);
            }
        }

        private String resolveDisplayName(ServerSession session, String fallback)
        {
            String identityId = session.getAttribute(SshDaemon.IDENTITY_KEY);
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
        public void destroy(ChannelSession channel)
        {
            if (logAppender != null)
            {
                ((Logger) LogManager.getRootLogger()).removeAppender(logAppender);
                logAppender.stop();
            }

            if (terminal != null)
            {
                try
                {
                    terminal.close();
                }
                catch (IOException e)
                {
                    // We should actually probably parse this.
                    FLog.severe(ExceptionUtils.getRootCauseMessage(e));
                }
            }

            // Interrupt reader thread
            if (thread != null && thread.isAlive())
            {
                thread.interrupt();
            }
        }

        @Override
        public void run()
        {
            String username = environment.getEnv().get(Environment.ENV_USER);

            try
            {
                printPreamble();

                while (true)
                {
                    String command;
                    try
                    {
                        command = lineReader.readLine("> ");
                    }
                    catch (UserInterruptException e)
                    {
                        continue;
                    }
                    catch (EndOfFileException e)
                    {
                        break;
                    }

                    if (command == null)
                    {
                        continue;
                    }

                    command = command.trim();
                    if (command.isEmpty())
                    {
                        continue;
                    }

                    if (command.equalsIgnoreCase("exit") || command.equalsIgnoreCase("quit"))
                    {
                        break;
                    }

                    final String cmd = command;
                    final String displayName = sshSession != null ? sshSession.getUsername() : username;
                    CompletableFuture.runAsync(() ->
                    {
                        FLog.info("[SSH: " + displayName + "] " + cmd);
                        String stripped = cmd.startsWith("/") ? cmd.substring(1) : cmd;
                        RemoteDispatchContext.dispatch(sshSession, stripped);
                    }, mainExecutor).exceptionally(ex ->
                    {
                        FLog.warning("[SSH] Command dispatch failed for " + displayName + ": " + ex.getMessage());
                        return null;
                    });
                }
            }
            catch (Exception e)
            {
                FLog.severe("Error processing SSH shell for user: " + username);
                FLog.severe(e);
            }
            finally
            {
                callback.onExit(0);
            }
        }

        private void printPreamble() throws IOException
        {
            terminal.writer().println("TotalFreedomMod version " + plugin.getPluginMeta().getVersion());
            terminal.writer().println("Connected to: " + Bukkit.getServer().getName());
            terminal.writer().println("- " + PlainTextComponentSerializer.plainText().serialize(Bukkit.getServer().motd()));
            terminal.writer().println();
            terminal.writer().println("Type 'exit' to disconnect.");
            terminal.writer().println("===============================================");
            terminal.writer().flush();
        }
    }
}

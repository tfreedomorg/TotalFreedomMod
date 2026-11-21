package me.totalfreedom.totalfreedommod.discord;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import discord4j.common.util.Snowflake;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.emoji.Emoji;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.User;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.admin.Admin;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.dispatch.RemoteDispatchContext;
import me.totalfreedom.totalfreedommod.dispatch.RemoteDispatchSession;
import me.totalfreedom.totalfreedommod.util.CallbackLogAppender;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FTask;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;

/**
 * Streams server log output into the Discord console channel and dispatches
 * plain Minecraft commands typed into that channel as the linked admin.
 */
public class DiscordConsoleRelay
{

    /** Discord hard message-length limit. We pack lines into chunks below this. */
    private static final int DISCORD_MAX_MESSAGE_LENGTH = 1900;

    /** Length of the {@code ```ansi\n} ... {@code \n```} wrapper each chunk is sent inside. */
    private static final int CODE_FENCE_LENGTH = 12;

    /** Longest chunk that still fits inside the fence. */
    private static final int MAX_CHUNK_LENGTH = DISCORD_MAX_MESSAGE_LENGTH - CODE_FENCE_LENGTH;

    private static final int DEFAULT_QUEUE_LIMIT = 2000;
    private static final int DEFAULT_FLUSH_MS = 1500;
    private static final int MIN_FLUSH_MS = 250;

    /**
     * Loggers whose output should never be relayed.
     */
    private static final String[] EXCLUDED_LOGGERS = {"discord4j", "reactor", "io.netty"};

    /**
     * Set while this relay logs its own failures.
     */
    private static final ThreadLocal<Boolean> SUPPRESS_CAPTURE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final TotalFreedomMod plugin;
    private final DiscordBridge bridge;

    private final Deque<String> pendingLines = new ArrayDeque<>();
    private final Object pendingLock = new Object();

    private final int queueLimit;

    /** Lines discarded because the queue was full, reported in the next successful flush. */
    private int droppedLines;

    private volatile Optional<CallbackLogAppender> logAppender = Optional.empty();
    private volatile Optional<BukkitTask> flushTask = Optional.empty();

    public DiscordConsoleRelay(TotalFreedomMod plugin, DiscordBridge bridge)
    {
        this.plugin = plugin;
        this.bridge = bridge;
        this.queueLimit = Math.max(64, ConfigEntry.DISCORD_CONSOLE_QUEUE_LIMIT.getInteger(DEFAULT_QUEUE_LIMIT));
    }

    public Mono<Void> bind(final GatewayDiscordClient gateway)
    {
        return gateway.on(MessageCreateEvent.class)
                      .filter(this::isConsoleCommand)
                      .flatMap(event -> handleCommand(event).onErrorResume(thrown ->
                                                            {
                                                                warnWithoutCapture(String.format("[Discord] Console command failed: %s",
                                                                        DiscordConnection.describeFailure(thrown)));
                                                                return Mono.empty();
                                                            }))
                       .then();
    }

    void attachAppender()
    {
        if (bridge.currentConsoleChannel().isEmpty())
            return;

        final int flushMs = Optional.ofNullable(ConfigEntry.DISCORD_CONSOLE_FLUSH.getInteger())
                                    .filter(configured -> configured >= MIN_FLUSH_MS)
                                    .orElse(DEFAULT_FLUSH_MS);
        final long ticks = Math.max(1L, flushMs / 50L);

        final CallbackLogAppender appender = new CallbackLogAppender("DiscordConsoleAppender", (line, level) -> enqueue(line)).excludeLoggers(EXCLUDED_LOGGERS);

        appender.start();

        ((Logger) LogManager.getRootLogger()).addAppender(appender);
        logAppender = Optional.of(appender);

        flushTask = Optional.of(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
                FTask.guard("DiscordConsoleRelay/flush", this::flush), ticks, ticks));
    }

    void detachAppender()
    {
        logAppender.ifPresent(appender ->
        {
            ((Logger) LogManager.getRootLogger()).removeAppender(appender);
            appender.stop();
        });
        logAppender = Optional.empty();

        flushTask.ifPresent(BukkitTask::cancel);
        flushTask = Optional.empty();

        synchronized (pendingLock)
        {
            pendingLines.clear();
            droppedLines = 0;
        }
    }

    private boolean isConsoleCommand(final MessageCreateEvent event)
    {
        if (event.getGuildId().isEmpty())
            return false;

        final Optional<User> author = event.getMessage().getAuthor();
        if (author.isEmpty() || author.get().isBot())
            return false;

        final Optional<Snowflake> channel = bridge.currentConsoleChannel();
        return channel.isPresent() && channel.get().equals(event.getMessage().getChannelId());
    }

    /**
     * Resolve the author to a linked admin and run their command.
     * <p>
     * The repository lookup is JDBC, so it is pushed onto {@code boundedElastic} rather than run
     * where the event arrived.
     */
    private Mono<Void> handleCommand(final MessageCreateEvent event)
    {
        final Message message = event.getMessage();
        final String content = message.getContent().trim();
        if (content.isEmpty())
            return Mono.empty();

        final String commandLine = content.startsWith("/") ? content.substring(1) : content;
        if (commandLine.isEmpty())
            return Mono.empty();

        return Mono.justOrEmpty(message.getAuthor()
                                       .map(User::getId)
                                       .map(Snowflake::asString))
                   .flatMap(discordUserId -> Mono.fromCallable(() -> resolveAdmin(discordUserId))
                                                 .subscribeOn(Schedulers.boundedElastic()))
                   .flatMap(resolution -> resolution.admin()
                                                    .map(admin -> dispatch(admin, commandLine))
                                                    .orElseGet(() -> react(message, resolution.reaction())));
    }

    private AdminResolution resolveAdmin(final String discordUserId)
    {
        final Optional<UUID> adminUuid;
        try
        {
            adminUuid = Optional.ofNullable(plugin.dm.getDiscordLinkRepository().findAdminUuidByDiscordId(discordUserId));
        }
        catch (SQLException ex)
        {
            warnWithoutCapture("[Discord] discord_links lookup failed: " + ex.getMessage());
            return AdminResolution.lookupFailed();
        }

        return adminUuid.flatMap(uuid -> Optional.ofNullable(plugin.al.getAdminByUuid(uuid)))
                        .filter(Admin::isActive)
                        .map(AdminResolution::linked)
                        .orElseGet(AdminResolution::notLinked);
    }

    private Mono<Void> dispatch(final Admin admin, final String commandLine)
    {
        final String displayName = "Discord@" + admin.getName();
        final RemoteDispatchSession session = new RemoteDispatchSession(RemoteDispatchSession.Channel.DISCORD,
                                                                        admin.getName(),
                                                                        displayName,
                                                                        true);

        return Mono.<Void>fromRunnable(() ->
                   {
                       FLog.info("[Discord: " + admin.getName() + "] /" + commandLine);
                       RemoteDispatchContext.dispatch(session, commandLine);
                   })
                   .subscribeOn(bridge.mainThread());
    }

    /**
     * Queue one captured log line, dropping the oldest when the queue is full.
     */
    private void enqueue(String line)
    {
        if (Boolean.TRUE.equals(SUPPRESS_CAPTURE.get()))
        {
            return;
        }

        synchronized (pendingLock)
        {
            while (pendingLines.size() >= queueLimit)
            {
                pendingLines.pollFirst();
                droppedLines++;
            }
            pendingLines.addLast(line);
        }
    }

    private void flush()
    {
        final Optional<DiscordSession> current = bridge.getSession();
        final Optional<Snowflake> channel = bridge.currentConsoleChannel();
        if (current.isEmpty() || channel.isEmpty())
            return;

        String chunk = drainChunk();
        if (chunk.isEmpty())
            return;

        String body = "```ansi\n" + chunk + "\n```";
        current.get()
               .channel(channel)
               .flatMap(target -> target.createMessage(body))
               .subscribe(
                        sent ->
                        {
                        },
                        err -> warnWithoutCapture("[Discord] Console flush failed: " + err.getMessage()));
    }

    /**
     * Take as many queued lines as fit in one message.
     */
    private String drainChunk()
    {
        StringBuilder chunk = new StringBuilder();
        int dropped;

        synchronized (pendingLock)
        {
            dropped = droppedLines;
            droppedLines = 0;

            if (dropped > 0)
                chunk.append(String.format("... %d line(s) dropped, console output is falling behind ...", dropped));

            while (!pendingLines.isEmpty())
            {
                String line = truncateToFit(pendingLines.peekFirst());

                if (!chunk.isEmpty() && chunk.length() + 1 + line.length() > MAX_CHUNK_LENGTH)
                    break;

                pendingLines.pollFirst();

                if (!chunk.isEmpty())
                    chunk.append('\n');

                chunk.append(line);
            }
        }

        return chunk.toString();
    }

    private static String truncateToFit(String line)
    {
        return line.length() <= MAX_CHUNK_LENGTH
               ? line
               : line.substring(0, MAX_CHUNK_LENGTH - 1) + "…";
    }

    /**
     * Log a relay failure without that log line being captured and queued for delivery to the
     * channel that just failed.
     */
    private static void warnWithoutCapture(String message)
    {
        SUPPRESS_CAPTURE.set(Boolean.TRUE);
        try
        {
            FLog.warning(message);
        }
        finally
        {
            SUPPRESS_CAPTURE.set(Boolean.FALSE);
        }
    }

    private static Mono<Void> react(final Message message, final String emoji)
    {
        return message.addReaction(Emoji.unicode(emoji))
                      .onErrorResume(ignored -> Mono.empty());
    }

    /**
     * Either the admin a console message should run as, or the reaction to leave on it saying why it will not run.
     */
    private record AdminResolution(Optional<Admin> admin, String reaction)
    {
        private static AdminResolution linked(final Admin admin)
        {
            return new AdminResolution(Optional.of(admin), "");
        }

        private static AdminResolution notLinked()
        {
            return new AdminResolution(Optional.empty(), "❌");
        }

        private static AdminResolution lookupFailed()
        {
            return new AdminResolution(Optional.empty(), "⚠️");
        }
    }
}

package me.totalfreedom.totalfreedommod.discord;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;

import discord4j.common.util.Snowflake;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.lifecycle.ReadyEvent;
import discord4j.core.object.entity.channel.GuildMessageChannel;
import discord4j.discordjson.json.ApplicationCommandOptionData;
import discord4j.discordjson.json.ApplicationCommandRequest;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FTask;

/**
 * Owns the Discord4J client, the chat/console relays and the slash-command handlers.
 * <p>
 * The connection itself is owned by {@link DiscordConnection}, which scopes one gateway session to
 * one pipeline and reconnects within a budget. Startup is a subscription rather than a blocking
 * call, so a slow or unreachable Discord never blocks server boot.
 * <p>
 * The live connection is published as one immutable {@link DiscordSession} behind a volatile field.
 * Readers on Reactor threads and the console flush task take a snapshot and either use a whole
 * connection or find none, and reconnecting swaps the lot in a single assignment.
 * <p>
 * Everything downstream of a gateway event runs on a Reactor thread. Anything that touches the
 * server has to hop to the main thread first, and the single convention for that is
 * {@link #mainThread()}, applied with {@code subscribeOn} to a {@code fromRunnable} or
 * {@code fromCallable} holding the server call.
 * 
 * @see DiscordConnection
 */
public class DiscordBridge extends FreedomService
{

    public static volatile boolean reloading = false;

    private static final SecureRandom CODE_RANDOM = new SecureRandom();
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    private static final int DEFAULT_LINK_CODE_TTL_SECONDS = 300;
    private static final Duration SHUTDOWN_MESSAGE_TIMEOUT = Duration.ofSeconds(5);

    private static final List<ApplicationCommandRequest> SLASH_COMMANDS = List.of(
            ApplicationCommandRequest.builder()
                    .name("list")
                    .description("Show a list of players on the server.")
                    .build(),
            ApplicationCommandRequest.builder()
                    .name("link")
                    .description("Link your Minecraft account by entering a code from the in-game /link command.")
                    .addOption(ApplicationCommandOptionData.builder()
                            .name("code")
                            .description("The 8-character code shown in-game.")
                            .type(3)
                            .required(true)
                            .build())
                    .build(),
            ApplicationCommandRequest.builder()
                    .name("unlink")
                    .description("Unlink your Minecraft account from your current Discord account.")
                    .build());

    private final Map<String, PendingLink> pendingLinks = new ConcurrentHashMap<>();
    private final DiscordConnection connection = new DiscordConnection();
    private final Scheduler mainThread;

    /**
     * The live connection, empty while disconnected. Volatile because it is written by the connect
     * pipeline and read from Reactor threads and the console flush task.
     */
    private volatile Optional<DiscordSession> session = Optional.empty();
    private volatile boolean started;
    private volatile Optional<DiscordChatRelay> chatRelay = Optional.empty();
    private volatile Optional<DiscordAdminchatRelay> adminchatRelay = Optional.empty();
    private volatile Optional<DiscordConsoleRelay> consoleRelay = Optional.empty();
    private volatile Optional<BukkitTask> cleanupTask = Optional.empty();
    private volatile int linkCodeTtlSeconds;
    private volatile boolean startedWhileReloading;

    public DiscordBridge(TotalFreedomMod plugin)
    {
        super(plugin);
        this.mainThread = Schedulers.fromExecutor(Bukkit.getScheduler().getMainThreadExecutor(plugin));
    }

    @Override
    protected void onStart()
    {
        startedWhileReloading = reloading;

        if (!Boolean.TRUE.equals(ConfigEntry.DISCORD_ENABLED.getBoolean()))
            return;

        plugin.dm.whenReady(() ->
                DiscordLinkJsonSync.reconcileFromJsonIfNewer(plugin, plugin.dm.getDiscordLinkRepository()));

        final Optional<String> token = configured(ConfigEntry.DISCORD_TOKEN.getString());
        if (token.isEmpty())
        {
            FLog.warning("[Discord] discord.enabled is true but discord.token is empty; bridge will not start.");
            return;
        }
        if (configured(ConfigEntry.DISCORD_GUILD_ID.getString()).isEmpty())
        {
            FLog.warning("[Discord] discord.guild_id is empty; bridge will not start.");
            return;
        }

        linkCodeTtlSeconds = Optional.ofNullable(ConfigEntry.DISCORD_LINK_CODE_TTL.getInteger())
                .filter(ttl -> ttl > 0)
                .orElse(DEFAULT_LINK_CODE_TTL_SECONDS);
        started = true;

        cleanupTask = Optional.of(plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin,
                FTask.guard("DiscordBridge/cleanupPendingLinks", this::cleanupPendingLinks),
                20L * 60L, 20L * 60L));

        connection.start(token.get(), this::onConnected);
    }

    @Override
    protected void onStop()
    {
        started = false;
        cleanupTask.ifPresent(BukkitTask::cancel);
        cleanupTask = Optional.empty();

        consoleRelay.ifPresent(DiscordConsoleRelay::detachAppender);

        if (!reloading)
        {
            chatRelay.ifPresent(relay -> getConfiguredMessage(ConfigEntry.DISCORD_SERVER_SHUTDOWN_MESSAGE)
                    .ifPresent(message -> relay.sendSystemMessageNow(message, SHUTDOWN_MESSAGE_TIMEOUT)));
        }

        connection.stop();

        session = Optional.empty();
        chatRelay = Optional.empty();
        adminchatRelay = Optional.empty();
        consoleRelay = Optional.empty();
        pendingLinks.clear();
    }

    /**
     * Resolves the guild and channels, publishes the session, then returns the relay pipelines
     * joined together. Those never complete on their own, which is what holds the gateway open;
     * when the connection drops they terminate, the returned {@link Mono} completes, and
     * {@link DiscordConnection} turns that into a retry.
     * <p>
     * A guild that cannot be resolved is an error rather than a quiet exit, so a bot that was
     * removed from the guild spends its reconnect budget and reports instead of sitting connected
     * and idle.
     */
    private Mono<Void> onConnected(final GatewayDiscordClient gateway)
    {
        if (!started)
            return Mono.empty();

        final String rawGuildId = ConfigEntry.DISCORD_GUILD_ID.getString();

        return Mono.justOrEmpty(parseSnowflake(rawGuildId, "guild_id"))
                .switchIfEmpty(Mono.error(() ->
                        new IllegalStateException(String.format("guild_id '%s' is not a valid id", rawGuildId))))
                .flatMap(guildId -> gateway.getGuildById(guildId)
                        .switchIfEmpty(Mono.error(() -> new IllegalStateException(
                                String.format("bot is not a member of guild %s", rawGuildId))))
                        .flatMap(guild -> resolveSession(gateway, guildId, guild.getName())))
                .flatMap(resolved -> publishSession(gateway, resolved));
    }

    private Mono<DiscordSession> resolveSession(final GatewayDiscordClient gateway, final Snowflake guildId,
            final String guildName)
    {
        return Mono.zip(
                        resolveChannel(gateway, ConfigEntry.DISCORD_PUBLIC_CHANNEL_ID.getString(), "public_channel_id"),
                        resolveChannel(gateway, ConfigEntry.DISCORD_ADMINCHAT_CHANNEL_ID.getString(), "adminchat_channel_id"),
                        resolveChannel(gateway, ConfigEntry.DISCORD_CONSOLE_CHANNEL_ID.getString(), "console_channel_id"))
                .map(resolved -> new DiscordSession(
                        gateway,
                        guildId,
                        guildName,
                        resolved.getT1().id(),
                        resolved.getT2().id(),
                        resolved.getT3().id(),
                        String.format("public: %s | adminchat: %s | console: %s",
                                resolved.getT1().name(), resolved.getT2().name(), resolved.getT3().name())));
    }

    private Mono<Void> publishSession(final GatewayDiscordClient gateway, final DiscordSession opened)
    {
        if (!started)
            return Mono.empty();

        final DiscordCommands openedCommands = new DiscordCommands(plugin, this);
        final DiscordChatRelay openedChat = new DiscordChatRelay(plugin, this);
        final DiscordAdminchatRelay openedAdminchat = new DiscordAdminchatRelay(plugin, this);
        final DiscordConsoleRelay openedConsole = new DiscordConsoleRelay(plugin, this);

        chatRelay = Optional.of(openedChat);
        adminchatRelay = Optional.of(openedAdminchat);
        consoleRelay = Optional.of(openedConsole);

        session = Optional.of(opened);

        final Mono<Void> ready = gateway.on(ReadyEvent.class)
                .doOnNext(event ->
                {
                    connection.reportConnected();
                    FLog.info(String.format("[Discord] Gateway ready. Bot: %s",
                            event.getSelf().getUsername()));
                })
                .then();

        return Mono.when(
                registerSlashCommands(gateway, opened)
                        .then(Mono.fromRunnable(() -> announceReady(opened))),
                ready,
                openedCommands.bind(gateway),
                openedChat.bind(gateway),
                openedAdminchat.bind(gateway),
                openedConsole.bind(gateway));
    }

    private void announceReady(final DiscordSession opened)
    {
        connection.reportConnected();

        consoleRelay.ifPresent(DiscordConsoleRelay::attachAppender);

        FLog.info(String.format("[Discord] Bridge ready. Guild: %s | %s",
                opened.guildName(), opened.describeChannels()));

        final ConfigEntry greeting = startedWhileReloading
                ? ConfigEntry.DISCORD_PLUGIN_RELOAD_MESSAGE
                : ConfigEntry.DISCORD_SERVER_STARTUP_MESSAGE;
        startedWhileReloading = false;

        if (opened.publicChannel().isPresent())
        {
            chatRelay.ifPresent(relay -> getConfiguredMessage(greeting)
                    .ifPresent(relay::sendSystemMessageToDiscord));
        }
    }

    private Mono<Void> registerSlashCommands(final GatewayDiscordClient gateway, final DiscordSession opened)
    {
        return gateway.getRestClient().getApplicationId()
                .flatMapMany(applicationId -> gateway.getRestClient().getApplicationService()
                        .bulkOverwriteGuildApplicationCommand(applicationId, opened.guildId().asLong(), SLASH_COMMANDS))
                .then(Mono.fromRunnable(() -> FLog.info(
                        String.format("[Discord] Registered slash commands on guild %s.", opened.guildName()))))
                .onErrorResume(thrown ->
                {
                    FLog.warning(String.format("[Discord] Failed to register slash commands: %s",
                            DiscordConnection.describeFailure(thrown)));
                    return Mono.empty();
                })
                .then();
    }

    private Mono<ResolvedChannel> resolveChannel(final GatewayDiscordClient gateway, final String rawId,
            final String configKey)
    {
        if (configured(rawId).isEmpty())
            return Mono.just(ResolvedChannel.none());

        return Mono.justOrEmpty(parseSnowflake(rawId, configKey))
                   .flatMap(id -> gateway.getChannelById(id)
                                         .ofType(GuildMessageChannel.class)
                                         .map(channel -> new ResolvedChannel(Optional.of(id), channel.getName()))
                                         .switchIfEmpty(Mono.fromSupplier(() ->
                                         {
                                             FLog.warning(String.format(
                                                                        "[Discord] %s '%s' is not a text channel in the configured guild.",
                                                                        configKey, rawId));
                                             return ResolvedChannel.none();
                                        })))
                    .defaultIfEmpty(ResolvedChannel.none())
                    .onErrorResume(thrown ->
                    {
                        FLog.warning(String.format(
                                                   "[Discord] Could not resolve %s '%s': %s",
                                                   configKey, rawId, DiscordConnection.describeFailure(thrown)));
                        return Mono.just(ResolvedChannel.none());
                    });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event)
    {
        if (currentPublicChannel().isEmpty())
            return;

        chatRelay.ifPresent(relay ->
        {
            final Player player = event.getPlayer();
            Component rendered;

            try
            {
                rendered = event.renderer().render(player, 
                                                   player.displayName(), // there has to be a way to display both without it being spammy 
                                                   event.message(), 
                                                   Audience.empty());
            }
            catch (Exception ex)
            {
                FLog.warning(String.format(
                                           "[Discord] Chat renderer threw, falling back to plain: %s",
                                           ex.getMessage()));
                rendered = Component.text(player.getName() + ": ").append(event.message());
            }
            relay.sendMessageToDiscord(rendered);
        });
    }

    public void sendBroadcastMessage(String senderName, String message, ConfigEntry configEntry)
    {
        getConfiguredMessage(configEntry).ifPresent(template -> sendToPublicRelay(template.replace("{sender}", senderName).replace("{message}", message)));
    }

    public void sendActionMessage(String senderName, String playerName, String reason, ConfigEntry configEntry)
    {
        getConfiguredMessage(configEntry).ifPresent(template -> sendToPublicRelay(template.replace("{sender}", Optional.ofNullable(senderName).orElse("CONSOLE"))
                                                                                          .replace("{player}", Optional.ofNullable(playerName).orElse("null"))
                                                                                          .replace("{reason}", configured(reason).orElse("No reason provided."))));
    }
    
    public void sendBanMessage(String senderName, String playerName, String reason)
    {
        getConfiguredMessage(ConfigEntry.DISCORD_PLAYER_BAN_MESSAGE).ifPresent(template -> 
                sendToPublicRelay(template.replace("{sender}", Optional.ofNullable(senderName).orElse("CONSOLE"))
                                          .replace("{player}", Optional.ofNullable(playerName).orElse("null"))
                                          .replace("{reason}", configured(reason).orElse("No reason provided"))
                                          .replace("{time}", "24 hours")));
    }
    
    public void sendTBanMessage(String senderName, String playerName, String reason, String time)
    {
        getConfiguredMessage(ConfigEntry.DISCORD_PLAYER_TBAN_MESSAGE).ifPresent(template -> 
                sendToPublicRelay(template.replace("{sender}", Optional.ofNullable(senderName).orElse("CONSOLE"))
                                          .replace("{player}", Optional.ofNullable(playerName).orElse("null"))
                                          .replace("{reason}", configured(reason).orElse("No reason provided"))
                                          .replace("{time}", Optional.ofNullable("{time}").orElse("5 minutes"))));
    }

    public void relayAdminchatMessage(CommandSender sender, Component tag, Component message)
    {
        if (currentAdminchatChannel().isEmpty())
            return;

        adminchatRelay.ifPresent(relay ->
        {
            final Component rendered = sender instanceof Player
                                       ? tag.append(Component.text(" " + sender.getName() + ": ")).append(message)
                                       : Component.text(sender.getName() + " ")
                                                              .append(tag)
                                                              .append(Component.text(": "))
                                                              .append(message);

            relay.sendMessageToDiscord(rendered);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        sendPlayerStatusMessage(event.getPlayer().getName(), ConfigEntry.DISCORD_PLAYER_JOIN_MESSAGE);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        sendPlayerStatusMessage(event.getPlayer().getName(), ConfigEntry.DISCORD_PLAYER_LEAVE_MESSAGE);
    }

    public void relayLoginMessage(Component message)
    {
        Optional.ofNullable(message)
                .map(DiscordMarkdown::render)
                .filter(rendered -> !rendered.isBlank())
                .ifPresent(this::sendToPublicRelay);
    }
    
    public Optional<DiscordSession> getSession()
    {
        return session;
    }

    /**
     * The server main thread as a Reactor scheduler. Every pipeline that ends in a call touching
     * the server publishes onto this first.
     */
    public Scheduler mainThread()
    {
        return mainThread;
    }

    public Optional<Snowflake> currentPublicChannel()
    {
        return getSession().flatMap(DiscordSession::publicChannel);
    }

    public Optional<Snowflake> currentAdminchatChannel()
    {
        return getSession().flatMap(DiscordSession::adminchatChannel);
    }

    public Optional<Snowflake> currentConsoleChannel()
    {
        return getSession().flatMap(DiscordSession::consoleChannel);
    }

    public boolean isReady()
    {
        return session.isPresent();
    }

    public String createPendingLink(UUID adminUuid)
    {
        final long expiryMs = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(linkCodeTtlSeconds);
        String code;
        
        do code = generateCode();
        while (Optional.ofNullable(pendingLinks.putIfAbsent(code, new PendingLink(adminUuid, expiryMs))).isPresent());

        return code;
    }

    /**
     * Consume {@code code}: returns the admin UUID it was registered for and removes the entry.
     * Returns empty if the code is unknown or expired.
     */
    public Optional<UUID> consumePendingLink(String code)
    {
        return Optional.ofNullable(code)
                       .map(String::toUpperCase)
                       .flatMap(upper -> Optional.ofNullable(pendingLinks.remove(upper)))
                       .filter(link -> link.expiresAtMs() >= System.currentTimeMillis())
                       .map(PendingLink::adminUuid);
    }

    public int getLinkCodeTtlSeconds()
    {
        return linkCodeTtlSeconds;
    }

    private void sendPlayerStatusMessage(String playerName, ConfigEntry configEntry)
    {
        getConfiguredMessage(configEntry).ifPresent(template -> sendToPublicRelay(template.replace("{player}", playerName)));
    }

    private void sendToPublicRelay(final String message)
    {
        if (currentPublicChannel().isEmpty())
            return;

        configured(message).ifPresent(text ->
                chatRelay.ifPresent(relay -> relay.sendSystemMessageToDiscord(text)));
    }

    private Optional<String> getConfiguredMessage(ConfigEntry configEntry)
    {
        return configured(configEntry.getString());
    }

    /**
     * A configured string that is actually set. Absent for both a missing key and a blank value,
     * which the config layer does not distinguish.
     */
    private static Optional<String> configured(final String value)
    {
        return Optional.ofNullable(value).filter(text -> !text.isBlank());
    }

    private static Optional<Snowflake> parseSnowflake(final String raw, final String configKey)
    {
        return configured(raw).flatMap(text ->
        {
            try
            {
                return Optional.of(Snowflake.of(text.trim()));
            }
            catch (NumberFormatException ex)
            {
                FLog.warning(String.format("[Discord] %s '%s' is not a valid Discord id.", configKey, raw));
                return Optional.empty();
            }
        });
    }

    private void cleanupPendingLinks()
    {
        final long now = System.currentTimeMillis();
        pendingLinks.entrySet().removeIf(entry -> entry.getValue().expiresAtMs() < now);
    }

    private static String generateCode()
    {
        final StringBuilder builder = new StringBuilder(CODE_LENGTH);

        for (int i = 0; i < CODE_LENGTH; i++)
            builder.append(CODE_ALPHABET.charAt(CODE_RANDOM.nextInt(CODE_ALPHABET.length())));

        return builder.toString();
    }

    private record PendingLink(UUID adminUuid, long expiresAtMs)
    {
    }

    private record ResolvedChannel(Optional<Snowflake> id, String name)
    {
        private static ResolvedChannel none()
        {
            return new ResolvedChannel(Optional.empty(), "(none)");
        }
    }
}

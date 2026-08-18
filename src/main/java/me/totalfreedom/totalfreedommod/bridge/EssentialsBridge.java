package me.totalfreedom.totalfreedommod.bridge;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

public class EssentialsBridge extends FreedomService
{

    private static final String PRIVATE_MESSAGE_EVENT = "net.ess3.api.events.PrivateMessagePreSendEvent";
    private static final String MAIL_EVENT = "net.essentialsx.api.v2.events.UserMailEvent";
    private static final String TELEPORT_REQUEST_EVENT = "net.ess3.api.events.TPARequestEvent";
    private static final String TELEPORT_RESPONSE_EVENT = "net.essentialsx.api.v2.events.TeleportRequestResponseEvent";

    private Plugin essentialsPlugin = null;
    private boolean privateMessageFilterRegistered;
    private boolean mailFilterRegistered;
    private boolean teleportRequestFiltersRegistered;
    private Method privateMessageSender;
    private Method privateMessageRecipient;
    private Method messageRecipientUuid;
    private Method mailRecipient;
    private Method mailMessage;
    private Method mailRecipientUuid;
    private Method mailSenderUuid;
    private Method teleportRequestRequester;
    private Method teleportRequestTarget;
    private Method commandSourcePlayer;
    private Method essentialsUserUuid;
    private Method teleportResponseRequestee;
    private Method teleportResponseRequester;
    private Method teleportResponseRequest;
    private Method teleportRequestName;
    private Method removeTeleportRequest;
    private Method essentialsGetUserByPlayer;
    private Method essentialsMatchUser;
    private Method essentialsCanInteractVanished;
    private Method essentialsResolvedUserUuid;

    public EssentialsBridge(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        activateEssentialsIntegration();
    }

    @Override
    protected void onStop()
    {
        HandlerList.unregisterAll(this);
        clearEssentialsIntegrationState();
    }

    private void clearEssentialsIntegrationState()
    {
        essentialsPlugin = null;
        privateMessageFilterRegistered = false;
        mailFilterRegistered = false;
        teleportRequestFiltersRegistered = false;
        privateMessageSender = null;
        privateMessageRecipient = null;
        messageRecipientUuid = null;
        mailRecipient = null;
        mailMessage = null;
        mailRecipientUuid = null;
        mailSenderUuid = null;
        teleportRequestRequester = null;
        teleportRequestTarget = null;
        commandSourcePlayer = null;
        essentialsUserUuid = null;
        teleportResponseRequestee = null;
        teleportResponseRequester = null;
        teleportResponseRequest = null;
        teleportRequestName = null;
        removeTeleportRequest = null;
        essentialsGetUserByPlayer = null;
        essentialsMatchUser = null;
        essentialsCanInteractVanished = null;
        essentialsResolvedUserUuid = null;
    }

    @EventHandler
    public void activateEssentialsAfterEnable(final PluginEnableEvent event)
    {
        if (!"Essentials".equals(event.getPlugin().getName()))
            return;

        essentialsPlugin = event.getPlugin();
        activateEssentialsIntegration();
    }

    @EventHandler
    public void deactivateEssentialsAfterDisable(final PluginDisableEvent event)
    {
        if (!"Essentials".equals(event.getPlugin().getName()))
            return;

        // Dynamic event classes belong to Essentials' class loader. Remove those listeners and
        // re-register this bridge's ordinary Bukkit handlers so a later Essentials enable can
        // bind cleanly to the replacement class loader.
        HandlerList.unregisterAll(this);
        clearEssentialsIntegrationState();
        server.getPluginManager().registerEvents(this, plugin);
    }

    /**
     * Essentials handles auto-accepted TPA commands before firing its request
     * event. Intercepting the authoritative Essentials command closes that API
     * gap while leaving non-Essentials commands untouched.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void filterAutoAcceptedTeleportRequest(final PlayerCommandPreprocessEvent event)
    {
        final Optional<TeleportRequestCommand> parsed = parseTeleportRequestCommand(event.getMessage());
        if (parsed.isEmpty())
            return;

        final TeleportRequestCommand request = parsed.orElseThrow();
        final Command command = server.getCommandMap().getCommand(request.label());
        if (!isEssentialsTeleportRequestCommand(command))
            return;

        final Player target = resolveEssentialsOnlinePlayer(event.getPlayer(), request.target());
        final boolean unresolvedSeparatedContact = target == null
                && server.getOnlinePlayers()
                        .stream()
                        .anyMatch(player -> plugin.pbe.areSeparated(
                                event.getPlayer().getUniqueId(),
                                player.getUniqueId()));
        if (!unresolvedSeparatedContact
                && (target == null || !shouldCancelSeparatedContact(
                        event.getPlayer().getUniqueId(),
                        target.getUniqueId(),
                        plugin.pbe::areSeparated)))
        {
            return;
        }

        event.setCancelled(true);
        FUtil.playerMsg(event.getPlayer(), "Player not found.", NamedTextColor.RED);
    }

    public Plugin getEssentialsPlugin()
    {
        if (essentialsPlugin == null)
        {
            try
            {
                final Plugin essentials = Bukkit.getServer().getPluginManager().getPlugin("Essentials");
                if (essentials != null && essentials.isEnabled())
                {
                    essentialsPlugin = essentials;
                }
            }
            catch (Exception ex)
            {
                FLog.severe(ex);
            }
        }
        return essentialsPlugin;
    }

    private Object getEssentialsUser(String username)
    {
        try
        {
            final Plugin essentials = getEssentialsPlugin();
            if (essentials != null)
            {
                Object userMap = essentials.getClass().getMethod("getUserMap").invoke(essentials);
                if (userMap != null)
                {
                    return userMap.getClass().getMethod("getUser", String.class).invoke(userMap, username);
                }
            }
        }
        catch (Exception ex)
        {
            FLog.severe(ex);
        }
        return null;
    }

    public long getLastActivity(String username)
    {
        try
        {
            final Object user = getEssentialsUser(username);
            if (user != null)
            {
                java.lang.reflect.Field field = user.getClass().getDeclaredField("lastActivity");
                field.setAccessible(true);
                Object value = field.get(user);
                if (value instanceof Long)
                {
                    return (Long) value;
                }
            }
        }
        catch (Exception ex)
        {
            FLog.severe(ex);
        }
        return 0L;
    }

    public boolean isAfk(String username)
    {
        try
        {
            final Object user = getEssentialsUser(username);
            if (user != null)
            {
                Object result = user.getClass().getMethod("isAfk").invoke(user);
                if (result instanceof Boolean)
                {
                    return (Boolean) result;
                }
            }
        }
        catch (Exception ex)
        {
            FLog.severe(ex);
        }
        return false;
    }

    public boolean isEssentialsEnabled()
    {
        try
        {
            final Plugin essentials = getEssentialsPlugin();
            if (essentials != null)
            {
                return essentials.isEnabled();
            }
        }
        catch (Exception ex)
        {
            FLog.severe(ex);
        }
        return false;
    }

    private void activateEssentialsIntegration()
    {
        final Plugin essentials = getEssentialsPlugin();
        if (essentials == null)
            return;

        initializeEssentialsCommandResolver(essentials);
        registerPrivateMessageFilter(essentials);
        registerMailFilter(essentials);
        registerTeleportRequestFilters(essentials);
    }

    private void initializeEssentialsCommandResolver(final Plugin essentials)
    {
        if (essentialsMatchUser != null)
            return;

        try
        {
            essentialsGetUserByPlayer = essentials.getClass().getMethod("getUser", Player.class);
            final Class<?> userClass = essentialsGetUserByPlayer.getReturnType();
            essentialsCanInteractVanished = userClass.getMethod("canInteractVanished");
            essentialsResolvedUserUuid = userClass.getMethod("getUUID");
            essentialsMatchUser = essentials.getClass().getMethod(
                    "matchUser",
                    Server.class,
                    userClass,
                    String.class,
                    Boolean.class,
                    boolean.class);
        }
        catch (ReflectiveOperationException ex)
        {
            essentialsGetUserByPlayer = null;
            essentialsMatchUser = null;
            essentialsCanInteractVanished = null;
            essentialsResolvedUserUuid = null;
            FLog.warning("Essentials teleport-command target resolution is unavailable: " + ex.getMessage());
        }
    }

    private void registerPrivateMessageFilter(final Plugin essentials)
    {
        if (privateMessageFilterRegistered)
            return;

        try
        {
            final Class<?> rawEventClass = Class.forName(
                    PRIVATE_MESSAGE_EVENT,
                    false,
                    essentials.getClass().getClassLoader());
            final Class<? extends Event> eventClass = rawEventClass.asSubclass(Event.class);

            privateMessageSender = eventClass.getMethod("getSender");
            privateMessageRecipient = eventClass.getMethod("getRecipient");
            messageRecipientUuid = privateMessageSender.getReturnType().getMethod("getUUID");

            server.getPluginManager().registerEvent(
                    eventClass,
                    this,
                    EventPriority.HIGHEST,
                    (listener, event) -> filterPrivateMessage(event),
                    plugin,
                    true);
            privateMessageFilterRegistered = true;
        }
        catch (ReflectiveOperationException | ClassCastException ex)
        {
            FLog.warning("Essentials private-message block filtering is unavailable: " + ex.getMessage());
        }
    }

    private void registerMailFilter(final Plugin essentials)
    {
        if (mailFilterRegistered)
            return;

        try
        {
            final Class<?> rawEventClass = Class.forName(
                    MAIL_EVENT,
                    false,
                    essentials.getClass().getClassLoader());
            final Class<? extends Event> eventClass = rawEventClass.asSubclass(Event.class);

            mailRecipient = eventClass.getMethod("getRecipient");
            mailMessage = eventClass.getMethod("getMessage");
            mailRecipientUuid = mailRecipient.getReturnType().getMethod("getUUID");
            mailSenderUuid = mailMessage.getReturnType().getMethod("getSenderUUID");

            server.getPluginManager().registerEvent(
                    eventClass,
                    this,
                    EventPriority.HIGHEST,
                    (listener, event) -> filterMail(event),
                    plugin,
                    true);
            mailFilterRegistered = true;
        }
        catch (ReflectiveOperationException | ClassCastException ex)
        {
            FLog.warning("Essentials mail block filtering is unavailable: " + ex.getMessage());
        }
    }

    private void registerTeleportRequestFilters(final Plugin essentials)
    {
        if (teleportRequestFiltersRegistered)
            return;

        try
        {
            final ClassLoader classLoader = essentials.getClass().getClassLoader();
            final Class<? extends Event> requestEventClass = Class.forName(
                            TELEPORT_REQUEST_EVENT,
                            false,
                            classLoader)
                    .asSubclass(Event.class);
            final Class<? extends Event> responseEventClass = Class.forName(
                            TELEPORT_RESPONSE_EVENT,
                            false,
                            classLoader)
                    .asSubclass(Event.class);

            teleportRequestRequester = requestEventClass.getMethod("getRequester");
            teleportRequestTarget = requestEventClass.getMethod("getTarget");
            commandSourcePlayer = teleportRequestRequester.getReturnType().getMethod("getPlayer");
            essentialsUserUuid = teleportRequestTarget.getReturnType().getMethod("getUUID");

            teleportResponseRequestee = responseEventClass.getMethod("getRequestee");
            teleportResponseRequester = responseEventClass.getMethod("getRequester");
            teleportResponseRequest = responseEventClass.getMethod("getTpaRequest");
            teleportRequestName = teleportResponseRequest.getReturnType().getMethod("getName");
            removeTeleportRequest = Class.forName(
                            "com.earth2me.essentials.User",
                            false,
                            classLoader)
                    .getMethod("removeTpaRequest", String.class);

            server.getPluginManager().registerEvent(
                    requestEventClass,
                    this,
                    EventPriority.HIGHEST,
                    (listener, event) -> filterTeleportRequest(event),
                    plugin,
                    true);
            server.getPluginManager().registerEvent(
                    responseEventClass,
                    this,
                    EventPriority.HIGHEST,
                    (listener, event) -> filterTeleportResponse(event),
                    plugin,
                    true);
            teleportRequestFiltersRegistered = true;
        }
        catch (ReflectiveOperationException | ClassCastException ex)
        {
            FLog.warning("Essentials teleport-request block filtering is unavailable: " + ex.getMessage());
        }
    }

    private void filterPrivateMessage(final Event event)
    {
        if (!(event instanceof Cancellable cancellable))
            return;

        try
        {
            final Object senderRecipient = privateMessageSender.invoke(event);
            final Object targetRecipient = privateMessageRecipient.invoke(event);
            final UUID senderUuid = getUuid(messageRecipientUuid.invoke(senderRecipient));
            final UUID targetUuid = getUuid(messageRecipientUuid.invoke(targetRecipient));

            if (senderUuid == null || targetUuid == null)
                return;

            if (shouldCancelSeparatedContact(senderUuid, targetUuid, plugin.pbe::areSeparated))
                cancellable.setCancelled(true);
        }
        catch (ReflectiveOperationException ex)
        {
            FLog.warning("Could not filter an Essentials private message: " + ex.getMessage());
        }
    }

    private void filterMail(final Event event)
    {
        if (!(event instanceof Cancellable cancellable))
            return;

        try
        {
            final Object recipient = mailRecipient.invoke(event);
            final Object message = mailMessage.invoke(event);
            final UUID recipientUuid = getUuid(mailRecipientUuid.invoke(recipient));
            final UUID senderUuid = getUuid(mailSenderUuid.invoke(message));

            if (senderUuid != null
                    && recipientUuid != null
                    && shouldCancelSeparatedContact(senderUuid, recipientUuid, plugin.pbe::areSeparated))
            {
                cancellable.setCancelled(true);
            }
        }
        catch (ReflectiveOperationException ex)
        {
            FLog.warning("Could not filter Essentials mail: " + ex.getMessage());
        }
    }

    private void filterTeleportRequest(final Event event)
    {
        if (!(event instanceof Cancellable cancellable))
            return;

        try
        {
            final Object requesterSource = teleportRequestRequester.invoke(event);
            final Object targetUser = teleportRequestTarget.invoke(event);
            final Player requester = (Player) commandSourcePlayer.invoke(requesterSource);
            final UUID targetUuid = getUuid(essentialsUserUuid.invoke(targetUser));

            if (requester == null
                    || targetUuid == null
                    || !shouldCancelSeparatedContact(
                            requester.getUniqueId(),
                            targetUuid,
                            plugin.pbe::areSeparated))
            {
                return;
            }

            cancellable.setCancelled(true);
        }
        catch (ReflectiveOperationException | ClassCastException ex)
        {
            FLog.warning("Could not filter an Essentials teleport request: " + ex.getMessage());
        }
    }

    private void filterTeleportResponse(final Event event)
    {
        if (!(event instanceof Cancellable cancellable))
            return;

        try
        {
            final Object requesteeUser = teleportResponseRequestee.invoke(event);
            final Object requesterUser = teleportResponseRequester.invoke(event);
            final UUID requesteeUuid = getUuid(essentialsUserUuid.invoke(requesteeUser));
            final UUID requesterUuid = getUuid(essentialsUserUuid.invoke(requesterUser));

            if (requesteeUuid == null
                    || requesterUuid == null
                    || !shouldCancelSeparatedContact(
                            requesteeUuid,
                            requesterUuid,
                            plugin.pbe::areSeparated))
            {
                return;
            }

            cancellable.setCancelled(true);
            removeStaleTeleportRequest(event, requesteeUser);

            final Player requestee = server.getPlayer(requesteeUuid);
            if (requestee != null)
            {
                FUtil.playerMsg(
                        requestee,
                        "That teleport request is no longer available.",
                        NamedTextColor.RED);
            }
        }
        catch (ReflectiveOperationException | ClassCastException ex)
        {
            FLog.warning("Could not filter an Essentials teleport response: " + ex.getMessage());
        }
    }

    static Optional<TeleportRequestCommand> parseTeleportRequestCommand(final String message)
    {
        if (message == null)
            return Optional.empty();

        final String trimmed = message.trim();
        if (trimmed.length() < 2 || trimmed.charAt(0) != '/')
            return Optional.empty();

        final String[] parts = trimmed.substring(1).trim().split("\\s+");
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty())
            return Optional.empty();

        return Optional.of(new TeleportRequestCommand(
                parts[0].toLowerCase(Locale.ROOT),
                parts[1]));
    }

    static boolean shouldCancelSeparatedContact(
            final UUID first,
            final UUID second,
            final BiPredicate<UUID, UUID> separationLookup)
    {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        Objects.requireNonNull(separationLookup, "separationLookup");
        return !first.equals(second) && separationLookup.test(first, second);
    }

    private boolean isEssentialsTeleportRequestCommand(final Command command)
    {
        if (!(command instanceof final PluginIdentifiableCommand pluginCommand)
                || !"Essentials".equals(pluginCommand.getPlugin().getName()))
        {
            return false;
        }

        return switch (command.getName().toLowerCase(Locale.ROOT))
        {
            case "tpa", "tpahere" -> true;
            default -> false;
        };
    }

    private Player resolveEssentialsOnlinePlayer(final Player requester, final String value)
    {
        if (essentialsGetUserByPlayer == null
                || essentialsMatchUser == null
                || essentialsCanInteractVanished == null
                || essentialsResolvedUserUuid == null)
        {
            return null;
        }

        try
        {
            final Object requesterUser = essentialsGetUserByPlayer.invoke(essentialsPlugin, requester);
            if (requesterUser == null)
                return null;

            final Boolean canInteractVanished = (Boolean) essentialsCanInteractVanished.invoke(requesterUser);
            final Object targetUser = essentialsMatchUser.invoke(
                    essentialsPlugin,
                    server,
                    requesterUser,
                    value,
                    canInteractVanished,
                    false);
            if (targetUser == null)
                return null;

            final UUID targetUuid = getUuid(essentialsResolvedUserUuid.invoke(targetUser));
            return targetUuid == null ? null : server.getPlayer(targetUuid);
        }
        catch (InvocationTargetException ignored)
        {
            // Essentials uses an exception for a normal no-match or ambiguous-match result.
            return null;
        }
        catch (ReflectiveOperationException | ClassCastException ex)
        {
            FLog.warning("Could not resolve an Essentials teleport target: " + ex.getMessage());
            return null;
        }
    }

    private void removeStaleTeleportRequest(final Event event, final Object requesteeUser)
            throws ReflectiveOperationException
    {
        final Object request = teleportResponseRequest.invoke(event);
        final String requestName = (String) teleportRequestName.invoke(request);
        removeTeleportRequest.invoke(requesteeUser, requestName);
    }

    private static UUID getUuid(final Object value)
    {
        return value instanceof final UUID uuid ? uuid : null;
    }

    record TeleportRequestCommand(String label, String target)
    {
    }
}

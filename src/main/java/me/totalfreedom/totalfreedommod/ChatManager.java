package me.totalfreedom.totalfreedommod;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.ansi.ANSIComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.display.Displayable;
import me.totalfreedom.totalfreedommod.player.FPlayer;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.util.*;
import me.totalfreedom.totalfreedommod.vault.VaultProviderRegistry;

import static me.totalfreedom.totalfreedommod.util.FUtil.playerMsg;

public class ChatManager extends FreedomService
{
    // The maximum message length that the Java Minecraft client can currently handle.
    private static final int MAX_MESSAGE_LENGTH_HARD_LIMIT = 32767;

    private VaultProviderRegistry vaultRegistry = null;
    private boolean essentialsChatInstalled = false;

    private String cachedRawFormat = null;
    private String cachedTranslatedFormat = null;

    public ChatManager(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        // Check for the EssentialsChat plugin
        Plugin essentialsChat = server.getPluginManager().getPlugin("EssentialsChat");
        if (essentialsChat != null && essentialsChat.isEnabled()) {
            essentialsChatInstalled = true;
        }

        // Try to register the Vault chat provider using a delayed task
        server.getScheduler().runTask(plugin, FTask.guard("ChatManager/registerVaultChatProvider", this::registerVaultChatProvider));
    }

    private void registerVaultChatProvider() {
        if (!ConfigEntry.VAULT_CHAT_PROVIDER_ENABLED.getBoolean()) {
            return;
        }

        VaultProviderRegistry registry = VaultProviderRegistry.create(plugin);
        if (registry == null) {
            return;
        }

        boolean shouldOverride = ConfigEntry.VAULT_CHAT_PROVIDER_OVERRIDE_EXISTING.getBoolean();
        if (registry.register(shouldOverride, essentialsChatInstalled)) {
            vaultRegistry = registry;
        }
    }

    /**
     * Handles PluginEnableEvent to re-register Vault Chat Provider when Vault
     * loads.
     * This ensures we register even if Vault loads after TotalFreedomMod.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("Vault")) {
            server.getScheduler().runTaskLater(plugin, FTask.guard("ChatManager/registerVaultChatProvider", this::registerVaultChatProvider), 5L);
        }
    }

    @Override
    protected void onStop() {
        if (vaultRegistry != null) {
            vaultRegistry.unregister();
            vaultRegistry = null;
        }
        cachedRawFormat = null;
        cachedTranslatedFormat = null;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerChatFormat(AsyncChatEvent event) {
        try {
            handleChatEvent(event);
        } catch (Exception ex) {
            FLog.severe(ex);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChatMentionPing(AsyncChatEvent event)
    {
        ChatMentionUtil.pingMentions(plugin, event.message(), plugin.al.isAdminSync(event.getPlayer()));
    }

    private void handleChatEvent(AsyncChatEvent event) {
        final Player player = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        // Handle color and formatting codes based on config
        boolean allowColors = ConfigEntry.VAULT_CHAT_ALLOW_COLOR_FORMATTING.getBoolean();
        boolean allowSpecial = ConfigEntry.VAULT_CHAT_ALLOW_SPECIAL_FORMATTING.getBoolean();

        // Truncate messages that are too long
        Integer maxLengthConfig = ConfigEntry.VAULT_CHAT_MAX_MESSAGE_LENGTH.getInteger();
        int maxLength = 256; // Default fallback
        if (maxLengthConfig != null && maxLengthConfig >= 1) {
            maxLength = maxLengthConfig;
        }
        maxLength = Math.min(maxLength, MAX_MESSAGE_LENGTH_HARD_LIMIT);
        maxLength = Math.max(1, maxLength); // Ensure at least 1

        if (message.length() > maxLength) {
            message = message.substring(0, maxLength);
            if (ConfigEntry.VAULT_CHAT_NOTIFY_TRUNCATED.getBoolean()) {
                FSync.playerMsg(player, "Message was shortened because it was too long to send.");
            }
        }

        // Check for caps (if enabled)
        if (ConfigEntry.VAULT_CHAT_NO_CAPS.getBoolean() && message.length() >= 6) {
            int caps = 0;
            for (char c : message.toCharArray()) {
                if (Character.isUpperCase(c)) {
                    caps++;
                }
            }
            Integer capsRatioConfig = ConfigEntry.VAULT_CHAT_CAPS_RATIO.getInteger();
            double capsRatio = 0.65; // integer fallback
            if (capsRatioConfig != null && capsRatioConfig >= 0 && capsRatioConfig <= 100) {
                capsRatio = capsRatioConfig / 100.0;
            }
            if (((float) caps / (float) message.length()) > capsRatio) {
                message = message.toLowerCase();
            }
        }

        // Check for adminchat
        final FPlayer fPlayer = plugin.pl.getPlayerSync(player);
        if (fPlayer.inAdminChat()) {
            FSync.adminChatMessage(player, message);
            event.setCancelled(true);
            return;
        }
        // Finally, set message
        Component messageComponent = AdventureUtil.formatChat(message, allowColors, allowSpecial);
        messageComponent = ChatMentionUtil.highlight(messageComponent, plugin.al.isAdmin(player));
        event.message(messageComponent);

        // Prefix and suffix come from the shared builder so chat, the tab list, and
        // the Vault Chat export all render identically — with or without Vault.
        String prefix = buildPlayerPrefix(player, PrefixFormat.SECTION);
        String suffix = "";
        String worldName = player.getWorld().getName();

        final String formatTemplate = getTranslatedChatFormat();

        final PlayerData data = player != null ? plugin.pl.getData(player) : null;

        final String resolvedTemplate = formatTemplate
                .replace("{PREFIX}", prefix)
                .replace("{SUFFIX}", suffix)
                .replace("{WORLD}", worldName)
                .replace("{GROUP}", "");
        final String resolvedNonAdminTemplate = resolvedTemplate.replace("{STRIKES}", "");
        final String resolvedAdminTemplate = resolvedTemplate.replace("{STRIKES}", data != null ? "*".repeat(data.getStrikes()) : "");

        event.renderer((source, sourceDisplayName, msg, viewer) -> {
            final Component displayName = data != null && data.hasCustomNickname() ?
                    data.getDisplayedNickname().hoverEvent(HoverEvent.showText(Component.text(source.getName()))) :
                    sourceDisplayName;
            if (viewer instanceof final CommandSender sender && plugin.al.isAdmin(sender)) {
                return buildRenderedMessage(displayName,
                        msg,
                        resolvedAdminTemplate,
                        resolvedAdminTemplate.indexOf("{DISPLAYNAME}"),
                        resolvedAdminTemplate.indexOf("{MESSAGE}"));
            }
            return buildRenderedMessage(displayName,
                    msg,
                    resolvedNonAdminTemplate,
                    resolvedNonAdminTemplate.indexOf("{DISPLAYNAME}"),
                    resolvedNonAdminTemplate.indexOf("{MESSAGE}"));
        });
    }

    private String getTranslatedChatFormat()
    {
        String raw = ConfigEntry.VAULT_CHAT_FORMAT.getString();
        if (raw == null || raw.isEmpty()) {
            raw = "{PREFIX}<{DISPLAYNAME}> {MESSAGE}";
        }
        if (raw == cachedRawFormat || raw.equals(cachedRawFormat)) {
            return cachedTranslatedFormat;
        }
        cachedRawFormat = raw;
        cachedTranslatedFormat = AdventureUtil.translateAlternateColorCodes(raw);
        return cachedTranslatedFormat;
    }

    private Component buildRenderedMessage(Component sourceDisplayName, Component message,
                                           String resolved, int dnIdx, int msgIdx) {

        if (dnIdx < 0 && msgIdx < 0) {
            return legacySection(resolved).append(message);
        }

        if (dnIdx >= 0 && msgIdx >= 0) {
            if (dnIdx < msgIdx) {
                return legacySection(resolved.substring(0, dnIdx))
                        .append(sourceDisplayName)
                        .append(legacySection(resolved.substring(dnIdx + "{DISPLAYNAME}".length(), msgIdx)))
                        .append(message)
                        .append(legacySection(resolved.substring(msgIdx + "{MESSAGE}".length())));
            } else {
                return legacySection(resolved.substring(0, msgIdx))
                        .append(message)
                        .append(legacySection(resolved.substring(msgIdx + "{MESSAGE}".length(), dnIdx)))
                        .append(sourceDisplayName)
                        .append(legacySection(resolved.substring(dnIdx + "{DISPLAYNAME}".length())));
            }
        }

        if (dnIdx >= 0) {
            return legacySection(resolved.substring(0, dnIdx))
                    .append(sourceDisplayName)
                    .append(legacySection(resolved.substring(dnIdx + "{DISPLAYNAME}".length())))
                    .append(message);
        }

        // msgIdx >= 0
        return legacySection(resolved.substring(0, msgIdx))
                .append(message)
                .append(legacySection(resolved.substring(msgIdx + "{MESSAGE}".length())));
    }

    private static Component legacySection(String s) {
        if (s == null || s.isEmpty()) {
            return Component.empty();
        }
        return LegacyComponentSerializer.legacySection().deserialize(s);
    }

    public void adminChat(CommandSender sender, String message) {
        Component tag = sender instanceof Player
                ? plugin.rm.getDisplay(sender).getColoredTag()
                : Component.text("[", NamedTextColor.DARK_GRAY)
                .append(Component.text("CONSOLE", NamedTextColor.DARK_PURPLE))
                .append(Component.text("]", NamedTextColor.DARK_GRAY));
        Component formattedMessage = FUtil.colorizeWithLinks(message, NamedTextColor.GOLD);

        Component nameComponent = Component.text(sender.getName() + " ")
                .append(tag)
                .append(Component.text("").color(NamedTextColor.WHITE));

        Component adminMsg = Component.text("[")
                .color(NamedTextColor.WHITE)
                .append(Component.text("ADMIN").color(NamedTextColor.AQUA))
                .append(Component.text("] ").color(NamedTextColor.WHITE))
                .append(nameComponent.color(NamedTextColor.DARK_RED))
                .append(Component.text(": ").color(NamedTextColor.DARK_RED))
                .append(formattedMessage);

        // Serialize console message to ANSI for terminal colors
        Component consoleMsg = Component.text("[ADMIN] ")
                .color(NamedTextColor.AQUA)
                .append(nameComponent)
                .append(Component.text(": ").color(NamedTextColor.WHITE))
                .append(formattedMessage);
        String ansiMessage = ANSIComponentSerializer.ansi().serialize(consoleMsg);
        Bukkit.getConsoleSender().sendMessage(ansiMessage);

        for (Player player : plugin.al.getOnlineAdmins()) {
            player.sendMessage(adminMsg);
        }

        plugin.db.relayAdminchatMessage(sender, tag, formattedMessage);
    }

    public void reportAction(Player reporter, OfflinePlayer reported, String report) {
        Component reportMsg = Component.text("[REPORTS] ")
                .color(NamedTextColor.RED)
                .append(Component.text(reporter.getName() + " has reported " + reported.getName() + " for ", NamedTextColor.GOLD))
                .append(FUtil.colorizeWithLinks(report, NamedTextColor.GOLD))
                .append(Component.text(" (", NamedTextColor.GOLD))
                .append(Component.text("click to teleport", NamedTextColor.GOLD)
                        .clickEvent(ClickEvent.runCommand(String.format("%s %s",
                                plugin.esb.isEssentialsEnabled() ? "tpo" : "tp",
                                reported.getName()))))
                .append(Component.text(")", NamedTextColor.GOLD));

        for (Player player : plugin.al.getOnlineAdmins()) {
            playerMsg(player, reportMsg);
        }
    }

    /**
     * Gets the player's custom tag (from /tag command).
     */
    private Component getPlayerCustomTag(Player player)
    {
        FPlayer fPlayer = plugin.pl.getPlayer(player);
        if (fPlayer == null)
        {
            return Component.empty();
        }
        Component tag = fPlayer.getTag();
        return tag != null ? tag : Component.empty();
    }

    /**
     * Gets the configured prefix for a display rank/title.
     * Returns null if not configured (will use default).
     */
    private String getConfigPrefix(Displayable display) {
        if (display instanceof me.totalfreedom.totalfreedommod.rank.CustomRank) {
            me.totalfreedom.totalfreedommod.rank.CustomRank custom = (me.totalfreedom.totalfreedommod.rank.CustomRank) display;
            if (custom.getPrefix() != null && !custom.getPrefix().isEmpty()) {
                return custom.getPrefix();
            }
        }
        return null;
    }

    /**
     * Used by TabList so the same logic and config settings apply in chat and in the tab list.
     */
    public enum PrefixFormat
    {
        SECTION,
        AMPERSAND
    }

    public String buildPlayerPrefix(Player player, PrefixFormat format)
    {
        String prefix = buildPrefixSection(player);
        return format == PrefixFormat.AMPERSAND ? prefix.replace('§', '&') : prefix;
    }

    private String buildPrefixSection(Player player)
    {
        if (player == null)
        {
            return "";
        }

        Displayable display = plugin.rm.getDisplay(player);
        String rankPrefix = "";

        if (display != null)
        {
            String configPrefix = getConfigPrefix(display);
            if (configPrefix != null && !configPrefix.isEmpty())
            {
                rankPrefix = AdventureUtil.componentToLegacySection(FUtil.colorize(configPrefix));
            }
            else
            {
                Component coloredTag = display.getColoredTag();
                if (coloredTag != null && !coloredTag.equals(Component.empty()))
                {
                    rankPrefix = AdventureUtil.componentToLegacySection(coloredTag);
                }
            }
        }

        String customTag = AdventureUtil.componentToLegacySection(getPlayerCustomTag(player));
        boolean enforcePrefix = Boolean.TRUE.equals(ConfigEntry.VAULT_CHAT_ENFORCE_PREFIX.getBoolean());

        String formattedTag = null;
        if (customTag != null && !customTag.isEmpty())
        {
            String tagTemplate = ConfigEntry.VAULT_CHAT_TAG.getString();
            if (tagTemplate == null || tagTemplate.isEmpty())
            {
                tagTemplate = "&7{TAG} ";
            }
            formattedTag = AdventureUtil.translateAlternateColorCodes(tagTemplate).replace("{TAG}", customTag);
        }

        if (!enforcePrefix)
        {
            return formattedTag != null ? formattedTag : rankPrefix;
        }
        return formattedTag != null
                ? (!rankPrefix.isEmpty() ? rankPrefix + formattedTag : formattedTag)
                : rankPrefix;
    }

}

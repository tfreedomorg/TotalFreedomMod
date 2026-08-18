package me.totalfreedom.totalfreedommod.discord;

import java.awt.Color;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.util.AdventureUtil;
import me.totalfreedom.totalfreedommod.util.ChatMentionUtil;
import me.totalfreedom.totalfreedommod.util.FLog;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/**
 * Bidirectional chat relay for the chat channel chosen by the extending subclass.
 */
public abstract class AbstractDiscordChatRelay extends ListenerAdapter
{
    protected static final int DISCORD_MAX_MESSAGE_LENGTH = 1900;
    protected static final int MINECRAFT_MAX_MESSAGE_LENGTH = 200;

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)\\b(?:https?://|www\\.)[-a-z0-9+&@#/%?=~_|!:,.;]*[-a-z0-9+&@#/%=~_|]");
    private static final Pattern TEMPLATE_PLACEHOLDER = Pattern.compile("\\{(user|role|rolecolor|message|reply)}");
    private static final Pattern MEDIA_EXTENSION = Pattern.compile(
            "(?i)\\.(?:png|jpe?g|gif|webp|bmp|tiff?|svg|mp4|m4v|mov|webm|avi|mkv|mp3|wav|ogg|oga|m4a|flac)(?:$|[?#])");
    private static final NamedTextColor[] MINECRAFT_COLORS = {
            NamedTextColor.BLACK,
            NamedTextColor.DARK_BLUE,
            NamedTextColor.DARK_GREEN,
            NamedTextColor.DARK_AQUA,
            NamedTextColor.DARK_RED,
            NamedTextColor.DARK_PURPLE,
            NamedTextColor.GOLD,
            NamedTextColor.GRAY,
            NamedTextColor.DARK_GRAY,
            NamedTextColor.BLUE,
            NamedTextColor.GREEN,
            NamedTextColor.AQUA,
            NamedTextColor.RED,
            NamedTextColor.LIGHT_PURPLE,
            NamedTextColor.YELLOW,
            NamedTextColor.WHITE
    };

    /**
     * Resolved per send rather than captured at construction, so a reconnect that produces new
     * channel handles does not leave this relay writing into a dead session's objects.
     */
    private final Supplier<Optional<TextChannel>> channelSupplier;
    private final String channelFormat;
    private final String chatFormat;
    private final Consumer<Component> chatAction;
    private final TotalFreedomMod plugin;
    private final DiscordBridge bridge;

    public AbstractDiscordChatRelay(Supplier<Optional<TextChannel>> channelSupplier, String channelFormat, String chatFormat, Consumer<Component> chatAction, TotalFreedomMod plugin, DiscordBridge bridge)
    {
        this.channelSupplier = channelSupplier;
        this.channelFormat = channelFormat;
        this.chatFormat = chatFormat;
        this.chatAction = chatAction;
        this.plugin = plugin;
        this.bridge = bridge;
    }

    public void sendMessageToDiscord(Component rendered)
    {
        String body = DiscordMarkdown.render(rendered);
        if (body.isBlank())
        {
            return;
        }

        if (channelFormat != null && !channelFormat.isBlank())
        {
            body = channelFormat.replace("{message}", body);
        }

        if (body.length() > DISCORD_MAX_MESSAGE_LENGTH)
        {
            body = body.substring(0, DISCORD_MAX_MESSAGE_LENGTH) + "…";
        }

        sendToRelayChannel(body, "forward chat to Discord");
    }

    public void sendSystemMessageToDiscord(String message)
    {
        if (message == null || message.isBlank())
        {
            return;
        }

        sendToRelayChannel(sanitizeForDiscord(message), "send system message to Discord");
    }

    /**
     * Blocking send used on shutdown, where a queued message would be dropped when the client
     * stops. Targets this relay's own channel: it previously hardcoded the public channel, so an
     * adminchat relay calling it would have posted into public chat.
     */
    public void sendSystemMessageToDiscordNow(String message, long timeout, TimeUnit unit)
    {
        if (message == null || message.isBlank())
        {
            return;
        }

        Optional<TextChannel> target = channelSupplier.get();
        if (target.isEmpty())
        {
            return;
        }

        try
        {
            target.get().sendMessage(sanitizeForDiscord(message))
                    .setAllowedMentions(Collections.emptyList())
                    .submit().get(timeout, unit);
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            FLog.warning("[Discord] Interrupted while sending system message to Discord.");
        }
        catch (Exception ex)
        {
            FLog.warning("[Discord] Failed to send system message to Discord: " + ex.getMessage());
        }
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event)
    {
        if (event.getAuthor().isBot() || event.getAuthor().isSystem())
        {
            return;
        }
        if (!event.isFromGuild())
        {
            return;
        }

        Optional<TextChannel> target = channelSupplier.get();
        if (target.isEmpty() || !event.getChannel().getId().equals(target.get().getId()))
        {
            return;
        }

        Message discordMessage = event.getMessage();
        String content = discordMessage.getContentDisplay();
        List<Message.Attachment> attachments = discordMessage.getAttachments();
        if (content.isBlank() && attachments.isEmpty())
        {
            return;
        }

        String template = chatFormat;
        if (template == null || template.isBlank())
        {
            template = "&9[Discord] &r{user}{reply}&7: &f{message}";
        }

        User author = event.getAuthor();
        String displayName = resolveDisplayName(event.getMember(), author);
        DiscordRole role = resolveRole(event.getMember());
        String truncatedContent = content.length() > MINECRAFT_MAX_MESSAGE_LENGTH
                ? content.substring(0, MINECRAFT_MAX_MESSAGE_LENGTH)
                : content;
        Component discordContent = buildDiscordContent(truncatedContent, attachments);
        String finalTemplate = template;
        Message referencedMessage = discordMessage.getReferencedMessage();

        if (referencedMessage == null)
        {
            relayToMinecraft(finalTemplate, displayName, role, discordContent, "");
            return;
        }

        Member referencedMember = referencedMessage.getMember();
        if (referencedMember == null)
        {
            referencedMember = event.getGuild().getMemberById(referencedMessage.getAuthor().getIdLong());
        }

        if (referencedMember != null)
        {
            relayToMinecraft(
                    finalTemplate,
                    displayName,
                    role,
                    discordContent,
                    resolveDisplayName(referencedMember, referencedMessage.getAuthor()));
            return;
        }

        event.getGuild().retrieveMemberById(referencedMessage.getAuthor().getIdLong()).queue(
                member -> relayToMinecraft(
                        finalTemplate,
                        displayName,
                        role,
                        discordContent,
                        resolveDisplayName(member, referencedMessage.getAuthor())),
                failure -> relayToMinecraft(
                        finalTemplate,
                        displayName,
                        role,
                        discordContent,
                        referencedMessage.getAuthor().getName()));
    }

    private static Component buildDiscordContent(String content, List<Message.Attachment> attachments)
    {
        Component result = Component.empty();
        Matcher matcher = URL_PATTERN.matcher(content);
        int last = 0;

        while (matcher.find())
        {
            if (matcher.start() > last)
            {
                result = result.append(Component.text(content.substring(last, matcher.start())));
            }

            String displayedUrl = matcher.group();
            String href = displayedUrl.regionMatches(true, 0, "http", 0, 4)
                    ? displayedUrl
                    : "https://" + displayedUrl;
            result = result.append(maskedLink(href, isMediaUrl(href)));
            last = matcher.end();
        }

        if (last < content.length())
        {
            result = result.append(Component.text(content.substring(last)));
        }

        for (Message.Attachment attachment : attachments)
        {
            if (!AdventureUtil.componentToPlainText(result).isBlank())
            {
                result = result.append(Component.space());
            }
            result = result.append(maskedLink(attachment.getUrl(), true));
        }

        return result;
    }

    private static Component maskedLink(String url, boolean media)
    {
        return Component.text(media ? "[Media]" : "[Link]", NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text(url)));
    }

    private static boolean isMediaUrl(String url)
    {
        return MEDIA_EXTENSION.matcher(url).find();
    }

    private static String resolveDisplayName(Member member, User user)
    {
        if (member != null && member.getNickname() != null && !member.getNickname().isBlank())
        {
            return member.getNickname();
        }
        return user.getName();
    }

    private void relayToMinecraft(String template, String displayName, DiscordRole role, Component discordContent, String replyName)
    {
        Bukkit.getScheduler().runTask(plugin, () ->
        {
            // Visual highlighting only: Discord authorship is not verified against a Minecraft
            // account, so an unverified Discord member must not be able to trigger an in-game
            // mention sound by typing a player's name.
            Component mentionedContent = ChatMentionUtil.highlight(discordContent, false);
            Component component = buildDiscordMessage(
                    template,
                    displayName,
                    role,
                    mentionedContent,
                    replyName);
            chatAction.accept(component);
        });
    }

    private static DiscordRole resolveRole(Member member)
    {
        if (member == null || member.getRoles().isEmpty())
        {
            return new DiscordRole("everyone", NamedTextColor.GRAY);
        }

        Role selectedRole = null;
        for (Role role : member.getRoles())
        {
            if (role.getColor() != null)
            {
                selectedRole = role;
                break;
            }
        }
        if (selectedRole == null)
        {
            selectedRole = member.getRoles().get(0);
        }

        Color discordColor = selectedRole.getColor();
        NamedTextColor minecraftColor = discordColor == null
                ? NamedTextColor.GRAY
                : closestMinecraftColor(discordColor.getRGB() & 0xFFFFFF);
        return new DiscordRole(selectedRole.getName(), minecraftColor);
    }

    private static NamedTextColor closestMinecraftColor(int rgb)
    {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        NamedTextColor closest = NamedTextColor.WHITE;
        long closestDistance = Long.MAX_VALUE;

        for (NamedTextColor color : MINECRAFT_COLORS)
        {
            int value = color.value();
            int colorRed = (value >> 16) & 0xFF;
            int colorGreen = (value >> 8) & 0xFF;
            int colorBlue = value & 0xFF;
            long redDistance = red - colorRed;
            long greenDistance = green - colorGreen;
            long blueDistance = blue - colorBlue;
            long distance = redDistance * redDistance
                    + greenDistance * greenDistance
                    + blueDistance * blueDistance;

            if (distance < closestDistance)
            {
                closestDistance = distance;
                closest = color;
            }
        }

        return closest;
    }

    private static Component buildDiscordMessage(String template, String user, DiscordRole role, Component message, String replyName)
    {
        Matcher matcher = TEMPLATE_PLACEHOLDER.matcher(template);
        LegacyStyleState styleState = new LegacyStyleState();
        Component result = Component.empty();
        Component reply = replyName == null || replyName.isBlank()
                ? Component.empty()
                : Component.text(" \u21aa Replying to ", NamedTextColor.GRAY)
                        .append(Component.text(replyName, NamedTextColor.AQUA));
        boolean hasReplyPlaceholder = template.contains("{reply}");
        boolean hasMessagePlaceholder = false;
        int last = 0;

        while (matcher.find())
        {
            String literal = template.substring(last, matcher.start());
            result = result.append(styledLiteral(literal, styleState));

            switch (matcher.group(1).toLowerCase(Locale.ROOT))
            {
                case "user":
                    result = result.append(Component.text(user).style(styleState.style()));
                    if (!hasReplyPlaceholder)
                    {
                        result = result.append(reply);
                    }
                    break;
                case "role":
                    result = result.append(Component.text(role.name).style(styleState.style()));
                    break;
                case "rolecolor":
                    styleState.setColor(role.color);
                    break;
                case "message":
                    result = result.append(Component.text().style(styleState.style()).append(message).build());
                    hasMessagePlaceholder = true;
                    break;
                case "reply":
                    result = result.append(reply);
                    break;
                default:
                    break;
            }

            last = matcher.end();
        }

        result = result.append(styledLiteral(template.substring(last), styleState));
        if (!hasMessagePlaceholder)
        {
            result = result.append(Component.text().style(styleState.style()).append(message).build());
        }
        return result;
    }

    private static Component styledLiteral(String literal, LegacyStyleState state)
    {
        if (literal.isEmpty())
        {
            return Component.empty();
        }

        Component component = Component.text().style(state.style()).append(AdventureUtil.format(literal)).build();
        state.consume(literal);
        return component;
    }

    private static String sanitizeForDiscord(String input)
    {
        return input.replace("`", "'");
    }

    private void failRelayChannelSend(final String failureDescription, final Throwable err)
    {
        FLog.warning("[Discord] Failed to " + failureDescription + (err != null ? ": " + err.getMessage() : ""));
    }

    private void sendToRelayChannel(final String body, final String failureDescription)
    {
        final Optional<TextChannel> target = channelSupplier.get();
        if (target.isEmpty() || body == null)
        {
            failRelayChannelSend(failureDescription, null);
            return;
        }

        try
        {
            target.get().sendMessage(body)
                    .setAllowedMentions(Collections.emptyList())
                    .queue(
                            null,
                            err -> failRelayChannelSend(failureDescription, err)
                    );
        }
        catch (RejectedExecutionException ex)
        {
            // The client underneath us is gone, so no gateway event is coming to announce it.
            // Tell the supervisor directly or the bridge would sit here failing every send.
            failRelayChannelSend(failureDescription, ex);
            bridge.reportTransportFailure(failureDescription, ex);
        }
    }

    private static final class DiscordRole
    {
        private final String name;
        private final NamedTextColor color;

        private DiscordRole(String name, NamedTextColor color)
        {
            this.name = name;
            this.color = color;
        }
    }

    private static final class LegacyStyleState
    {
        private TextColor color;
        private boolean obfuscated;
        private boolean bold;
        private boolean strikethrough;
        private boolean underlined;
        private boolean italic;

        private void setColor(TextColor color)
        {
            this.color = color;
            clearDecorations();
        }

        private Style style()
        {
            Style.Builder builder = Style.style();
            if (color != null)
            {
                builder.color(color);
            }
            if (obfuscated)
            {
                builder.decoration(TextDecoration.OBFUSCATED, TextDecoration.State.TRUE);
            }
            if (bold)
            {
                builder.decoration(TextDecoration.BOLD, TextDecoration.State.TRUE);
            }
            if (strikethrough)
            {
                builder.decoration(TextDecoration.STRIKETHROUGH, TextDecoration.State.TRUE);
            }
            if (underlined)
            {
                builder.decoration(TextDecoration.UNDERLINED, TextDecoration.State.TRUE);
            }
            if (italic)
            {
                builder.decoration(TextDecoration.ITALIC, TextDecoration.State.TRUE);
            }
            return builder.build();
        }

        private void consume(String text)
        {
            for (int i = 0; i + 1 < text.length(); i++)
            {
                char marker = text.charAt(i);
                if (marker != '&' && marker != '§')
                {
                    continue;
                }

                char code = Character.toLowerCase(text.charAt(i + 1));
                if (code == '#' && i + 7 < text.length())
                {
                    String hex = text.substring(i + 2, i + 8);
                    if (hex.matches("[0-9a-fA-F]{6}"))
                    {
                        color = TextColor.color(Integer.parseInt(hex, 16));
                        clearDecorations();
                        i += 7;
                    }
                    continue;
                }

                if (code == 'x' && i + 13 < text.length())
                {
                    StringBuilder hex = new StringBuilder(6);
                    boolean valid = true;
                    for (int part = 0; part < 6; part++)
                    {
                        int markerIndex = i + 2 + part * 2;
                        int digitIndex = markerIndex + 1;
                        if (text.charAt(markerIndex) != marker
                                || Character.digit(text.charAt(digitIndex), 16) < 0)
                        {
                            valid = false;
                            break;
                        }
                        hex.append(text.charAt(digitIndex));
                    }
                    if (valid)
                    {
                        color = TextColor.color(Integer.parseInt(hex.toString(), 16));
                        clearDecorations();
                        i += 13;
                    }
                    continue;
                }

                NamedTextColor namedColor = legacyColor(code);
                if (namedColor != null)
                {
                    color = namedColor;
                    clearDecorations();
                    i++;
                    continue;
                }

                switch (code)
                {
                    case 'k':
                        obfuscated = true;
                        break;
                    case 'l':
                        bold = true;
                        break;
                    case 'm':
                        strikethrough = true;
                        break;
                    case 'n':
                        underlined = true;
                        break;
                    case 'o':
                        italic = true;
                        break;
                    case 'r':
                        color = null;
                        clearDecorations();
                        break;
                    default:
                        continue;
                }
                i++;
            }
        }

        private void clearDecorations()
        {
            obfuscated = false;
            bold = false;
            strikethrough = false;
            underlined = false;
            italic = false;
        }

        private static NamedTextColor legacyColor(char code)
        {
            switch (code)
            {
                case '0': return NamedTextColor.BLACK;
                case '1': return NamedTextColor.DARK_BLUE;
                case '2': return NamedTextColor.DARK_GREEN;
                case '3': return NamedTextColor.DARK_AQUA;
                case '4': return NamedTextColor.DARK_RED;
                case '5': return NamedTextColor.DARK_PURPLE;
                case '6': return NamedTextColor.GOLD;
                case '7': return NamedTextColor.GRAY;
                case '8': return NamedTextColor.DARK_GRAY;
                case '9': return NamedTextColor.BLUE;
                case 'a': return NamedTextColor.GREEN;
                case 'b': return NamedTextColor.AQUA;
                case 'c': return NamedTextColor.RED;
                case 'd': return NamedTextColor.LIGHT_PURPLE;
                case 'e': return NamedTextColor.YELLOW;
                case 'f': return NamedTextColor.WHITE;
                default: return null;
            }
        }
    }
}

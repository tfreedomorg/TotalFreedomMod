package me.totalfreedom.totalfreedommod.discord;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import discord4j.common.util.Snowflake;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.entity.Attachment;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.Role;
import discord4j.core.object.entity.User;
import discord4j.core.spec.MessageCreateSpec;
import discord4j.rest.util.AllowedMentions;
import discord4j.rest.util.Color;
import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.util.AdventureUtil;
import me.totalfreedom.totalfreedommod.util.ChatMentionUtil;
import me.totalfreedom.totalfreedommod.util.FLog;

/**
 * Bidirectional chat relay for the chat channel chosen by the extending subclass.
 */
public abstract class AbstractDiscordChatRelay
{
    protected static final int DISCORD_MAX_MESSAGE_LENGTH = 1900;
    protected static final int MINECRAFT_MAX_MESSAGE_LENGTH = 200;

    private static final int NO_ROLE_COLOR = Role.DEFAULT_COLOR.getRGB();
    private static final String DEFAULT_CHAT_FORMAT = "&9[Discord] &r{user}{reply}&7: &f{message}";

    private static final Pattern TEMPLATE_PLACEHOLDER = Pattern.compile("\\{(user|role|rolecolor|message|reply)}");
    private static final Pattern MEDIA_EXTENSION = Pattern.compile(
        "(?i)\\.(?:png|jpe?g|gif|webp|bmp|tiff?|svg|mp4|m4v|mov|webm|avi|mkv|mp3|wav|ogg|oga|m4a|flac)(?:$|[?#])");
    private static final Pattern URL_PATTERN = Pattern.compile(
        "(?i)\\b(?:https?://|www\\.)[-a-z0-9+&@#/%?=~_|!:,.;]*[-a-z0-9+&@#/%=~_|]");

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
     * Resolved per send rather than captured at construction, so a reconnect that produces a new
     * session does not leave this relay writing into a dead connection.
     */
    private final Supplier<Optional<Snowflake>> channelSupplier;
    private final Optional<String> channelFormat;
    private final Optional<String> chatFormat;
    private final Consumer<Component> chatAction;
    private final TotalFreedomMod plugin;
    private final DiscordBridge bridge;

    public AbstractDiscordChatRelay(Supplier<Optional<Snowflake>> channelSupplier, String channelFormat, String chatFormat, Consumer<Component> chatAction, TotalFreedomMod plugin, DiscordBridge bridge)
    {
        this.channelSupplier = channelSupplier;
        this.channelFormat = configured(channelFormat);
        this.chatFormat = configured(chatFormat);
        this.chatAction = chatAction;
        this.plugin = plugin;
        this.bridge = bridge;
    }

    private static Optional<String> configured(final String value)
    {
        return Optional.ofNullable(value).filter(text -> !text.isBlank());
    }

    /**
     * Every message is handled inside its own {@code onErrorResume}. 
     * An error reaching the outer {@link reactor.core.publisher.Flux} terminates it, and a
     * terminated event stream is a relay that has silently stopped while the gateway stays up. 
     * Recovering per message means one malformed message costs one message.
     */
    public Mono<Void> bind(final GatewayDiscordClient gateway)
    {
        return gateway.on(MessageCreateEvent.class)
                .filter(this::isRelayable)
                .flatMap(event -> handleMessage(event)
                        .onErrorResume(thrown ->
                        {
                            FLog.warning(String.format("[Discord] Dropped an inbound message: %s",
                                    DiscordConnection.describeFailure(thrown)));
                            return Mono.empty();
                        }))
                .then();
    }

    public void sendMessageToDiscord(Component rendered)
    {
        configured(DiscordMarkdown.render(rendered))
                .map(body -> channelFormat
                        .map(format -> format.replace("{message}", body))
                        .orElse(body))
                .map(AbstractDiscordChatRelay::truncateForDiscord)
                .ifPresent(body -> sendToRelayChannel(body, "forward chat to Discord"));
    }

    private static String truncateForDiscord(final String body)
    {
        return body.length() > DISCORD_MAX_MESSAGE_LENGTH
                ? body.substring(0, DISCORD_MAX_MESSAGE_LENGTH) + "…"
                : body;
    }

    public void sendSystemMessageToDiscord(String message)
    {
        configured(message).ifPresent(text ->
                sendToRelayChannel(sanitizeForDiscord(text), "send system message to Discord"));
    }

    /**
     * Blocking send used on shutdown, where a queued message would be dropped when the connection
     * goes away.
     * <p>
     * This is the one place the bridge blocks deliberately.
     */
    public void sendSystemMessageNow(String message, Duration timeout)
    {
        configured(message).ifPresent(text ->
        {
            try
            {
                createMessage(sanitizeForDiscord(text)).block(timeout);
            }
            catch (Exception ex)
            {
                FLog.warning("[Discord] Failed to send system message to Discord: " + ex.getMessage());
            }
        });
    }

    private boolean isRelayable(final MessageCreateEvent event)
    {
        if (event.getGuildId().isEmpty())
            return false;

        final Optional<User> author = event.getMessage().getAuthor();
        if (author.isEmpty() || author.get().isBot())
            return false;

        final Optional<Snowflake> target = channelSupplier.get();
        return target.isPresent() && target.get().equals(event.getMessage().getChannelId());
    }

    private Mono<Void> handleMessage(final MessageCreateEvent event)
    {
        final Message discordMessage = event.getMessage();
        final String content = discordMessage.getContent();
        final List<Attachment> attachments = List.copyOf(discordMessage.getAttachments());
        if (content.isBlank() && attachments.isEmpty())
            return Mono.empty();

        final String finalTemplate = chatFormat.orElse(DEFAULT_CHAT_FORMAT);
        final Optional<Member> member = event.getMember();
        final String displayName = resolveDisplayName(member, discordMessage.getAuthor());
        final String truncatedContent = content.length() > MINECRAFT_MAX_MESSAGE_LENGTH
                                        ? content.substring(0, MINECRAFT_MAX_MESSAGE_LENGTH)
                                        : content;
        final Component discordContent = buildDiscordContent(truncatedContent, attachments);

        return Mono.zip(resolveRole(member), resolveReplyName(discordMessage))
                   .flatMap(resolved -> relayToMinecraft(finalTemplate,
                                                         displayName,
                                                         resolved.getT1(),
                                                         discordContent,
                                                         resolved.getT2()));
    }

    /**
     * Function to thread hop to main thread 
     */
    private Mono<Void> relayToMinecraft(String template, String displayName, DiscordRole role, Component discordContent, Optional<String> replyName)
    {
        return Mono.fromRunnable(() ->
                    {
                        Component mentionedContent = ChatMentionUtil.highlightAndPing(plugin, discordContent, false);
                        Component component = buildDiscordMessage(
                                template,
                                displayName,
                                role,
                                mentionedContent,
                                replyName);
                        chatAction.accept(component);
                    })
                   .subscribeOn(bridge.mainThread())
                   .then();
    }

    /**
     * The display name of whoever wrote the message being replied to, absent when this message is
     * not a reply or the referenced author cannot be resolved.
     */
    private static Mono<Optional<String>> resolveReplyName(final Message message)
    {
        return message.getReferencedMessage()
                      .map(referenced -> referenced.getAuthorAsMember()
                                             .map(Member::getDisplayName)
                                             .switchIfEmpty(Mono.justOrEmpty(referenced.getAuthor().map(User::getUsername)))
                                             .map(Optional::of)
                                             .defaultIfEmpty(Optional.<String>empty()))
                      .orElseGet(() -> Mono.just(Optional.empty()));
    }

    private static Mono<DiscordRole> resolveRole(final Optional<Member> member)
    {
        return member.map(present -> present.getRoles()
                                            .collectList()
                                            .map(AbstractDiscordChatRelay::pickRole))
                     .orElseGet(() -> Mono.just(defaultRole()))
                     .onErrorReturn(defaultRole());
    }

    private static DiscordRole defaultRole()
    {
        return new DiscordRole("everyone", NamedTextColor.GRAY);
    }

    private static DiscordRole pickRole(final List<Role> roles)
    {
        if (roles.isEmpty())
            return defaultRole();

        final Role selectedRole = roles.stream()
                                       .filter(role -> colorOf(role).isPresent())
                                       .findFirst()
                                       .orElseGet(() -> roles.get(0));

        final NamedTextColor minecraftColor = colorOf(selectedRole).map(color -> closestMinecraftColor(color.getRGB() & 0xFFFFFF))
                                                                   .orElse(NamedTextColor.GRAY);

        return new DiscordRole(selectedRole.getName(), minecraftColor);
    }

    private static Optional<Color> colorOf(final Role role)
    {
        return Optional.ofNullable(role.getPrimaryColor())
                       .filter(color -> color.getRGB() != NO_ROLE_COLOR);
    }

    private static Component buildDiscordContent(String content, List<Attachment> attachments)
    {
        Component result = Component.empty();
        Matcher matcher = URL_PATTERN.matcher(content);
        int last = 0;

        while (matcher.find())
        {
            if (matcher.start() > last)
                result = result.append(Component.text(content.substring(last, matcher.start())));

            String displayedUrl = matcher.group();
            String href = displayedUrl.regionMatches(true, 0, "http", 0, 4)
                    ? displayedUrl
                    : "https://" + displayedUrl;
            result = result.append(maskedLink(href, isMediaUrl(href)));
            last = matcher.end();
        }

        if (last < content.length())
            result = result.append(Component.text(content.substring(last)));

        for (Attachment attachment : attachments)
        {
            if (!AdventureUtil.componentToPlainText(result).isBlank())
                result = result.append(Component.space());
            
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

    private static String resolveDisplayName(Optional<Member> member, Optional<User> user)
    {
        return member.map(Member::getDisplayName)
                     .or(() -> user.map(User::getUsername))
                     .orElse("unknown");
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

    private static Component buildDiscordMessage(String template, String user, DiscordRole role, Component message, Optional<String> replyName)
    {
        Matcher matcher = TEMPLATE_PLACEHOLDER.matcher(template);
        LegacyStyleState styleState = new LegacyStyleState();
        Component result = Component.empty();
        Component reply = replyName.filter(name -> !name.isBlank())
                                   .map(name -> Component.text(" ↪ Replying to ", NamedTextColor.GRAY)
                                                         .append(Component.text(name, NamedTextColor.AQUA)))
                                   .map(Component.class::cast)
                                   .orElseGet(Component::empty);
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
                        result = result.append(reply);

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
            result = result.append(Component.text().style(styleState.style()).append(message).build());

        return result;
    }

    private static Component styledLiteral(String literal, LegacyStyleState state)
    {
        if (literal.isEmpty())
            return Component.empty();

        Component component = Component.text().style(state.style()).append(AdventureUtil.format(literal)).build();
        state.consume(literal);
        return component;
    }

    private static String sanitizeForDiscord(String input)
    {
        return input.replace("`", "'");
    }

    private void failRelayChannelSend(final String failureDescription, final Optional<Throwable> err)
    {
        FLog.warning(String.format("[Discord] Failed to %s: %s", 
                                   failureDescription,
                                   err.map(thrown -> ": " + thrown.getMessage()).orElse("")));
    }

    /**
     * Build the send for {@code body} against whichever channel this relay currently points at.
     */
    private Mono<Message> createMessage(final String body)
    {
        final Optional<Snowflake> target = channelSupplier.get();

        return Mono.justOrEmpty(configured(body))
                   .flatMap(text -> Mono.justOrEmpty(bridge.getSession())
                                        .flatMap(current -> current.channel(target))
                                        .flatMap(channel -> channel.createMessage(MessageCreateSpec.builder()
                                                                   .content(text)
                                                                   .allowedMentions(AllowedMentions.suppressAll())
                                                                   .build())
                                                ));
    }

    /**
     * Fire-and-forget send with an explicit error consumer.
     */
    private void sendToRelayChannel(final String body, final String failureDescription)
    {
        // The no-op onNext is a placeholder for Reactor's three-argument subscribe, which has no overload taking only an error consumer.
        createMessage(body).subscribe(sent -> {},
                                      err -> failRelayChannelSend(failureDescription, Optional.of(err)));
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
        private Optional<TextColor> color = Optional.empty();

        private boolean obfuscated;
        private boolean bold;
        private boolean strikethrough;
        private boolean underlined;
        private boolean italic;

        private void setColor(TextColor color)
        {
            this.color = Optional.of(color);
            clearDecorations();
        }

        private Style style()
        {
            Style.Builder builder = Style.style();
            color.ifPresent(builder::color);

            if (obfuscated)
                builder.decoration(TextDecoration.OBFUSCATED, TextDecoration.State.TRUE);

            if (bold)
                builder.decoration(TextDecoration.BOLD, TextDecoration.State.TRUE);

            if (strikethrough)
                builder.decoration(TextDecoration.STRIKETHROUGH, TextDecoration.State.TRUE);

            if (underlined)
                builder.decoration(TextDecoration.UNDERLINED, TextDecoration.State.TRUE);

            if (italic)
                builder.decoration(TextDecoration.ITALIC, TextDecoration.State.TRUE);

            return builder.build();
        }

        private void consume(String text)
        {
            for (int i = 0; i + 1 < text.length(); i++)
            {
                char marker = text.charAt(i);
                if (marker != '&' && marker != '§')
                    continue;

                char code = Character.toLowerCase(text.charAt(i + 1));
                if (code == '#' && i + 7 < text.length())
                {
                    String hex = text.substring(i + 2, i + 8);
                    if (hex.matches("[0-9a-fA-F]{6}"))
                    {
                        color = Optional.of(TextColor.color(Integer.parseInt(hex, 16)));
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
                        color = Optional.of(TextColor.color(Integer.parseInt(hex.toString(), 16)));
                        clearDecorations();
                        i += 13;
                    }
                    continue;
                }

                final Optional<TextColor> namedColor = legacyColor(code);
                if (namedColor.isPresent())
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
                        color = Optional.empty();
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

        /**
         * The colour a legacy code selects, absent when the character is not a colour code at all.
         */
        private static Optional<TextColor> legacyColor(char code)
        {
            return switch (code)
            {
                case '0' -> Optional.of(NamedTextColor.BLACK);
                case '1' -> Optional.of(NamedTextColor.DARK_BLUE);
                case '2' -> Optional.of(NamedTextColor.DARK_GREEN);
                case '3' -> Optional.of(NamedTextColor.DARK_AQUA);
                case '4' -> Optional.of(NamedTextColor.DARK_RED);
                case '5' -> Optional.of(NamedTextColor.DARK_PURPLE);
                case '6' -> Optional.of(NamedTextColor.GOLD);
                case '7' -> Optional.of(NamedTextColor.GRAY);
                case '8' -> Optional.of(NamedTextColor.DARK_GRAY);
                case '9' -> Optional.of(NamedTextColor.BLUE);
                case 'a' -> Optional.of(NamedTextColor.GREEN);
                case 'b' -> Optional.of(NamedTextColor.AQUA);
                case 'c' -> Optional.of(NamedTextColor.RED);
                case 'd' -> Optional.of(NamedTextColor.LIGHT_PURPLE);
                case 'e' -> Optional.of(NamedTextColor.YELLOW);
                case 'f' -> Optional.of(NamedTextColor.WHITE);
                default -> Optional.empty();
            };
        }
    }
}

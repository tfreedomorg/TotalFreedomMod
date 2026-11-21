package me.totalfreedom.totalfreedommod.util;

import java.util.regex.Pattern;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public class AdventureUtil
{

    private static LegacyComponentSerializer legacySerializer(char character)
    {
        return LegacyComponentSerializer.builder()
                .character(character)
                .hexColors()
                .useUnusualXRepeatedCharacterHexFormat()
                .build();
    }

    private static final LegacyComponentSerializer LEGACY_AMPERSAND = legacySerializer('&');
    private static final LegacyComponentSerializer LEGACY_SECTION = legacySerializer('\u00A7');
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)\\b(?:https?://|www\\.)[-a-z0-9+&@#/%?=~_|!:,.;]*[-a-z0-9+&@#/%=~_|]");

    private static final TagResolver COLOR_TAGS = TagResolver.resolver(
            StandardTags.color(),
            StandardTags.gradient(),
            StandardTags.rainbow(),
            StandardTags.transition(),
            StandardTags.pride());
    private static final TagResolver DECORATION_TAGS = StandardTags.decorations();

    private static final MiniMessage MM_FULL = MiniMessage.builder()
            .tags(TagResolver.resolver(COLOR_TAGS, DECORATION_TAGS, StandardTags.reset())).build();
    private static final MiniMessage MM_COLORS = MiniMessage.builder()
            .tags(TagResolver.resolver(COLOR_TAGS, StandardTags.reset())).build();
    private static final MiniMessage MM_DECORATIONS = MiniMessage.builder()
            .tags(TagResolver.resolver(DECORATION_TAGS, StandardTags.reset())).build();

    private AdventureUtil()
    {
    }

    /**
     * Converts legacy color codes (&a, §a) to Component.
     *
     * @param legacy The legacy string with color codes
     * @return Component representation
     */
    public static Component legacyToComponent(String legacy)
    {
        if (legacy == null)
        {
            return Component.empty();
        }
        return LEGACY_AMPERSAND.deserialize(legacy);
    }

    public static Component addLinks(Component component)
    {
        if (component == null)
        {
            return Component.empty();
        }
        return component.replaceText(builder -> builder
                .match(URL_PATTERN)
                .replacement((result, text) ->
                {
                    final String url = result.group();
                    final String href = url.regionMatches(true, 0, "http", 0, 4) ? url : "https://" + url;
                    return text.clickEvent(ClickEvent.openUrl(href))
                            .hoverEvent(HoverEvent.showText(Component.text("Open " + href)))
                            .decorate(TextDecoration.UNDERLINED);
                }));
    }

    /**
     * Converts Component back to legacy format for compatibility.
     *
     * @param component The Component to convert
     * @return Legacy string with color codes
     */
    public static String componentToLegacy(Component component)
    {
        if (component == null)
        {
            return "";
        }
        return LEGACY_AMPERSAND.serialize(component);
    }

    /**
     * Converts Component back to legacy format using § codes (for console/chat format).
     * Use this when the output needs § codes instead of & codes.
     *
     * @param component The Component to convert
     * @return Legacy string with § color codes
     */
    public static String componentToLegacySection(Component component)
    {
        if (component == null)
        {
            return "";
        }
        return LEGACY_SECTION.serialize(component);
    }

    /**
     * Creates a colored Component from text and color.
     *
     * @param text The text content
     * @param color The NamedTextColor
     * @return Component with color applied
     */
    public static Component colorize(String text, NamedTextColor color)
    {
        if (text == null)
        {
            return Component.empty();
        }
        Component component = Component.text(text);
        if (color != null)
        {
            component = component.color(color);
        }
        return component;
    }

    public static String componentToPlainText(Component component)
    {
        if (component == null)
        {
            return "";
        }
        return PLAIN_TEXT.serialize(component);
    }

    /**
     * True if any inherited component applies an explicit color/decoration.
     */
    public static boolean hasVisualStyle(Component component)
    {
        if (component == null)
        {
            return false;
        }
        if (component.style().color() != null)
        {
            return true;
        }
        for (TextDecoration decoration : TextDecoration.values())
        {
            if (component.decoration(decoration) == TextDecoration.State.TRUE)
            {
                return true;
            }
        }
        for (Component child : component.children())
        {
            if (hasVisualStyle(child))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * True if {@code text} contains visible characters after stripping legacy colour/format
     * codes and whitespace. Used to reject nicknames that would turn up empty.
     */
    public static boolean hasVisibleText(String text)
    {
        if (text == null)
        {
            return false;
        }
        return hasVisiblePlainText(stripColor(text.replace('§', '&')));
    }

    /**
     * True if {@code component} contains visible plaintext content.
     */
    public static boolean hasVisibleText(Component component)
    {
        if (component == null)
        {
            return false;
        }
        return hasVisiblePlainText(componentToPlainText(component));
    }

    private static boolean hasVisiblePlainText(String plain)
    {
        if (plain == null)
        {
            return false;
        }
        final int len = plain.length();
        for (int i = 0; i < len; i++)
        {
            final char c = plain.charAt(i);
            if (c == '\u00a0' || c == '\u200b' || c == '\u200c' || c == '\u200d' || c == '\ufeff')
            {
                continue;
            }
            if (!Character.isWhitespace(c))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Strips color codes from a string, returning plain text.
     * Replacement for ChatColor.stripColor()
     *
     * @param text The text with color codes
     * @return Plain text without color codes
     */
    public static String stripColor(String text)
    {
        if (text == null)
        {
            return "";
        }
        Component component = legacyToComponent(text);
        return PLAIN_TEXT.serialize(component);
    }

    /**
     * Translates ampersand (&) color codes to legacy section (§) codes.
     * Replacement for ChatColor.translateAlternateColorCodes('&', text).
     *
     * @param text The text containing &-codes
     * @return Text with section (§) color codes
     */
    public static String translateAlternateColorCodes(String text)
    {
        if (text == null)
        {
            return "";
        }
        return LEGACY_SECTION.serialize(LEGACY_AMPERSAND.deserialize(text));
    }

    /**
     * Translates alternate color codes (e.g., &a) to Component.
     * Replacement for ChatColor.translateAlternateColorCodes()
     *
     * @param altColorChar The alternate color character (usually '&')
     * @param text The text to translate
     * @return Component with colors applied
     */
    public static Component translateAlternateColorCodes(char altColorChar, String text)
    {
        if (text == null)
        {
            return Component.empty();
        }
        if (altColorChar == '&')
        {
            return LEGACY_AMPERSAND.deserialize(text);
        }
        else if (altColorChar == '§')
        {
            return LEGACY_SECTION.deserialize(text);
        }
        else
        {
            // For other characters, replace them with & first
            String replaced = text.replace(altColorChar, '&');
            return LEGACY_AMPERSAND.deserialize(replaced);
        }
    }

    private static String colorCodeName(char c)
    {
        switch (c)
        {
            case '0': return "black";
            case '1': return "dark_blue";
            case '2': return "dark_green";
            case '3': return "dark_aqua";
            case '4': return "dark_red";
            case '5': return "dark_purple";
            case '6': return "gold";
            case '7': return "gray";
            case '8': return "dark_gray";
            case '9': return "blue";
            case 'a': return "green";
            case 'b': return "aqua";
            case 'c': return "red";
            case 'd': return "light_purple";
            case 'e': return "yellow";
            case 'f': return "white";
            case 'k': return "obfuscated";
            case 'l': return "bold";
            case 'm': return "strikethrough";
            case 'n': return "underlined";
            case 'o': return "italic";
            case 'r': return "reset";
        }
        throw new IllegalArgumentException("Character supplied is not within range of valid character codes: '" + c + "'");
    }

    /**
     * Shared rich text formatter for display text (chat prefixes, tags, nicknames,
     * ...). Accepts legacy &/§ codes — including {@code &#rrggbb} and {@code &x&r&r&g&g&b&b}
     * (legacy hex) as well as MiniMessage.  Only non-interactive text is accepted.
     * 
     * @param input The raw text
     * @return The formatted Component
     */
    public static Component format(String input)
    {
        if (input == null)
        {
            return Component.empty();
        }
        return MM_FULL.deserialize(legacyToMiniMessage(input, true, true));
    }

    /**
     * Like {@link #format(String)} but for untrusted chat, gated by config: colours
     * (incl. hex/gradient/rainbow) and decorations are each enabled independently.
     *
     * @param input        The raw text
     * @param allowColors  Whether colour/hex/gradient/rainbow tags are permitted
     * @param allowSpecial Whether decoration tags are permitted
     * @return The formatted Component
     */
    public static Component formatChat(String input, boolean allowColors, boolean allowSpecial)
    {
        if (input == null)
        {
            return Component.empty();
        }
        if (!allowColors && !allowSpecial)
        {
            return Component.text(stripColor(input));
        }
        final String mini = legacyToMiniMessage(input, allowColors, allowSpecial);
        if (allowColors && allowSpecial)
        {
            return MM_FULL.deserialize(mini);
        }
        return (allowColors ? MM_COLORS : MM_DECORATIONS).deserialize(mini);
    }

    /**
     * Converts legacy &/§ colour and format codes (including {@code &#rrggbb} and the
     * {@code &x&r&r&g&g&b&b} hex form) into their MiniMessage equivalents, leaving any
     * literal text and existing MiniMessage tags untouched.  Codes whose category is
     * disabled are ignored.
     *
     * @param text         The text containing legacy codes
     * @param allowColors  Whether colour/hex codes are converted (otherwise dropped)
     * @param allowSpecial Whether decoration codes are converted (otherwise dropped)
     * @return Text with legacy codes converted to MiniMessage tags
     */
    public static String legacyToMiniMessage(String text, boolean allowColors, boolean allowSpecial)
    {
        if (text == null)
        {
            return "";
        }
        final StringBuilder out = new StringBuilder(text.length());
        final int len = text.length();
        for (int i = 0; i < len; i++)
        {
            final char c = text.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < len)
            {
                final char next = text.charAt(i + 1);
                // &x&r&r&g&g&b&b hex form
                if (next == 'x' || next == 'X')
                {
                    final String hex = readSpigotHex(text, i);
                    if (hex != null)
                    {
                        if (allowColors)
                        {
                            out.append("<#").append(hex).append('>');
                        }
                        i += 13;
                        continue;
                    }
                }
                // &#rrggbb hex form
                if (next == '#' && i + 8 <= len && isHex(text, i + 2, 6))
                {
                    if (allowColors)
                    {
                        out.append("<#").append(text, i + 2, i + 8).append('>');
                    }
                    i += 7;
                    continue;
                }
                final char code = Character.toLowerCase(next);
                if (isColorChar(code))
                {
                    if (allowColors)
                    {
                        out.append('<').append(colorCodeName(code)).append('>');
                    }
                    i += 1;
                    continue;
                }
                if (isDecorationChar(code))
                {
                    if (allowSpecial)
                    {
                        out.append('<').append(colorCodeName(code)).append('>');
                    }
                    i += 1;
                    continue;
                }
                if (code == 'r')
                {
                    if (allowColors || allowSpecial)
                    {
                        out.append("<reset>");
                    }
                    i += 1;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isColorChar(char c)
    {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }

    private static boolean isDecorationChar(char c)
    {
        return c == 'k' || c == 'l' || c == 'm' || c == 'n' || c == 'o';
    }

    private static boolean isHex(String s, int start, int count)
    {
        if (start + count > s.length())
        {
            return false;
        }
        for (int i = start; i < start + count; i++)
        {
            if (Character.digit(s.charAt(i), 16) < 0)
            {
                return false;
            }
        }
        return true;
    }

    // Reads a &x&r&r&g&g&b&b hex sequence beginning at index i (the & before x);
    // returns 6 hex digits (rrggbb) or NULL if the full pattern is not present.
    private static String readSpigotHex(String text, int i)
    {
        if (i + 13 >= text.length())
        {
            return null;
        }
        final StringBuilder hex = new StringBuilder(6);
        for (int p = 0; p < 6; p++)
        {
            final int base = i + 2 + p * 2;
            final char sym = text.charAt(base);
            final char dig = text.charAt(base + 1);
            if (sym != '&' && sym != '§')
            {
                return null;
            }
            if (Character.digit(dig, 16) < 0)
            {
                return null;
            }
            hex.append(dig);
        }
        return hex.toString();
    }

    /**
     * Applies text decoration to a Component.
     *
     * @param component The Component to decorate
     * @param decoration The TextDecoration to apply
     * @return Component with decoration applied
     */
    public static Component decorate(Component component, TextDecoration decoration)
    {
        if (component == null)
        {
            return Component.empty();
        }
        return component.decorate(decoration);
    }

    /**
     * Formats a string of text with legacy code conversion, MiniMessage tags, and placeholder
     * substitution in one call, doing the same logic as format().
     *
     * @param text         The text string to format
     * @param placeholders Placeholder tag resolvers, e.g. Placeholder.component("name", ...), Placeholder.unparsed("rank", ...)
     * @return The formatted Component
     */
    public static Component formatWithPlaceholders(String text, TagResolver... placeholders)
    {
        if (text == null)
        {
            return Component.empty();
        }
        final String miniMessageContent = legacyToMiniMessage(text, true, true);
        return MM_FULL.deserialize(miniMessageContent, TagResolver.resolver(placeholders));
    }
}


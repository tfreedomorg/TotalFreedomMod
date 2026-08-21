package me.totalfreedom.totalfreedommod.discord;

import java.util.EnumSet;
import java.util.Set;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.format.TextDecoration.State;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

final class DiscordMarkdown
{

    private static final TextDecoration[] ORDER = {
            TextDecoration.STRIKETHROUGH,
            TextDecoration.UNDERLINED,
            TextDecoration.BOLD,
            TextDecoration.ITALIC
    };

    private static final String[] MD = {"~~", "__", "**", "*"};

    private DiscordMarkdown()
    {
    }

    static String render(Component component)
    {
        if (component == null)
        {
            return "";
        }
        StringBuilder out = new StringBuilder();
        EnumSet<TextDecoration> active = EnumSet.noneOf(TextDecoration.class);
        walk(component, EnumSet.noneOf(TextDecoration.class), out, active);
        closeAll(active, out);
        return out.toString();
    }

    private static void walk(Component node, Set<TextDecoration> inherited,
                             StringBuilder out, EnumSet<TextDecoration> active)
    {
        EnumSet<TextDecoration> effective = effectiveDecorations(node, inherited);

        String own = ownText(node);
        if (!own.isEmpty())
        {
            sync(active, effective, out);
            escapeInto(own, out);
        }

        for (Component child : node.children())
        {
            walk(child, effective, out, active);
        }
    }

    private static String ownText(Component node)
    {
        if (node instanceof TextComponent text)
        {
            return text.content();
        }
        // Translatable / keybind / score components: fall back to plain text
        // of the node alone (children are walked separately, so strip them).
        Component bare = node.children(java.util.Collections.emptyList());
        return PlainTextComponentSerializer.plainText().serialize(bare);
    }

    private static EnumSet<TextDecoration> effectiveDecorations(Component node, Set<TextDecoration> inherited)
    {
        EnumSet<TextDecoration> result = EnumSet.noneOf(TextDecoration.class);
        result.addAll(inherited);
        for (TextDecoration d : ORDER)
        {
            State s = node.decoration(d);
            if (s == State.TRUE)
            {
                result.add(d);
            }
            else if (s == State.FALSE)
            {
                result.remove(d);
            }
        }
        return result;
    }

    private static void sync(EnumSet<TextDecoration> active, Set<TextDecoration> want, StringBuilder out)
    {
        if (active.equals(want))
        {
            return;
        }
        closeAll(active, out);
        for (int i = 0; i < ORDER.length; i++)
        {
            if (want.contains(ORDER[i]))
            {
                out.append(MD[i]);
            }
        }
        active.clear();
        active.addAll(want);
    }

    private static void closeAll(EnumSet<TextDecoration> active, StringBuilder out)
    {
        for (int i = ORDER.length - 1; i >= 0; i--)
        {
            if (active.contains(ORDER[i]))
            {
                out.append(MD[i]);
            }
        }
    }

    private static void escapeInto(String s, StringBuilder out)
    {
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            switch (c)
            {
                case '\\':
                case '*':
                case '_':
                case '~':
                case '`':
                case '|':
                    out.append('\\').append(c);
                    break;
                default:
                    out.append(c);
            }
        }
    }
}

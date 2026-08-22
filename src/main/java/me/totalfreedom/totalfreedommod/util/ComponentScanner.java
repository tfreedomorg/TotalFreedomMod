package me.totalfreedom.totalfreedommod.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Bounded-cost inspection of Adventure {@link Component} graphs.
 */
public final class ComponentScanner
{

    public record ComponentMetrics(boolean unsafe, int plainTextLength)
    {
        static ComponentMetrics markUnsafe()
        {
            return new ComponentMetrics(true, -1);
        }
    }

    private static final class GraphProbe
    {
        int nodes;
        boolean oversized;
        boolean cyclic;
        boolean translatableUnsafe;
        boolean clickEvent;
        boolean unsafeHover;
        boolean budgetExceeded;

        boolean unsafe()
        {
            return budgetExceeded || cyclic || oversized || translatableUnsafe || clickEvent || unsafeHover;
        }
    }

    private ComponentScanner()
    {
    }

    public static int safeNodeCount(Component root, int maxNodes)
    {
        return probe(root, maxNodes, 0L).nodes;
    }

    public static boolean isUnsafe(Component root, int maxNodes)
    {
        return probe(root, maxNodes, 0L).unsafe();
    }

    public static boolean isCursed(Component root, int maxNodes)
    {
        return isCursed(root, maxNodes, 0L);
    }

    public static boolean isCursed(Component root, int maxNodes, long deadlineNanos)
    {
        return probe(root, maxNodes, deadlineNanos).unsafe();
    }

    /**
     * Single-pass component inspection: graph safety plus plain-text length when safe.
     */
    public static ComponentMetrics inspectComponent(Component root, int maxNodes, long deadlineNanos)
    {
        GraphProbe graph = probe(root, maxNodes, deadlineNanos);
        if (graph.unsafe())
        {
            return ComponentMetrics.markUnsafe();
        }
        if (budgetExceeded(deadlineNanos))
        {
            return ComponentMetrics.markUnsafe();
        }
        return new ComponentMetrics(false, PlainTextComponentSerializer.plainText().serialize(root).length());
    }

    /**
     * Returns true when the component graph contains a {@code clickEvent}, which
     * lets clients run commands as the interacting player.
     */
    public static boolean hasClickEvent(Component root, int maxNodes)
    {
        return probe(root, maxNodes, 0L).clickEvent;
    }

    public static int safePlainTextLength(Component root, int maxNodes)
    {
        return safePlainTextLength(root, maxNodes, 0L);
    }

    public static int safePlainTextLength(Component root, int maxNodes, long deadlineNanos)
    {
        GraphProbe graph = probe(root, maxNodes, deadlineNanos);
        if (graph.unsafe())
        {
            return -1;
        }
        if (budgetExceeded(deadlineNanos))
        {
            return -1;
        }
        return PlainTextComponentSerializer.plainText().serialize(root).length();
    }

    private static GraphProbe probe(Component root, int maxNodes, long deadlineNanos)
    {
        GraphProbe result = new GraphProbe();
        if (root == null)
        {
            return result;
        }

        IdentityHashMap<Component, Boolean> seen = new IdentityHashMap<>();
        Deque<Component> stack = new ArrayDeque<>();
        stack.push(root);
        int discovered = 1;
        while (!stack.isEmpty())
        {
            if (budgetExceeded(deadlineNanos))
            {
                result.budgetExceeded = true;
                return result;
            }

            Component current = stack.pop();
            if (seen.put(current, Boolean.TRUE) != null)
            {
                result.cyclic = true;
                return result;
            }
            result.nodes++;
            if (result.nodes > maxNodes)
            {
                result.oversized = true;
                return result;
            }
            if (current instanceof TranslatableComponent translatable
                    && (!translatable.arguments().isEmpty() || containsFormatSpecifier(translatable.key())))
            {
                result.translatableUnsafe = true;
                return result;
            }
            if (current.clickEvent() != null)
            {
                result.clickEvent = true;
            }
            HoverEvent<?> hover = current.hoverEvent();
            if (hover != null)
            {
                if (hover.action() == HoverEvent.Action.SHOW_TEXT)
                {
                    if (hover.value() instanceof Component hoverText)
                    {
                        if (++discovered > maxNodes)
                        {
                            result.oversized = true;
                            return result;
                        }
                        stack.push(hoverText);
                    }
                }
                else
                {
                    result.unsafeHover = true;
                    return result;
                }
            }
            for (Component child : current.children())
            {
                if (++discovered > maxNodes)
                {
                    result.oversized = true;
                    return result;
                }
                stack.push(child);
            }
            if (current instanceof TranslatableComponent translatable)
            {
                for (TranslationArgument arg : translatable.arguments())
                {
                    Object value = arg.value();
                    if (value instanceof Component vc)
                    {
                        if (++discovered > maxNodes)
                        {
                            result.oversized = true;
                            return result;
                        }
                        stack.push(vc);
                    }
                }
            }
        }
        return result;
    }

    private static boolean budgetExceeded(long deadlineNanos)
    {
        return deadlineNanos > 0L && System.nanoTime() > deadlineNanos;
    }

    private static boolean containsFormatSpecifier(String key)
    {
        if (key == null || key.indexOf('%') < 0)
        {
            return false;
        }
        return key.replace("%%", "").indexOf('%') >= 0;
    }
}

package me.totalfreedom.totalfreedommod.cmd.internal;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import me.totalfreedom.api.cmd.resolver.AbstractArgumentResolver;
import me.totalfreedom.totalfreedommod.util.FLog;

/**
 * Resolvers are registered by name (as returned by {@link AbstractArgumentResolver#name()})
 * and optionally by the exact parameter type they produce, so handler parameters of that
 * type are resolved automatically with no annotation. A registration may also supply a
 * tab-completion candidate list (e.g. registry keys for {@code Material}) used as the
 * default suggestion source when no explicit {@code @Completer} exists for the position.
 */
public final class ResolverRegistry
{

    private static final Map<String, AbstractArgumentResolver<?>> byName = new ConcurrentHashMap<>();
    private static final Map<Class<?>, AbstractArgumentResolver<?>> byType = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Supplier<List<String>>> suggestionsByType = new ConcurrentHashMap<>();

    private ResolverRegistry() {}

    /**
     * Registers a resolver by name only. Parameters must opt in with
     * {@code @Resolve("<name>")}.
     */
    public static void register(AbstractArgumentResolver<?> resolver)
    {
        byName.put(resolver.name(), resolver);
    }

    /**
     * Registers a resolver by name and binds it to an exact parameter type, so any
     * handler parameter of {@code type} resolves through it automatically.
     */
    public static void register(AbstractArgumentResolver<?> resolver, Class<?> type)
    {
        register(resolver, type, null);
    }

    /**
     * Registers a resolver bound to a type, with a default tab-completion candidate
     * supplier. The supplier is invoked lazily per suggestion request.
     */
    public static void register(AbstractArgumentResolver<?> resolver, Class<?> type, Supplier<List<String>> suggestions)
    {
        byName.put(resolver.name(), resolver);
        byType.put(type, resolver);
        if (suggestions != null)
        {
            suggestionsByType.put(type, suggestions);
        }
    }

    public static AbstractArgumentResolver<?> byName(String name)
    {
        AbstractArgumentResolver<?> resolver = byName.get(name);
        if (resolver == null)
        {
            FLog.warn("No argument resolver registered with the name '{}'", name);
        }
        return resolver;
    }

    public static AbstractArgumentResolver<?> byType(Class<?> type)
    {
        return byType.get(type);
    }

    public static boolean hasType(Class<?> type)
    {
        return byType.containsKey(type);
    }

    public static Supplier<List<String>> suggestionsFor(Class<?> type)
    {
        return suggestionsByType.get(type);
    }

    public static void clear()
    {
        byName.clear();
        byType.clear();
        suggestionsByType.clear();
    }
}

package me.totalfreedom.totalfreedommod.cmd.resolver;

import java.util.List;
import org.bukkit.command.CommandSender;

public interface AbstractArgumentResolver<T>
{
    String name();

    T resolve(String arg, String strategy);

    /**
     * Sender-aware resolution hook. Resolvers handling player identities should override this
     * method so hidden online players cannot be discovered by command dispatch.
     */
    default T resolve(final CommandSender sender, final String arg, final String strategy)
    {
        return resolve(arg, strategy);
    }

    /**
     * This is the fallback used when the parameter has no {@code @Completer} and the
     * {@link me.totalfreedom.totalfreedommod.cmd.internal.ResolverRegistry ResolverRegistry}
     * registration supplied no type-bound candidate list. Implement it on resolvers reached by
     * {@code @Resolve("<name>")}, since those have no parameter type to key a registration off.
     *
     * @return candidates, or an empty list when the argument has no enumerable value set
     */
    default List<String> suggestions()
    {
        return List.of();
    }

    /**
     * Sender-aware suggestion hook. The default preserves existing sender-independent resolvers.
     */
    default List<String> suggestions(final CommandSender sender)
    {
        return suggestions();
    }
}

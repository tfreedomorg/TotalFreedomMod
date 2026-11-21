package me.totalfreedom.totalfreedommod.cmd;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Subcommand;

public final class CommandRegistry
{
    private static final ConcurrentHashMap<String, FCommand> registry = new ConcurrentHashMap<>();

    private CommandRegistry() {}

    public static void store(FCommand command)
    {
        Command meta = command.getClass().getAnnotation(Command.class);
        if (meta == null) throw new IllegalArgumentException(
            command.getClass().getName() + " is missing @Command");
            
        registry.put(meta.name().toLowerCase(Locale.ROOT), command);
        
        Stream.of(meta.aliases())
              .map(alias -> alias.toLowerCase(Locale.ROOT))
              .forEach(alias -> registry.putIfAbsent(alias, command));
    }

    public static FCommand getByName(String commandName)
    {
        return registry.get(commandName.toLowerCase(Locale.ROOT));
    }

    public static List<String> listAliases(String commandName)
    {
        FCommand cmd = registry.get(commandName.toLowerCase());
        if (cmd == null) return List.of();
        return List.of(cmd.getClass().getAnnotation(Command.class).aliases());
    }

    public static List<String> listSubcommands(String commandName)
    {
        FCommand cmd = registry.get(commandName.toLowerCase());
        if (cmd == null) return List.of();
        return Stream.of(cmd.getClass().getDeclaredMethods())
                     .filter(m -> m.isAnnotationPresent(Subcommand.class))
                     .map(m -> m.getAnnotation(Subcommand.class).value())
                     .collect(Collectors.toUnmodifiableList());
    }

    public static Collection<FCommand> all()
    {
        return Collections.unmodifiableCollection(registry.values());
    }

    public static void clear()
    {
        registry.clear();
    }
}

package me.totalfreedom.totalfreedommod.cmd.internal;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.cmd.CommandFailException;
import me.totalfreedom.totalfreedommod.cmd.FCommand;
import me.totalfreedom.totalfreedommod.cmd.MessageUtils;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Callback;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Completer;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Cooldown;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Greedy;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Resolve;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Subcommand;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Switch;
import me.totalfreedom.totalfreedommod.cmd.resolver.AbstractArgumentResolver;
import me.totalfreedom.totalfreedommod.cmd.resolver.ArgumentResolutionException;
import me.totalfreedom.totalfreedommod.util.FLog;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Builds Brigadier command node trees from {@link FCommand} declarations and wires them
 * into the server command dispatcher via Paper's {@code LifecycleEvents.COMMANDS}.
 * 
 * <h3>Registration flow</h3>
 * <ol>
 *   <li>{@link #register(FCommand, TotalFreedomMod)} creates a processor per command and
 *       stores it; the first registration hooks the plugin's {@code COMMANDS} lifecycle event.
 *   <li>Every time the lifecycle event fires (startup and reloads), all stored processors
 *       are (re-)registered with the server dispatcher.
 * </ol>
 *
 * <h3>Handlers</h3>
 * A method annotated {@link Callback} is a handler. With a {@link Subcommand}, it hangs off
 * that literal path; without one (or with an empty path), it is a command <em>root</em>
 * handler. Replaces abstract run() method. Multiple root handlers may coexist when they take
 * different numbers of arguments (e.g. a bare toggle form plus a greedy-message form) or the
 * same number with disjoint token sets (boolean vs numeric); root handlers whose argument
 * chains overlap are ambiguous and logged. When no zero-argument root handler
 * exists, bare invocation falls back to echoing the {@link Command#usage() usage} string.
 *
 * <h3>Nested subcommands</h3>
 * {@link Subcommand#value()} is a space-separated literal path.
 * Methods are grouped into a trie keyed by path segment before the
 * Brigadier tree is built, so declarations sharing a prefix like {@code "set"} and
 * {@code "set default"} merge into one branch. A node may carry both handlers and children at
 * once. As with root handlers, multiple handlers may share a path when they take different
 * numbers of arguments (e.g. {@code "-s"} alone plus {@code "-s"} with a trailing greedy reason)
 * or the same number with disjoint token sets (boolean vs numeric, as in {@code /settings set});
 * handlers on the same path whose argument chains overlap are ambiguous and logged
 * (see {@link #conflicts}).
 *
 * <h3>Permissions</h3>
 * {@link Permission} on the {@code FCommand} class gates the whole command and is wired into
 * the root node's {@code requires()}. On a handler method, it overrides the class-level gate for that subcommand. 
 * Permission enforcement runs through {@link PermissionGate}.
 *
 * <h3>Arguments</h3>
 * Handler parameters after the optional leading sender map positionally to Brigadier argument
 * nodes: 
 * <li>Scalars and enums via {@link ArgumentResolver}</li> 
 * <li>{@code Player} via Paper's player selector</li>
 * <li>Any type registered in {@link ResolverRegistry}</li> 
 * <li>Any type annotated with {@link Resolve}</li>. 
 * A trailing {@code String} annotated {@link Greedy} consumes the rest of the line.
 *
 * <h3>Argument chain order (Brigadier constraint)</h3>
 * Argument nodes are built innermost-first: the leaf carries {@code executes()}; each parent
 * wraps the next via {@code .then()}. {@link #attachHandler} iterates right-to-left.
 *
 * <h3>Tab completion</h3>
 * {@link Completer} methods are collected up front into a {@code (subcommand path, position)}
 * lookup. When building the argument at that position, a matching completer is wired in via
 * {@code .suggests(...)}. 
 * <br/>
 * Positions with no explicit completer fall back to, in order:
 * <ol>
 *    <li>Candidates supplied by the type's {@link ResolverRegistry} registration</li>
 *    <li>{@link AbstractArgumentResolver#suggestions() Candidates from the parameter's resolver},
 *        which is what covers {@link Resolve @Resolve}-named parameters (they are matched by name,
 *        so there is no type registration to read a list from)</li>
 *    <li>Fuzzy-matched enum constant names</li>
 *    <li>Online player names and selectors, for {@code Player} parameters</li>
 *    <li>Brigadier/Paper defaults</li>
 * </ol>
 * A plain {@code String} parameter has no enumerable value set and therefore completes to nothing
 * unless the command declares a {@link Completer} for it.
 */             
@SuppressWarnings({"rawtypes", "unchecked"})
public final class CommandProcessor
{

    private static final Map<String, CommandProcessor> commands = new ConcurrentHashMap<>();
    private static final AtomicBoolean hooked = new AtomicBoolean(false);

    /**
     * Creates a processor for {@code command}; the first call also registers the
     * {@code COMMANDS} lifecycle handler that flushes every stored processor into the
     * server dispatcher each time the event fires (startup and reloads).
     */
    public static void register(FCommand command, TotalFreedomMod plugin)
    {
        Command meta = command.getClass().getAnnotation(Command.class);
        if (meta == null)
        {
            FLog.warning(String.format("%s is missing @Command; skipped", command.getClass().getSimpleName()));
            return;
        }

        // Apparently, brigadier is case-sensitive for the primary literal. Usage of toLowerCase() is to enforce lowercasing on them.
        commands.put(meta.name().toLowerCase(), new CommandProcessor(command, plugin, meta));
        FLog.debug(String.format("Queued /%s for Brigadier registration", meta.name().toLowerCase()));

        if (hooked.compareAndSet(false, true))
        {
            plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                commands.values().forEach(p -> p.registerWith(event.registrar())));
        }
    }

    public static void reset()
    {
        commands.clear();
    }

    private final FCommand command;
    private final TotalFreedomMod plugin;
    private final String commandName;
    private final String description;
    private final String usage;
    private final List<String> aliases;
    private final Permission classPermission;
    private final Map<CompleterKey, Method> completers = new HashMap<>();

    private CommandProcessor(FCommand command, TotalFreedomMod plugin, Command meta)
    {
        this.command = command;
        this.plugin = plugin;
        this.commandName = meta.name().toLowerCase();
        this.description = meta.description();
        this.usage = meta.usage();
        this.aliases = Arrays.stream(meta.aliases()).map(String::toLowerCase).toList();
        this.classPermission = command.getClass().getAnnotation(Permission.class);
    }

    private void registerWith(Commands registrar)
    {
        try
        {
            registrar.register(buildNode().build(), description, aliases);
            FLog.info(String.format("Registered /%s (aliases: %s)", commandName, String.join(", ", aliases)));
        }
        catch (Exception e)
        {
            FLog.severe(String.format("Failed to register /%s: \n%s", commandName, ExceptionUtils.getRootCauseMessage(e)));
        }
    }

    /**
     * @return true if {@code method} is already accessible or could be made accessible via
     * {@link Method#trySetAccessible()}; otherwise logs a warning and returns false.
     */
    private boolean ensureAccessible(Method method)
    {
        if (method.canAccess(command) || method.trySetAccessible())
        {
            return true;
        }
        FLog.warning(String.format("%s.%s is not accessible; skipped.", command.getClass().getSimpleName(), method.getName()));
        return false;
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildNode()
    {
        completers.clear();

        Arrays.stream(command.getClass().getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Completer.class))
                .filter(this::ensureAccessible)
                .forEach(method ->
                {
                    if (!isValidCompleterSignature(method))
                    {
                        FLog.warning(String.format("%s has @Completer but an invalid signature (expected (SenderType, String) -> List<String>); skipped.", method.getName()));
                        return;
                    }
                    Completer c = method.getAnnotation(Completer.class);
                    completers.put(new CompleterKey(c.value(), c.position()), method);
                });

        SubcommandNode trie = new SubcommandNode(null);
        List<Method> rootHandlers = new ArrayList<>();

        Arrays.stream(command.getClass().getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Callback.class))
                .filter(this::ensureAccessible)
                .forEach(method ->
                {
                    String pathValue = method.isAnnotationPresent(Subcommand.class)
                            ? method.getAnnotation(Subcommand.class).value().trim()
                            : "";

                    if (pathValue.isEmpty())
                    {
                        rootHandlers.stream()
                                .filter(prior -> conflicts(prior, method))
                                .forEach(prior -> FLog.warning(String.format(
                                        "Ambiguous root handlers on /%s:\n %s and %s cannot be told apart at parse time (%d argument(s) with overlapping tokens)",
                                        commandName, method.getName(), prior.getName(), argumentCount(method))));

                        rootHandlers.add(method);
                        return;
                    }

                    SubcommandNode node = Arrays.stream(pathValue.split("\\s+"))
                            .reduce(trie, 
                                (current, segment) -> current.children.computeIfAbsent(segment, SubcommandNode::new), 
                                (a, b) -> a);

                    node.handlerMethods
                        .stream()
                        .filter(existing -> conflicts(existing, method))
                        .forEach(existing -> FLog.warning(String.format(
                                "Ambiguous handlers for subcommand \"%s\" on /%s:\n %s and %s cannot be told apart at parse time (%d argument(s) with overlapping tokens)",
                                pathValue, commandName, method.getName(), existing.getName(), argumentCount(method))));

                    node.handlerMethods.add(method);
                });

        LiteralArgumentBuilder<CommandSourceStack> root = LiteralArgumentBuilder.literal(commandName);
        
        if (classPermission != null) 
        {
            root.requires(source -> PermissionGate.test(plugin, source.getSender(), classPermission, false));
        }

        trie.children.values().forEach(child -> root.then(buildBranch(child)));

        rootHandlers.forEach(rootHandler -> attachHandler(root, rootHandler));

        boolean bareExecutable = rootHandlers.stream()
                .anyMatch(rootHandler -> argumentCount(rootHandler) == 0);

        if (!bareExecutable && !usage.isEmpty()) 
        {
            root.executes(this::sendUsage);
        }

        return root;
    }

    /**
     * A node in the subcommand path trie; may carry a handler, children, or both.
     */
    private static final class SubcommandNode
    {
        final String literal;
        final Map<String, SubcommandNode> children = new LinkedHashMap<>();
        final List<Method> handlerMethods = new ArrayList<>();

        private SubcommandNode(String literal)
        {
            this.literal = literal;
        }
    }

    /**
     * Pairs a {@link Completer} to the subcommand path and argument position it applies to.
     * Root handlers use the empty path {@code ""}.
     */
    private record CompleterKey(String subcommandPath, int position) {}

    private LiteralArgumentBuilder<CommandSourceStack> buildBranch(SubcommandNode node)
    {
        LiteralArgumentBuilder<CommandSourceStack> branch = LiteralArgumentBuilder.literal(node.literal);
        node.children.values().forEach(child -> branch.then(buildBranch(child)));
        node.handlerMethods.forEach(handler -> attachHandler(branch, handler));
        return branch;
    }

    private static boolean isValidCompleterSignature(Method method)
    {
        Class<?>[] types = method.getParameterTypes();
        return (types.length == 2 || (types.length == 3 && types[2] == List.class))
            && ArgumentResolver.isSenderType(types[0])
            && types[1] == String.class
            && method.getReturnType() == List.class;
    }

    private static String subcommandPath(Method method)
    {
        return method.isAnnotationPresent(Subcommand.class)
            ? method.getAnnotation(Subcommand.class).value().trim()
            : "";
    }

    /**
     * Number of Brigadier positional argument nodes a handler produces, i.e. its parameters
     * minus the optional leading sender and minus any {@link Switch} parameters (which become
     * literal branches rather than argument nodes).
     */
    private static int argumentCount(Method method)
    {
        return positionalParams(method).size();
    }

    /**
     * A handler's positional parameters: everything except the optional leading sender and any
     * {@link Switch} parameters (which become literal branches rather than argument nodes).
     */
    private static List<Parameter> positionalParams(Method method)
    {
        Parameter[] params = method.getParameters();
        boolean hasSender = params.length > 0 && ArgumentResolver.isSenderType(params[0].getType());

        return Arrays.stream(params)
                .skip(hasSender ? 1 : 0)
                .filter(param -> !isSwitchParam(param))
                .toList();
    }

    /**
     * Whether two handlers on the same path cannot both be routed reliably. Handlers with
     * different positional arities never conflict: the longer chain simply extends the shorter
     * one's tree. Same-arity handlers are walked position by position: identically-named,
     * identically-typed parameters merge into the same Brigadier node, so the walk continues;
     * at the first diverging position the handlers split into sibling argument nodes, which is
     * only deterministic when the siblings have distinct node names AND mutually exclusive
     * token sets (currently: boolean vs a numeric type, since {@code true}/{@code false} never
     * parse as numbers). Everything else is order-dependent and flagged: identical signatures
     * (duplicate leaf), same-name-different-type positions (Brigadier merges nodes by name,
     * corrupting the tree), and overlapping siblings (string-like arguments accept any token;
     * numeric types overlap each other).
     */
    private static boolean conflicts(Method a, Method b)
    {
        List<Parameter> left = positionalParams(a);
        List<Parameter> right = positionalParams(b);

        if (left.size() != right.size())
        {
            return false;
        }

        for (int i = 0; i < left.size(); i++)
        {
            Parameter x = left.get(i);
            Parameter y = right.get(i);

            if (x.getName().equals(y.getName()) && x.getType() == y.getType())
            {
                continue;
            }

            return x.getName().equals(y.getName()) || !tokenSetsDisjoint(x, y);
        }

        return true;
    }

    /**
     * @return true if no input token can parse under both parameters' Brigadier argument types.
     */
    private static boolean tokenSetsDisjoint(Parameter a, Parameter b)
    {
        return (isBoolArg(a) && isNumericArg(b)) || (isNumericArg(a) && isBoolArg(b));
    }

    private static boolean isBoolArg(Parameter param)
    {
        return !isCustomResolved(param)
            && (param.getType() == boolean.class || param.getType() == Boolean.class);
    }

    private static boolean isNumericArg(Parameter param)
    {
        Class<?> type = param.getType();
        return !isCustomResolved(param)
            && (type == int.class || type == Integer.class
                || type == long.class || type == Long.class
                || type == double.class || type == Double.class
                || type == float.class || type == Float.class);
    }

    /**
     * @return true if {@code param} is a valid {@link Switch}-annotated boolean parameter.
     */
    private static boolean isSwitchParam(Parameter param)
    {
        return param.isAnnotationPresent(Switch.class)
            && (param.getType() == boolean.class || param.getType() == Boolean.class);
    }

    /**
     * Fallback for bare invocations of commands with no zero-argument root handler.
     */
    private int sendUsage(CommandContext<CommandSourceStack> ctx)
    {
        CommandSender sender = PermissionGate.resolveSender(ctx.getSource().getSender());
        if (!PermissionGate.test(plugin, sender, classPermission, true))
        {
            return 0;
        }

        String label = ctx.getInput();
        int sp = label.indexOf(' ');
        if (sp >= 0) label = label.substring(0, sp);
        if (label.startsWith("/")) label = label.substring(1);
        int ns = label.indexOf(':');
        if (ns >= 0) label = label.substring(ns + 1);

        sender.sendMessage(Component.text("Usage: " + usage.replace("<command>", label), NamedTextColor.RED));
        return 1;
    }

    /**
     * @param priorArgNames argument-node names of the positional parameters ahead of this one, in
     *                      order, supplied to completers that declare the third parameter
     */ 
    private SuggestionProvider<CommandSourceStack> buildSuggestionProvider(Method completerMethod, boolean greedy, List<String> priorArgNames)
    {
        Completer.Scope scope = completerMethod.getAnnotation(Completer.class).scope();
        boolean replacesWord = greedy && scope != Completer.Scope.ARGUMENT;
        boolean seesWholeArgument = greedy && scope != Completer.Scope.WORD;
        boolean wantsPriorArgs = completerMethod.getParameterCount() == 3;

        return (ctx, builder) ->
        {
            CommandSender sender = ctx.getSource().getSender();
            if (!completerMethod.getParameterTypes()[0].isInstance(sender))
            {
                return builder.buildFuture();
            }

            SuggestionsBuilder target = currentWord(builder, replacesWord);
            String typed = seesWholeArgument ? builder.getRemaining() : target.getRemaining();
            try
            {
                List<String> suggestions = wantsPriorArgs
                ? (List<String>) completerMethod.invoke(command, sender, typed, priorArgs(ctx, priorArgNames))
                : (List<String>) completerMethod.invoke(command, sender, typed);
                suggestions.forEach(target::suggest);
            }
            catch (Exception e)
            {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                FLog.severe(String.format("Error in completer %s: \n%s", completerMethod.getName(), ExceptionUtils.getRootCauseMessage(cause)));
            }
            return target.buildFuture();
        };
    }

    private static SuggestionsBuilder currentWord(SuggestionsBuilder builder, boolean greedy)
    {
        if (!greedy)
        {
            return builder;
        }

        int lastSpace = builder.getRemaining().lastIndexOf(' ');
        return lastSpace < 0 ? builder : builder.createOffset(builder.getStart() + lastSpace + 1);
    }

    /**
     * Text typed for the arguments named in {@code names}, in that order, for a completer that
     * asked to see them.
     * <p>
     * Values are read back off the parsed nodes rather than out of the resolved arguments, so a
     * completer sees the raw input even for arguments whose type would fail to resolve it. A name
     * that has not been parsed yet yields and empty string, which happens only when the client asks
     * about a position it has not reached.
     */
    /**
     * Argument-node names of the positional parameters ahead of {@code position}, in order
     */ 
    private static List<String> argumentNames(List<Parameter> positionalParams, int position)
    {
        return positionalParams.subList(0, position)
                               .stream()
                               .map(Parameter::getName)
                               .toList();
    }

    private static List<String> priorArgs(CommandContext<CommandSourceStack> ctx, List<String> names)
    {
        Map<String, String> typed = new HashMap<>();
        ctx.getNodes().forEach(parsed -> typed.put(parsed.getNode().getName(), parsed.getRange().get(ctx.getInput())));

        return names.stream()
                    .map(name -> typed.getOrDefault(name,""))
                    .toList();
    }
    
     /** 
     * Default suggester for an argument with no explicit {@link Completer}: fuzzy-matches the partial
     * input against {@code candidates}, falling back to the enum's constant names when the parameter
     * is enum-typed and no candidates were produced.
     * <p>
     * The fallback matters because a type-bound resolver over an enum
     * (e.g. {@code WorldTime}) makes the parameter custom-resolved, which would otherwise take
     * priority over the enum names and leave the argument with nothing to suggest. Candidates are
     * fetched per request so live sources stay current, and the enum names are resolved once here.
     *
     * @param candidates   candidate source, or {@code null} when the parameter has no resolver
     * @param declaredType the parameter's type, consulted only for the enum fallback
     */
    private SuggestionProvider<CommandSourceStack> buildCandidateSuggestionProvider(
        Supplier<List<String>> candidates, Class<?> declaredType)
    {
        List<String> enumNames = declaredType != null && declaredType.isEnum()
                ? Arrays.stream(declaredType.getEnumConstants())
                        .map(constant -> ((Enum<?>) constant).name())
                        .toList()
                : List.of();

        return (ctx, builder) -> {
            List<String> values = candidates != null ? candidates.get() : List.of();
            if (values.isEmpty())
            {
                values = enumNames;
            }

            FuzzyMatch.filter(values, builder.getRemaining())
                    .forEach(builder::suggest);

            return builder.buildFuture();
        };
    }

    /**
     * Candidate source for a positional parameter with no explicit {@link Completer}, in order:
     * the type-bound list from its {@link ResolverRegistry} registration, then the
     * {@link AbstractArgumentResolver#suggestions() resolver's own} candidates. The latter is what
     * covers {@link Resolve @Resolve}-named parameters, whose resolver is chosen by name and so has
     * no type registration to read a list from.
     *
     * @return a candidate supplier, or {@code null} when the parameter has no custom resolver
     */
    private static Supplier<List<String>> candidatesFor(Parameter param)
    {
        Supplier<List<String>> byType = ResolverRegistry.suggestionsFor(param.getType());
        if (byType != null)
        {
            return byType;
        }

        if (!isCustomResolved(param))
        {
            return null;
        }

        AbstractArgumentResolver<?> resolver = resolverFor(param);
        return resolver != null ? resolver::suggestions : null;
    }

    /**
     * Fallback suggester for {@code Player} parameters. Paper's player selector supplies its own
     * suggestions natively, but only when the suggestion context carries a source it recognises,
     * which is not guaranteed for commands registered through this framework. Serving names and
     * selectors ourselves makes completion deterministic.
     */
    private SuggestionProvider<CommandSourceStack> buildPlayerSuggestionProvider()
    {
        return (ctx, builder) -> {
            // Single-target selectors only: these nodes are built with ArgumentTypes.player(), not
            // players(), so @a is rejected at resolve time and must not be offered.
            List<String> names = new ArrayList<>(List.of("@p", "@r", "@s"));
            plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .sorted()
                    .forEach(names::add);

            FuzzyMatch.filter(names, builder.getRemaining())
                    .forEach(builder::suggest);

            return builder.buildFuture();
        };
    }

    /** 
     * @return true if the parameter routes through a custom resolver rather than a native Brigadier type. 
     */
    private static boolean isCustomResolved(Parameter param)
    {
        return param.isAnnotationPresent(Resolve.class) || ResolverRegistry.hasType(param.getType());
    }

    private static AbstractArgumentResolver<?> resolverFor(Parameter param)
    {
        Resolve ann = param.getAnnotation(Resolve.class);
        if (ann != null && !ann.value().isEmpty())
        {
            return ResolverRegistry.byName(ann.value());
        }
        return ResolverRegistry.byType(param.getType());
    }

    /**
     * Attaches a handler's switch branches, argument chain, and/or {@code executes()} onto its
     * literal branch. Splits the method's parameters into {@link Switch} parameters (which
     * become literal branches, one subset at a time via {@link #attachSwitchLevel}) and
     * positional parameters (which become the Brigadier argument chain via
     * {@link #buildPositionalChain}).
     */
    private void attachHandler(LiteralArgumentBuilder<CommandSourceStack> branch, Method method)
    {
        String subPath = subcommandPath(method);
        Parameter[] params = method.getParameters();
        boolean hasSender = params.length > 0 && ArgumentResolver.isSenderType(params[0].getType());
        int argStart = hasSender ? 1 : 0;

        List<Parameter> switchParams = new ArrayList<>();
        List<Integer> switchIndices = new ArrayList<>();
        List<Parameter> positionalParams = new ArrayList<>();
        List<Integer> positionalIndices = new ArrayList<>();

        IntStream.range(argStart, params.length)
                .forEach(i -> 
                {
                    Parameter param = params[i];
                    
                    if (param.isAnnotationPresent(Switch.class) && !isSwitchParam(param)) 
                    {
                        FLog.warning(String.format("@Switch on non-boolean parameter '%s' of /%s %s is ignored", param.getName(), commandName, subPath));
                    }
                    
                    if (isSwitchParam(param)) 
                    {
                        switchParams.add(param);
                        switchIndices.add(i);
                    } 
                    else 
                    {
                        positionalParams.add(param);
                        positionalIndices.add(i);
                    }
                });

        Permission methodPermission = method.isAnnotationPresent(Permission.class)
                ? method.getAnnotation(Permission.class)
                : classPermission;

        attachSwitchLevel(branch, method, subPath, methodPermission, params, hasSender,
                switchParams, switchIndices, positionalParams, positionalIndices, 0, new boolean[switchParams.size()]);
    }

    /**
     * Recursively attaches, at position {@code from} into the declared switch order, both the
     * "no more switches" terminal (the positional argument chain, or direct {@code executes()})
     * and one literal branch per remaining switch that recurses to include it. Generates every
     * subset of the handler's switches, in declaration order, as its own literal path.
     */
    private void attachSwitchLevel(
        ArgumentBuilder<CommandSourceStack, ?> parent, Method method, String subPath, Permission methodPermission,
        Parameter[] params, boolean hasSender,
        List<Parameter> switchParams, List<Integer> switchIndices,
        List<Parameter> positionalParams, List<Integer> positionalIndices,
        int from, boolean[] chosen)
    {
        com.mojang.brigadier.Command<CommandSourceStack> dispatch = buildDispatch(
            method, subPath, methodPermission, params, hasSender, switchIndices, chosen.clone());
            
        RequiredArgumentBuilder<CommandSourceStack, ?> head = buildPositionalChain(positionalParams, positionalIndices, subPath, dispatch);
        
        if (head != null) {
            parent.then(head);
        } else {
            parent.executes(dispatch);
        }

        IntStream.range(from, switchParams.size())
                .forEach(j -> {
                    Switch sw = switchParams.get(j).getAnnotation(Switch.class);
                    LiteralArgumentBuilder<CommandSourceStack> switchBranch = LiteralArgumentBuilder.literal("-" + sw.value());
                    
                    boolean[] next = chosen.clone();
                    next[j] = true;
                    
                    attachSwitchLevel(switchBranch, method, subPath, methodPermission, params, hasSender,
                        switchParams, switchIndices, positionalParams, positionalIndices, j + 1, next);
                        
                    parent.then(switchBranch);
                });
    }

    /**
     * Builds the positional Brigadier argument chain (innermost-first) for a handler's non-switch
     * parameters, terminating in {@code dispatch}. Returns {@code null} when there are no
     * positional parameters, in which case the caller should call {@code executes(dispatch)} directly.
     */
    private RequiredArgumentBuilder<CommandSourceStack, ?> buildPositionalChain(
        List<Parameter> positionalParams, List<Integer> positionalIndices, String subPath,
        com.mojang.brigadier.Command<CommandSourceStack> dispatch)
    {
        return IntStream.iterate(positionalParams.size() - 1, i -> i >= 0, i -> i - 1) // IntStream is my new favorite thing ever
                .boxed()
                .reduce(
                    (RequiredArgumentBuilder<CommandSourceStack, ?>) null, 
                    (RequiredArgumentBuilder<CommandSourceStack, ?> head, Integer position) -> {
                        Parameter param = positionalParams.get(position);
                        Class<?> type = param.getType();
                        boolean greedy = param.isAnnotationPresent(Greedy.class);
                        
                        if (greedy && position != positionalParams.size() - 1) {
                            FLog.warning(String.format("@Greedy on non-last parameter '%s' of /%s %s is ignored", param.getName(), commandName, subPath));
                            greedy = false;
                        }

                        RequiredArgumentBuilder<CommandSourceStack, ?> arg;
                        if (greedy) {
                            arg = RequiredArgumentBuilder.argument(param.getName(), StringArgumentType.greedyString());
                        } else if (isCustomResolved(param)) {
                            arg = RequiredArgumentBuilder.argument(param.getName(), StringArgumentType.word());
                        } else if (ArgumentResolver.isPlayerArgType(type)) {
                            arg = RequiredArgumentBuilder.argument(param.getName(), ArgumentTypes.player());
                        } else {
                            arg = RequiredArgumentBuilder.argument(param.getName(), (ArgumentType<?>) ArgumentResolver.resolve(type));
                        }

                        Method completer = completers.get(new CompleterKey(subPath, position));
                        Supplier<List<String>> candidates = candidatesFor(param);
                        if (completer != null) {
                            arg.suggests(buildSuggestionProvider(completer, greedy, argumentNames(positionalParams, position)));
                        } else if (candidates != null || type.isEnum()) {
                            arg.suggests(buildCandidateSuggestionProvider(candidates, type));
                        } else if (ArgumentResolver.isPlayerArgType(type)) {
                            arg.suggests(buildPlayerSuggestionProvider());
                        }

                        if (position == positionalParams.size() - 1) {
                            arg.executes(dispatch);
                        }
                        if (head != null) {
                            RequiredArgumentBuilder rawHead = head;
                            arg.then(rawHead);
                        }
                        return arg;
                    }, 
                    (a, b) -> a
                );
    }

    /**
     * Builds the {@code Command<CommandSourceStack>} invoked once all of a handler's argument
     * nodes (positional and switch) have parsed successfully. {@code switchValues} is fixed at
     * tree-build time (one per subset generated by {@link #attachSwitchLevel}); positional values
     * are read from {@code ctx} at dispatch time.
     */
    private com.mojang.brigadier.Command<CommandSourceStack> buildDispatch(
    Method method, String subPath, Permission methodPermission, Parameter[] params, boolean hasSender,
    List<Integer> switchIndices, boolean[] switchValues)
    {
        int argStart = hasSender ? 1 : 0;
        return ctx ->
        {
            CommandSourceStack source = ctx.getSource();
            CommandSender sender = PermissionGate.resolveSender(source.getSender());

            if (!PermissionGate.test(plugin, sender, methodPermission, classPermission, true))
            {
                return 0;
            }

            if (hasSender && !params[0].getType().isInstance(sender))
            {
                sender.sendMessage(PermissionGate.ONLY_PLAYER_MESSAGE);
                return 0;
            }

            if (sender instanceof Player player && method.isAnnotationPresent(Cooldown.class))
            {
                Cooldown cooldownAnn = method.getAnnotation(Cooldown.class);
                String key = cooldownKey(commandName, subPath);
                if (CooldownManager.isOnCooldown(player.getUniqueId(), key))
                {
                    long remainingSec = (long) Math.ceil(
                        CooldownManager.remainingMillis(player.getUniqueId(), key) / 1000.0);
                    sender.sendMessage(Component.text(
                        cooldownAnn.message().replace("{remaining}", Long.toString(remainingSec))));
                    return 0;
                }
                CooldownManager.setCooldown(
                    player.getUniqueId(), key, cooldownAnn.unit().toMillis(cooldownAnn.value()));
            }

            Object[] invokeArgs = new Object[params.length];
            if (hasSender) invokeArgs[0] = sender;
            
            IntStream.range(0, switchIndices.size())
                    .forEach(s -> invokeArgs[switchIndices.get(s)] = switchValues[s]);

            try
            {
                for (int i = argStart; i < params.length; i++)
                {
                    if (isSwitchParam(params[i])) continue;

                    String paramName = params[i].getName();
                    Class<?> type = params[i].getType();

                    if (isCustomResolved(params[i]))
                    {
                        AbstractArgumentResolver<?> resolver = resolverFor(params[i]);
                        if (resolver == null)
                        {
                            sender.sendMessage(Component.text("Internal command error: no resolver for argument " + paramName, NamedTextColor.RED));
                            return 0;
                        }
                        Resolve ann = params[i].getAnnotation(Resolve.class);
                        String strategy = ann != null ? ann.strategy() : "";
                        String raw = ctx.getArgument(paramName, String.class);
                        invokeArgs[i] = resolver.resolve(raw, strategy);
                    }
                    else if (ArgumentResolver.isPlayerArgType(type))
                    {
                        var resolver = ctx.getArgument(paramName, PlayerSelectorArgumentResolver.class);
                        List<Player> players = resolver.resolve(source);
                        if (players.isEmpty())
                        {
                            sender.sendMessage(Component.text("Player not found.", NamedTextColor.GRAY));
                            return 0;
                        }
                        invokeArgs[i] = players.get(0);
                    }
                    else if (type.isEnum())
                    {
                        String raw = ctx.getArgument(paramName, String.class);
                        try
                        {
                            invokeArgs[i] = resolveEnum(type, raw);
                        }
                        catch (ArgumentResolutionException | IllegalArgumentException e)
                        {
                            sender.sendMessage(Component.text("Invalid value '" + raw + "' for argument: " + paramName, NamedTextColor.RED));
                            return 0;
                        }
                    }
                    else
                    {
                        invokeArgs[i] = ctx.getArgument(paramName, type);
                    }
                }
            }
            catch (ArgumentResolutionException e)
            {
                sender.sendMessage(e.getFormattedMessage());
                return 0;
            }

            try
            {
                Object result = method.invoke(command, invokeArgs);
                if (result instanceof Boolean b) return b ? 1 : 0;
                return 1;
            }
            catch (InvocationTargetException e)
            {
                Throwable cause = e.getCause();
                if (cause instanceof CommandFailException cfe)
                {
                    sender.sendMessage(MessageUtils.parse(cfe.getMessage()));
                    return 0;
                }
                if (cause instanceof ArgumentResolutionException are)
                {
                    sender.sendMessage(are.getFormattedMessage());
                    return 0;
                }
                FLog.severe(String.format("Error in /%s %s: \n%s", commandName, subPath, ExceptionUtils.getRootCauseMessage(e)));
                sender.sendMessage(Component.text("Command error: " + (cause == null || cause.getMessage() == null ? "Unknown cause" : cause.getMessage()), NamedTextColor.RED));
                return 0;
            }
            catch (Exception e)
            {
                FLog.severe(String.format("Error in /%s %s: \n%s", commandName, subPath, ExceptionUtils.getRootCauseMessage(e)));
                return 0;
            }
        };
    }


    /**
     * Resolves a non-{@link Resolve @Resolve} enum parameter through the 
     * {@link me.totalfreedom.totalfreedommod.cmd.resolver.EnumArgumentResolver EnumArgumentResolver}, 
     * forcing {@code mode=uppercase} so the raw token is always uppercased 
     * before either the enum's {@code fromString} or {@code Enum::valueOf} lookup runs.
     */
    private static Object resolveEnum(Class<?> enumType, String raw)
    {
        AbstractArgumentResolver<?> resolver = ResolverRegistry.byName("Enum");
        return resolver.resolve(raw, "class=" + enumType.getName() + ",mode=uppercase");
    }

    public static String cooldownKey(String commandName, String subcommandValue)
    {
        return commandName + ":" + subcommandValue;
    }
}

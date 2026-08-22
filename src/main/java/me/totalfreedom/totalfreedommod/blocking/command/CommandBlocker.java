package me.totalfreedom.totalfreedommod.blocking.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.CommandMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.blocking.command.CommandBlockerEntry.PatternToken;
import me.totalfreedom.totalfreedommod.blocking.command.CommandBlockerEntry.TokenType;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public class CommandBlocker extends FreedomService
{

    private final Pattern flagPattern = Pattern.compile("(:([0-9]){5,})");
    private final Pattern restrictedSelectorPattern = Pattern.compile("(?i)(?<![a-z0-9_])(?:@[aenrs](?=\\[|\\b)|@p(?=\\[))");
    //
    private final Map<String, List<CommandBlockerEntry>> entriesByBaseCommand = Maps.newHashMap();
    private final List<String> unknownCommands = Lists.newArrayList();
    private List<String> serverCommandBlockedSubstrings = Lists.newArrayList();
    private long lastServerCommandBlockWarningTick = 0L;
    private long blockedServerCommandsSinceLastWarning = 0L;

    private static final Comparator<CommandBlockerEntry> MOST_SPECIFIC_FIRST =
            Comparator.<CommandBlockerEntry>comparingInt(CommandBlockerEntry::literalTokenCount).reversed()
                    .thenComparing(Comparator.<CommandBlockerEntry>comparingInt(CommandBlockerEntry::patternTokenCount).reversed());

    public CommandBlocker(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        load();
    }

    @Override
    protected void onStop()
    {
        entriesByBaseCommand.clear();
    }

    public void load()
    {
        entriesByBaseCommand.clear();
        unknownCommands.clear();
        loadServerCommandBlockerConfig();

        final CommandMap commandMap = getCommandMap();
        if (commandMap == null)
        {
            FLog.severe("Error loading commandMap.");
            return;
        }

        @SuppressWarnings("unchecked")
        List<String> blockedCommands = (List<String>) ConfigEntry.BLOCKED_COMMANDS.getList();
        int loadedCount = 0;
        for (String rawEntry : blockedCommands)
        {
            final String[] parts = rawEntry.split(":");
            if (parts.length < 3 || parts.length > 4)
            {
                FLog.warning("Invalid command blocker entry: " + rawEntry);
                continue;
            }

            final CommandBlockerRank rank = CommandBlockerRank.fromToken(parts[0]);
            final CommandBlockerAction action = CommandBlockerAction.fromToken(parts[1]);
            String commandSpec = parts[2].toLowerCase().substring(1);
            final String message = (parts.length > 3 ? parts[3] : null);

            if (rank == null || action == null || commandSpec == null || commandSpec.isEmpty())
            {
                FLog.warning("Invalid command blocker entry: " + rawEntry);
                continue;
            }

            final String[] specParts = commandSpec.split("\\s+");
            String commandName = specParts[0];

            final List<PatternToken> patternTokens = new ArrayList<>();
            boolean malformed = false;
            for (int i = 1; i < specParts.length; i++)
            {
                if (specParts[i].isEmpty())
                {
                    continue;
                }
                PatternToken token = PatternToken.of(specParts[i]);
                if (token.type == TokenType.MULTI && i != specParts.length - 1)
                {
                    FLog.warning("Invalid command blocker entry (\"{*}\" may only appear as the last token): " + rawEntry);
                    malformed = true;
                    break;
                }
                patternTokens.add(token);
            }
            if (malformed)
            {
                continue;
            }

            final Command command = commandMap.getCommand(commandName);
            if (command == null)
            {
                unknownCommands.add(commandName);
            }
            else
            {
                commandName = command.getName().toLowerCase();
            }

            final CommandBlockerEntry blockedCommandEntry = new CommandBlockerEntry(rank, action, commandName, patternTokens, message);
            registerEntry(commandName, blockedCommandEntry);

            if (command != null)
            {
                for (String alias : command.getAliases())
                {
                    registerEntry(alias.toLowerCase(), blockedCommandEntry);
                }
            }
            loadedCount++;
        }

        for (List<CommandBlockerEntry> bucket : entriesByBaseCommand.values())
        {
            bucket.sort(MOST_SPECIFIC_FIRST);
        }

        FLog.info("Loaded " + loadedCount + " blocked commands (" + (loadedCount - unknownCommands.size()) + " known).");
    }

    private void registerEntry(String baseCommand, CommandBlockerEntry entry)
    {
        entriesByBaseCommand.computeIfAbsent(baseCommand, k -> new ArrayList<>()).add(entry);
    }

    private void loadServerCommandBlockerConfig()
    {
        serverCommandBlockedSubstrings = Lists.newArrayList();

        @SuppressWarnings("unchecked")
        List<String> blockedSubstrings = (List<String>) ConfigEntry.BLOCK_SERVER_COMMANDS_BLOCKED_SUBSTRINGS.getList();
        if (blockedSubstrings != null)
        {
            for (String token : blockedSubstrings)
            {
                if (token == null)
                {
                    continue;
                }
                final String trimmed = token.trim();
                if (!trimmed.isEmpty())
                {
                    serverCommandBlockedSubstrings.add(trimmed.toLowerCase());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event)
    {
        final Player player = event.getPlayer();
        final String command = event.getMessage();

        if (!plugin.al.isAdmin(player) && containsRestrictedTarget(command))
        {
            FUtil.playerMsg(player, "You may not use player selectors in commands.");
            event.setCancelled(true);
            return;
        }

        // Blocked commands
        if (isCommandBlocked(command, player, true))
        {
            // CommandBlocker handles messages and broadcasts
            event.setCancelled(true);
        }
    }

    private boolean containsRestrictedTarget(String command)
    {
        return restrictedSelectorPattern.matcher(command).find();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event)
    {
        if (!Boolean.TRUE.equals(ConfigEntry.BLOCK_SERVER_COMMANDS_ENABLED.getBoolean()))
        {
            return;
        }

        final CommandSender sender = event.getSender();
        if (sender instanceof Player)
        {
            return;
        }
        if (sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender)
        {
            return;
        }

        final boolean blockAtNamedSenders = Boolean.TRUE.equals(ConfigEntry.BLOCK_SERVER_COMMANDS_BLOCK_AT_NAMED_SENDERS.getBoolean());
        final boolean blockCommandBlockHolders = Boolean.TRUE.equals(ConfigEntry.BLOCK_SERVER_COMMANDS_BLOCK_COMMAND_BLOCK_HOLDERS.getBoolean());

        final boolean isAtNamedSender = "@".equals(sender.getName());
        final boolean isCommandBlockHolder = sender instanceof BlockCommandSender || sender instanceof CommandMinecart;

        final String rawCommand = event.getCommand();
        if (rawCommand == null || rawCommand.isEmpty())
        {
            return;
        }

        if (blockCommandBlockHolders && isCommandBlockHolder)
        {
            event.setCancelled(true);
            logThrottledBlock(sender, rawCommand);
            return;
        }

        if (!(blockAtNamedSenders && isAtNamedSender))
        {
            return;
        }

        if (isCommandBlocked(rawCommand, sender, false, true))
        {
            event.setCancelled(true);
            logThrottledBlock(sender, rawCommand);
            return;
        }

        if (!matchesServerCommandBlocklist(rawCommand))
        {
            return;
        }

        event.setCancelled(true);
        logThrottledBlock(sender, rawCommand);
    }

    private void logThrottledBlock(CommandSender sender, String rawCommand)
    {
        if (!Boolean.TRUE.equals(ConfigEntry.BLOCK_SERVER_COMMANDS_LOG_THROTTLED_WARNINGS.getBoolean()))
        {
            return;
        }

        blockedServerCommandsSinceLastWarning++;

        final long intervalTicks = Math.max(1, ConfigEntry.BLOCK_SERVER_COMMANDS_LOG_INTERVAL_TICKS.getInteger());
        final long nowTick = server.getCurrentTick();
        if (lastServerCommandBlockWarningTick == 0L || nowTick - lastServerCommandBlockWarningTick >= intervalTicks)
        {
            FLog.warning("[TFM] Blocked " + blockedServerCommandsSinceLastWarning
                    + " server-side command(s) from " + sender.getClass().getSimpleName()
                    + " (\"" + sender.getName() + "\"). Last: " + rawCommand);

            lastServerCommandBlockWarningTick = nowTick;
            blockedServerCommandsSinceLastWarning = 0L;
        }
    }

    private boolean matchesServerCommandBlocklist(String command)
    {
        if (serverCommandBlockedSubstrings.isEmpty())
        {
            return false;
        }

        String normalized = command.trim();
        if (normalized.startsWith("/"))
        {
            normalized = normalized.substring(1);
        }
        normalized = normalized.toLowerCase();

        for (String token : serverCommandBlockedSubstrings)
        {
            if (normalized.contains(token))
            {
                return true;
            }
        }

        return false;
    }

    public boolean isCommandBlocked(String command, CommandSender sender)
    {
        return isCommandBlocked(command, sender, false, false);
    }

    public boolean isCommandBlocked(String command, CommandSender sender, boolean doAction)
    {
        return isCommandBlocked(command, sender, doAction, false);
    }

    public boolean isCommandBlocked(String command, CommandSender sender, boolean doAction, boolean ignoreRank)
    {
        if (command == null || command.isEmpty())
        {
            return false;
        }

        // Format
        command = command.toLowerCase().trim();
        command = command.startsWith("/") ? command.substring(1) : command;

        // Check for plugin specific commands
        final String[] commandParts = command.split("\\s+");
        if (commandParts.length == 0 || commandParts[0].isEmpty())
        {
            return false;
        }
        if (commandParts[0].contains(":"))
        {
            if (doAction)
            {
                FUtil.playerMsg(sender, "Plugin specific commands are disabled.");
            }
            return true;
        }

        for (String part : commandParts)
        {
            Matcher matcher = flagPattern.matcher(part);
            if (!matcher.matches())
            {
                continue;
            }
            if (doAction)
            {
                FUtil.playerMsg(sender, "That command contains an illegal number: " + matcher.group(1));
            }
            return true;
        }

        final List<CommandBlockerEntry> bucket = entriesByBaseCommand.get(commandParts[0]);
        if (bucket == null || bucket.isEmpty())
        {
            return false;
        }

        final String[] inputArgs = new String[commandParts.length - 1];
        System.arraycopy(commandParts, 1, inputArgs, 0, inputArgs.length);

        CommandBlockerEntry match = null;
        for (CommandBlockerEntry candidate : bucket)
        {
            if (candidate.matches(inputArgs))
            {
                match = candidate;
                break;
            }
        }

        if (match == null)
        {
            return false;
        }

        if (!ignoreRank && match.getRank().hasPermission(sender))
        {
            return false;
        }

        if (doAction)
        {
            match.doActions(sender);
        }

        return true;
    }

    private CommandMap getCommandMap()
    {
        return server.getCommandMap();
    }
}

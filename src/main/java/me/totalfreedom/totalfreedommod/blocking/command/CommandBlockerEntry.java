package me.totalfreedom.totalfreedommod.blocking.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.PluginProvider;
import me.totalfreedom.totalfreedommod.util.FUtil;

import lombok.Getter;

public class CommandBlockerEntry
{

    public enum TokenType
    {
        LITERAL, SINGLE, MULTI
    }

    public static final class PatternToken
    {
        public final TokenType type;
        public final String value;

        private PatternToken(TokenType type, String value)
        {
            this.type = type;
            this.value = value;
        }

        public static PatternToken of(String raw)
        {
            String t = raw.trim().toLowerCase();
            if (t.equals("{*}"))
            {
                return new PatternToken(TokenType.MULTI, null);
            }
            if (t.equals("{?}"))
            {
                return new PatternToken(TokenType.SINGLE, null);
            }
            return new PatternToken(TokenType.LITERAL, t);
        }

        public boolean isWildcard()
        {
            return type != TokenType.LITERAL;
        }
    }

    @Getter
    private final CommandBlockerRank rank;
    @Getter
    private final CommandBlockerAction action;
    @Getter
    private final String command;
    @Getter
    private final String subCommand;
    @Getter
    private final String message;
    @Getter
    private final List<PatternToken> patternTokens;
    @Getter
    private final boolean wildcardPattern;

    public CommandBlockerEntry(CommandBlockerRank rank, CommandBlockerAction action, String command, String message)
    {
        this(rank, action, command, (List<PatternToken>) null, message);
    }

    public CommandBlockerEntry(CommandBlockerRank rank, CommandBlockerAction action, String command, String subCommand, String message)
    {
        this(rank, action, command, tokenize(subCommand), message);
    }

    public CommandBlockerEntry(CommandBlockerRank rank, CommandBlockerAction action, String command, List<PatternToken> patternTokens, String message)
    {
        this.rank = rank;
        this.action = action;
        this.command = command;
        this.patternTokens = patternTokens == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(patternTokens));
        this.subCommand = renderTokens(this.patternTokens);
        boolean anyWildcard = false;
        for (PatternToken t : this.patternTokens)
        {
            if (t.isWildcard())
            {
                anyWildcard = true;
                break;
            }
        }
        this.wildcardPattern = anyWildcard;
        this.message = "&7" + (message == null || message.equals("_") ? "That command is blocked." : message);
    }

    public static List<PatternToken> tokenize(String raw)
    {
        if (raw == null)
        {
            return Collections.emptyList();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty())
        {
            return Collections.emptyList();
        }
        List<PatternToken> tokens = new ArrayList<>();
        for (String part : trimmed.split("\\s+"))
        {
            if (part.isEmpty())
            {
                continue;
            }
            tokens.add(PatternToken.of(part));
        }
        return tokens;
    }

    private static String renderTokens(List<PatternToken> tokens)
    {
        if (tokens.isEmpty())
        {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++)
        {
            if (i > 0)
            {
                sb.append(' ');
            }
            PatternToken t = tokens.get(i);
            switch (t.type)
            {
                case LITERAL -> sb.append(t.value);
                case SINGLE -> sb.append("{?}");
                case MULTI -> sb.append("{*}");
            }
        }
        return sb.toString();
    }

    public boolean matches(String[] inputArgs)
    {
        if (!wildcardPattern)
        {
            if (patternTokens.isEmpty())
            {
                return true;
            }
            if (inputArgs.length < patternTokens.size())
            {
                return false;
            }
            for (int i = 0; i < patternTokens.size(); i++)
            {
                if (!patternTokens.get(i).value.equals(inputArgs[i]))
                {
                    return false;
                }
            }
            return true;
        }
        // Wildcard match: strict length, {?} = any one token, trailing {*} = 1+ tokens.
        for (int i = 0; i < patternTokens.size(); i++)
        {
            PatternToken pt = patternTokens.get(i);
            if (pt.type == TokenType.MULTI)
            {
                return inputArgs.length > i;
            }
            if (i >= inputArgs.length)
            {
                return false;
            }
            if (pt.type == TokenType.LITERAL && !pt.value.equals(inputArgs[i]))
            {
                return false;
            }
        }
        return inputArgs.length == patternTokens.size();
    }

    public int literalTokenCount()
    {
        int n = 0;
        for (PatternToken t : patternTokens)
        {
            if (t.type == TokenType.LITERAL)
            {
                n++;
            }
        }
        return n;
    }

    public int patternTokenCount()
    {
        return patternTokens.size();
    }

    public void doActions(CommandSender sender)
    {
        if (action == CommandBlockerAction.BLOCK_AND_EJECT && sender instanceof Player)
        {
            PluginProvider.get().ae.autoEject((Player) sender, "You used a prohibited command: " + command);
            FUtil.bcastMsg(sender.getName() + " was automatically kicked for using harmful commands.", NamedTextColor.RED);
            return;
        }

        if (action == CommandBlockerAction.BLOCK_UNKNOWN)
        {
            FUtil.playerMsg(sender, "Unknown command. Type \"help\" for help.", NamedTextColor.WHITE);
            return;
        }

        FUtil.playerMsg(sender, FUtil.colorize(message));
    }
}

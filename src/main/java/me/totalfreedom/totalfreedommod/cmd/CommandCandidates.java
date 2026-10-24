package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import org.bukkit.Server;
import org.bukkit.command.CommandSender;

/**
 * Tab-completion source for arguments that are themselves a command line, as in {@code /wildcard}
 * and {@code /gcmd}.
 * <p>
 * Completion is delegated to the server's own command map, so the inner command gets the same
 * suggestions it would get if it had been typed directly, arguments included.
 */
final class CommandCandidates
{

    private CommandCandidates()
    {
    }

    /**
     * Completions for the final word of {@code typed}, an entire inner command line, as seen by
     * {@code sender}.
     * <p>
     * The whole line has to be passed in because that is what decides the candidates: the words
     * before the cursor are what tells the command map whether a command name or one of that
     * command's arguments is being typed. The completions come back covering the final word only,
     * which pairs with
     * {@link me.totalfreedom.totalfreedommod.cmd.internal.annotation.Completer.Scope#ARGUMENT_TO_WORD}.
     *
     * @param sender the sender the suggestions are filtered for, not the player the command would
     *               eventually run as
     */
    static List<String> inner(Server server, CommandSender sender, String typed)
    {
        final List<String> completions = server.getCommandMap().tabComplete(sender, typed);
        return completions == null ? List.of() : completions;
    }
}

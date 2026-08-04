package me.totalfreedom.totalfreedommod.cmd;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.rank.Rank;
import me.totalfreedom.totalfreedommod.ssh.AttributedConsoleSender;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;

@Permission(level = Rank.SUPER_ADMIN, permission = "tfm.admin.wildcard")
@Command(
        name = "wildcard",
        description = "Run any command on all users, username placeholder = ?.", 
        usage = "/wildcard <command> (use ? to insert each player's username)")
public class Command_wildcard extends FCommand
{
    public static List<String> BLOCKED_COMMANDS = Arrays.asList(
            "wildcard",
            "gtfo",
            "doom",
            "saconfig",
            "crash"
    );

    @Completer(value = "", position = 0, scope = Completer.Scope.ARGUMENT_TO_WORD)
    public List<String> completeCommand(CommandSender sender, String partial)
    {
        return CommandCandidates.inner(server(), sender, partial);
    }

    @Callback
    public void wildcard(CommandSender sender, @Greedy String command)
    {
        final String[] splitCommand = command.split(" ");

        if (Arrays.stream(splitCommand).anyMatch(this::isBlacklistedCommand)
                || plugin().cb.isCommandBlocked(command, sender))
        {
            msg(sender, "<red>Did you really think that was going to work?");
            return;
        }

        // Remote channels (SSH/Discord) hand us an attribution wrapper, and CraftBukkit refuses to
        // turn anything but its own sender types into a vanilla command listener, so the wrapper has
        // to be peeled off before the command is re-dispatched through Brigadier.
        final CommandSender dispatcher = AttributedConsoleSender.unwrap(sender);

        server().getOnlinePlayers().forEach(player ->
            {
                String processedCommand = command.replaceAll("\\x3f", player.getName());
                msg(sender, "<gray>Running command: <command>", Placeholder.unparsed("command", processedCommand));

                if (!server().dispatchCommand(dispatcher, processedCommand))
                    msg(sender, "<red>Failed to execute command. Are you sure you entered it correctly?");
            });
    }

    private boolean isBlacklistedCommand(String command)
    {
        return BLOCKED_COMMANDS.stream()
                               .map(blocked -> server().getCommandMap().getCommand(blocked))
                               .filter(Objects::nonNull)
                               .anyMatch(cmd ->
                                    cmd.getName().equalsIgnoreCase(command) || 
                                    cmd.getAliases().contains(command));
    }
}

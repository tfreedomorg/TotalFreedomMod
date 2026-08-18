package me.totalfreedom.totalfreedommod.cmd.resolver;

import me.totalfreedom.totalfreedommod.util.AdventureUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import me.totalfreedom.totalfreedommod.cmd.FCommand;

import java.util.List;
import java.util.UUID;

public class PlayerArgumentResolver implements AbstractArgumentResolver<Player>
{
    private Player resolveDefault(final CommandSender sender, final String arg)
    {
        Player player;

        // UUID
        try
        {
            final UUID uuid = UUID.fromString(arg);
            player = Bukkit.getPlayer(uuid);
        }
        // Username
        catch (IllegalArgumentException ex)
        {
            player = Bukkit.getPlayer(arg);
        }

        // Nickname
        if (player == null)
        {
            player = Bukkit.getOnlinePlayers().stream()
                    .filter(candidate -> PlayerVisibilityPolicy.canExpose(sender, candidate, false))
                    .filter(p -> AdventureUtil.componentToPlainText(p.displayName()).toLowerCase().contains(arg.toLowerCase()))
                    .findAny()
                    .orElse(null);
        }

        if (player == null || !PlayerVisibilityPolicy.canExpose(sender, player, false))
            throw new ArgumentResolutionException(FCommand.PLAYER_NOT_FOUND);

        return player;
    }

    @Override
    public String name()
    {
        return "Player";
    }

    @Override
    public List<String> suggestions()
    {
        return suggestions(Bukkit.getConsoleSender());
    }

    @Override
    public List<String> suggestions(final CommandSender sender)
    {
        return Bukkit.getOnlinePlayers()
                .stream()
                .filter(player -> PlayerVisibilityPolicy.canExpose(sender, player, false))
                .map(Player::getName)
                .sorted()
                .toList();
    }

    @Override
    public Player resolve(String arg, String strategy)
    {
        return resolveDefault(Bukkit.getConsoleSender(), arg);
    }

    @Override
    public Player resolve(final CommandSender sender, final String arg, final String strategy)
    {
        return resolveDefault(sender, arg);
    }
}

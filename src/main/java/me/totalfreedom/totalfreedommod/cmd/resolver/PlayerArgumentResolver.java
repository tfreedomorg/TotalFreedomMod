package me.totalfreedom.totalfreedommod.cmd.resolver;

import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import me.totalfreedom.totalfreedommod.cmd.FCommand;
import me.totalfreedom.totalfreedommod.util.AdventureUtil;

public class PlayerArgumentResolver implements AbstractArgumentResolver<Player>
{
    private Player resolveDefault(String arg)
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
                    .filter(p -> AdventureUtil.componentToPlainText(p.displayName()).toLowerCase().contains(arg.toLowerCase()))
                    .findAny()
                    .orElse(null);
        }

        if (player == null)
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
        return Bukkit.getOnlinePlayers().stream()
                     .map(Player::getName)
                     .sorted()
                     .toList();
    }

    @Override
    public Player resolve(String arg, String strategy)
    {
        return resolveDefault(arg);
    }
    
}

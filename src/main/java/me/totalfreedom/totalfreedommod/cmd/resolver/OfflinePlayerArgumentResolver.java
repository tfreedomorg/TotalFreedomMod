package me.totalfreedom.totalfreedommod.cmd.resolver;

import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import me.totalfreedom.totalfreedommod.cmd.FCommand;

public class OfflinePlayerArgumentResolver implements AbstractArgumentResolver<OfflinePlayer>
{
    @Override
    public String name()
    {
        return "OfflinePlayer";
    }

    /**
     * Online players only. {@link Bukkit#getOfflinePlayers()} walks every profile the server has
     * ever seen, which on a public server is far too large to serve on each keystroke.
     */
    @Override
    public List<String> suggestions()
    {
        return Bukkit.getOnlinePlayers().stream()
                     .map(Player::getName)
                     .sorted()
                     .toList();
    }

    @Override
    public OfflinePlayer resolve(String arg, String strategy)
    {
        OfflinePlayer offlinePlayer;
        // UUID
        try
        {
            final UUID uuid = UUID.fromString(arg);
            offlinePlayer = Bukkit.getOfflinePlayer(uuid);
        }
        // Username
        catch (IllegalArgumentException ex)
        {
            offlinePlayer = Bukkit.getOfflinePlayer(arg);
        }

        if (!offlinePlayer.isOnline() && !offlinePlayer.hasPlayedBefore() && strategy.equalsIgnoreCase("hideUnknownPlayers"))
        {
            throw new ArgumentResolutionException(FCommand.PLAYER_NOT_FOUND);
        }

        return offlinePlayer;
    }
}

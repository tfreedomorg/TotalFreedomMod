package me.totalfreedom.totalfreedommod.cmd.resolver;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class PlayerListArgumentResolver implements AbstractArgumentResolver<List<Player>>
{
    private List<Player> resolveDefault(String arg)
    {
        final String[] candidates = arg.split(",");
        final List<Player> results = new ArrayList<>();

        for (String candidate : candidates)
        {
            candidate = candidate.trim();

            Player player;

            // UUID
            try
            {
                final UUID uuid = UUID.fromString(candidate);
                player = Bukkit.getPlayer(uuid);
            }
            // Username
            catch (IllegalArgumentException ex)
            {
                player = Bukkit.getPlayer(candidate);
            }

            if (player == null)
                throw new ArgumentResolutionException("Player not found: " + candidate);

            results.add(player);
        }

        return results;
    }

    @Override
    public String name()
    {
        return "PlayerList";
    }

    /**
     * Only whole names: the argument is comma-separated, and Brigadier suggestions replace the
     * token rather than append to it, so partial-list completion would clobber earlier entries.
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
    public List<Player> resolve(String arg, String strategy)
    {
        return resolveDefault(arg);
    }
    
}

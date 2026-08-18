package me.totalfreedom.totalfreedommod.cmd.resolver;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PlayerListArgumentResolver implements AbstractArgumentResolver<List<Player>>
{
    private List<Player> resolveDefault(final CommandSender sender, final String arg)
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

            if (player == null || !PlayerVisibilityPolicy.canExpose(sender, player, false))
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
    public List<Player> resolve(String arg, String strategy)
    {
        return resolveDefault(Bukkit.getConsoleSender(), arg);
    }

    @Override
    public List<Player> resolve(final CommandSender sender, final String arg, final String strategy)
    {
        return resolveDefault(sender, arg);
    }
}

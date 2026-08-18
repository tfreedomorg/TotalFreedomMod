package me.totalfreedom.totalfreedommod.cmd.resolver;

import me.totalfreedom.totalfreedommod.PluginProvider;
import me.totalfreedom.totalfreedommod.cmd.FCommand;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

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
    public OfflinePlayer resolve(String arg, String strategy)
    {
        return resolve(Bukkit.getConsoleSender(), arg, strategy);
    }

    @Override
    public OfflinePlayer resolve(final CommandSender sender, final String arg, final String strategy)
    {
        OfflinePlayer resolvedPlayer;
        // UUID
        try
        {
            final UUID uuid = UUID.fromString(arg);
            final Player onlinePlayer = Bukkit.getPlayer(uuid);
            resolvedPlayer = onlinePlayer != null ? onlinePlayer : Bukkit.getOfflinePlayer(uuid);
        }
        // Username
        catch (IllegalArgumentException ex)
        {
            final Player onlinePlayer = Bukkit.getPlayerExact(arg);
            OfflinePlayer cachedPlayer = onlinePlayer != null
                    ? onlinePlayer
                    : Bukkit.getOfflinePlayerIfCached(arg);
            if (cachedPlayer == null && !hasStrategy(strategy, "cachedOnly"))
                cachedPlayer = Bukkit.getOfflinePlayer(arg);
            if (cachedPlayer == null)
                throw new ArgumentResolutionException(FCommand.PLAYER_NOT_FOUND);
            resolvedPlayer = cachedPlayer;
        }

        final OfflinePlayer offlinePlayer = resolvedPlayer;

        final boolean allowBlockedHidden = hasStrategy(strategy, "allowBlockedHidden")
                && sender instanceof Player viewer
                && PluginProvider.get().pbl.hasBlocked(viewer.getUniqueId(), offlinePlayer.getUniqueId());
        final Player onlinePlayer = offlinePlayer.getPlayer();
        if (onlinePlayer != null && !PlayerVisibilityPolicy.canExpose(sender, onlinePlayer, allowBlockedHidden))
            throw new ArgumentResolutionException(FCommand.PLAYER_NOT_FOUND);

        if (!offlinePlayer.isOnline()
                && !offlinePlayer.hasPlayedBefore()
                && hasStrategy(strategy, "hideUnknownPlayers")
                && !allowBlockedHidden)
        {
            throw new ArgumentResolutionException(FCommand.PLAYER_NOT_FOUND);
        }

        return offlinePlayer;
    }

    private boolean hasStrategy(final String strategy, final String expected)
    {
        return List.of(strategy.toLowerCase(Locale.ROOT).split("[,;+]"))
                .stream()
                .map(String::trim)
                .anyMatch(expected::equalsIgnoreCase);
    }
}

package me.totalfreedom.totalfreedommod.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import me.totalfreedom.totalfreedommod.PluginProvider;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.rank.CustomRank;
import me.totalfreedom.totalfreedommod.rank.RankRole;

/**
 * Plaintext online player list shared by automated console contexts.
 */
public final class PlayerListUtil
{
    private static final String CANNOT_RETRIEVE_PLAYERS_MESSAGE = "Unable to retrieve list of players at this time.";

    private PlayerListUtil()
    {
    }

    public static String buildPlainList()
    {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers())
        {
            names.add(player.getName());
        }
        int max = Bukkit.getMaxPlayers();
        return "There are " + names.size() + "/" + max + " players online:\n" + String.join(", ", names);
    }

    public static String buildRankList()
    {
        final TotalFreedomMod plugin = PluginProvider.get();
        // If this somehow fails, just send back a list with no additional rank context
        if (plugin == null)
            return buildPlainList();

        final var players = Bukkit.getOnlinePlayers();
        if (players == null)
            return CANNOT_RETRIEVE_PLAYERS_MESSAGE;

        // Build a map from ranks to players that have that rank, with OP as a default
        final Map<CustomRank, List<Player>> playerRanks = new HashMap<>();
        final var defaultRank = plugin.rm.getRegistry().byRole(RankRole.DEFAULT_OP).orElse(null);
        // Without an op rank in the registry there is no bucket to default anyone into, so fall
        // back to the plain listing rather than dropping unranked players from the output.
        if (defaultRank == null)
            return buildPlainList();

        playerRanks.put(defaultRank, new ArrayList<>());
        for (final var player : players)
        {
            final var admin = plugin.al.getAdmin(player);
            // If they're an admin, we can pull their rank
            if (admin != null)
            {
                final var rank = plugin.rm.getCustomRank(admin.getRankId());
                if (rank == null)
                {
                    // Give them OP if somehow they don't have a rank
                    playerRanks.get(defaultRank).add(player);
                    continue;
                }
                // Add the player to their corresponding list, or create the list if it doesn't exist yet
                if (!playerRanks.containsKey(rank))
                    playerRanks.put(rank, new ArrayList<>());
                playerRanks.get(rank).add(player);
                continue;
            }
            // Otherwise default to OP
            playerRanks.get(defaultRank).add(player);
        }

        if (playerRanks.get(defaultRank).isEmpty())
            playerRanks.remove(defaultRank);

        final int max = Bukkit.getMaxPlayers();

        final List<String> playerListings = plugin.rm.getCustomRanks()
            .values()
            .stream()
            .sorted((r1, r2) -> Integer.compare(r2.getLevel(), r1.getLevel())) // Sort in reverse level order, highest ranks listed first
            .filter(playerRanks::containsKey)
            .map(rank -> {
                // Get all players with that rank
                final List<Player> rankedPlayers = playerRanks.get(rank);
                final List<String> rankedPlayerNames = rankedPlayers
                    .stream()
                    .map(Player::getName)
                    .collect(Collectors.toCollection(ArrayList::new));
                // Make a nice message out of it
                return String.format("%ss (%s): %s",
                    rank.getName(),
                    rankedPlayers.size(),
                    String.join(", ",rankedPlayerNames));
            }).collect(Collectors.toCollection(ArrayList::new));

        return String.format("There are %s/%s players online.\n\n%s",
            players.size(),
            max,
            String.join("\n", playerListings));
    }
}

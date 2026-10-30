package me.totalfreedom.totalfreedommod.util;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import me.totalfreedom.totalfreedommod.PluginProvider;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.rank.CustomRank;
import me.totalfreedom.totalfreedommod.rank.RankRole;
import me.totalfreedom.totalfreedommod.title.Title;

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
        final TotalFreedomMod              plugin  = PluginProvider.get();
        final Collection<? extends Player> players = Bukkit.getOnlinePlayers();

        // Build a map from ranks to players that have that rank, with OP as a default
        final Map<CustomRank, List<Player>> playerRanks = new HashMap<>();
        final var defaultRank = plugin.rm.getRegistry().byRole(RankRole.DEFAULT_OP).orElse(null);

        if (defaultRank == null)
            return buildPlainList();

        final Map<Title, List<Player>> playerTitles = new HashMap<>();

        playerRanks.put(defaultRank, new ArrayList<>());
        for (final var player : players)
        {
            if (plugin.rm.getDisplay(player) instanceof Title title)
            {
                playerTitles.computeIfAbsent(title, ignored -> new ArrayList<>()).add(player);
                continue;
            }

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

        final Comparator<Player> byDisplayOrder = plugin.rm.byDisplayOrder();

        final List<String> titleListings = playerTitles.keySet()
            .stream()
            .sorted()
            .map(title -> formatListing(title.getName(), playerTitles.get(title), byDisplayOrder))
            .toList();

        final List<String> rankListings = plugin.rm.getCustomRanks()
            .values()
            .stream()
            .sorted((r1, r2) -> Integer.compare(r2.getLevel(), r1.getLevel())) // Sort in reverse level order, highest ranks listed first
            .filter(playerRanks::containsKey)
            .map(rank -> formatListing(rank.getName(), playerRanks.get(rank), byDisplayOrder))
            .toList();

        final List<String> playerListings = Stream.concat(titleListings.stream(), rankListings.stream())
            .toList();

        return String.format("There are %s/%s players online.\n\n%s",
            players.size(),
            max,
            String.join("\n", playerListings));
    }

    /**
     * The players matching {@code membership} as a single group, for the filtered Discord list.
     */
    public static String buildFilteredList(final String groupName, final Predicate<Player> membership)
    {
        final TotalFreedomMod plugin = PluginProvider.get();

        final List<Player> members = Bukkit.getOnlinePlayers()
                                           .stream()
                                           .map(Player.class::cast)
                                           .filter(membership)
                                           .toList();

        return String.format("There are %s/%s players online.\n\n%s",
            Bukkit.getOnlinePlayers().size(),
            Bukkit.getMaxPlayers(),
            formatListing(groupName, members, plugin.rm.byDisplayOrder()));
    }

    public static boolean isFamous(final Player player)
    {
        return ConfigEntry.FAMOUS_PLAYERS.getList().contains(player.getName().toLowerCase());
    }

    private static String formatListing(final String groupName, final List<Player> members, final Comparator<Player> order)
    {
        final List<String> names = members.stream()
                                          .sorted(order)
                                          .map(Player::getName)
                                          .toList();

        return String.format("%ss (%s): %s", groupName, names.size(), names.isEmpty() ? "None" : String.join(", ", names));
    }
}

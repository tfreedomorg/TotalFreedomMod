package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.banning.Ban;
import me.totalfreedom.totalfreedommod.cmd.internal.FuzzyMatch;
import me.totalfreedom.totalfreedommod.util.AdventureUtil;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;

/**
 * Shared tab-completion sources for commands that take a player name as a plain {@code String}.
 * <p>
 * Those arguments exist so offline and previously-known names can be targeted, which is exactly why
 * they get no suggestions from the command framework.
 * <p>
 * Every source runs the candidates through {@link FuzzyMatch} so completion behaves the same as the
 * framework's built-in suggesters.
 */
final class NameCandidates
{

    private static final int MIN_TYPED_PREFIX = 3;

    private NameCandidates()
    {
    }

    static List<String> onlineTyped(Server server, String partial)
    {
        if (partial.length() < MIN_TYPED_PREFIX)
        {
            return List.of();
        }

        final String prefix = partial.toLowerCase();
        return server.getOnlinePlayers()
                     .stream()
                     .map(Player::getName)
                     .filter(name -> name.toLowerCase().startsWith(prefix))
                     .sorted()
                     .toList();
    }

    static List<String> online(Server server, String partial)
    {
        return FuzzyMatch.filter(
                server.getOnlinePlayers()
                      .stream()
                      .map(Player::getName)
                      .sorted()
                      .toList(),
                partial);
    }

    /**
     * Plain-text nicknames of the online players who have one, for arguments that are matched
     * against nicknames rather than usernames.
     */
    static List<String> onlineNicknames(TotalFreedomMod plugin, Server server, String partial)
    {
        return FuzzyMatch.filter(
                server.getOnlinePlayers()
                      .stream()
                      .map(player -> plugin.pl.getData(player).getNickname())
                      .filter(nickname -> nickname != null)
                      .map(nickname -> AdventureUtil.componentToPlainText(nickname).trim())
                      .filter(nickname -> !nickname.isEmpty())
                      .sorted()
                      .toList(),
                partial);
    }

    static List<String> banned(TotalFreedomMod plugin, String partial)
    {
        return FuzzyMatch.filter(
                plugin.bm.getUsernameBans()
                         .stream()
                         .map(Ban::getUsername)
                         .filter(name -> name != null)
                         .distinct()
                         .sorted()
                         .toList(),
                partial);
    }

    static List<String> permbanned(TotalFreedomMod plugin, String partial)
    {
        return FuzzyMatch.filter(
                plugin.pm.getPermbannedNames()
                         .stream()
                         .sorted()
                         .toList(),
                partial);
    }

    static List<String> whitelisted(String partial)
    {
        return FuzzyMatch.filter(
                Bukkit.getWhitelistedPlayers()
                      .stream()
                      .map(OfflinePlayer::getName)
                      .filter(name -> name != null)
                      .sorted()
                      .toList(),
                partial);
    }
}

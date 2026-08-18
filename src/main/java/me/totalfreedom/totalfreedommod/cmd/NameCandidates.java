package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.banning.Ban;
import me.totalfreedom.totalfreedommod.cmd.internal.FuzzyMatch;
import me.totalfreedom.totalfreedommod.cmd.resolver.PlayerVisibilityPolicy;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
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

    private NameCandidates()
    {
    }

    static List<String> online(
            final Server server,
            final CommandSender sender,
            final String partial)
    {
        return FuzzyMatch.filter(
                server.getOnlinePlayers()
                      .stream()
                      .filter(player -> PlayerVisibilityPolicy.canExpose(sender, player, false))
                      .map(Player::getName)
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

    static List<String> whitelisted(final CommandSender sender, final String partial)
    {
        return FuzzyMatch.filter(
                Bukkit.getWhitelistedPlayers()
                      .stream()
                      .filter(player -> PlayerVisibilityPolicy.canExpose(sender, player, false))
                      .map(OfflinePlayer::getName)
                      .filter(name -> name != null)
                      .sorted()
                      .toList(),
                partial);
    }
}

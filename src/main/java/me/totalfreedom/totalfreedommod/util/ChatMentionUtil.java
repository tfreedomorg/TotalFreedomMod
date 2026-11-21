package me.totalfreedom.totalfreedommod.util;

import java.util.*;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;

public final class ChatMentionUtil
{

    private ChatMentionUtil()
    {
    }

    public static Component highlight(Component message, boolean allowEveryone)
    {
        if (message == null)
        {
            return Component.empty();
        }

        MentionContext context = createContext();
        if (context == null)
        {
            return message;
        }

        return message.replaceText(builder -> builder
                .match(context.pattern)
                .replacement((result, text) ->
                {
                    String at = result.group(1);
                    String mentionedName = result.group(2);

                    if (mentionedName.equalsIgnoreCase("everyone"))
                    {
                        return allowEveryone && "@".equals(at)
                                ? text.color(NamedTextColor.YELLOW)
                                : text;
                    }

                    return context.playersByName.containsKey(mentionedName.toLowerCase(Locale.ROOT))
                            ? text.color(NamedTextColor.YELLOW)
                            : text;
                }));
    }

    public static void pingMentions(TotalFreedomMod plugin, Component message, boolean allowEveryone)
    {
        if (message == null)
        {
            return;
        }

        MentionContext context = createContext();
        if (context == null)
        {
            return;
        }

        Set<UUID> pingedPlayers = new HashSet<>();
        message.replaceText(builder -> builder
                .match(context.pattern)
                .replacement((result, text) ->
                {
                    String at = result.group(1);
                    String mentionedName = result.group(2);

                    if (mentionedName.equalsIgnoreCase("everyone"))
                    {
                        if (allowEveryone && "@".equals(at))
                        {
                            for (Player player : context.onlinePlayers)
                            {
                                pingedPlayers.add(player.getUniqueId());
                            }
                        }
                        return text;
                    }

                    Player mentionedPlayer = context.playersByName.get(mentionedName.toLowerCase(Locale.ROOT));
                    if (mentionedPlayer != null)
                    {
                        pingedPlayers.add(mentionedPlayer.getUniqueId());
                    }
                    return text;
                }));

        playPings(plugin, pingedPlayers);
    }

    public static Component highlightAndPing(TotalFreedomMod plugin, Component message, boolean allowEveryone)
    {
        Component highlighted = highlight(message, allowEveryone);
        pingMentions(plugin, message, allowEveryone);
        return highlighted;
    }

    private static MentionContext createContext()
    {
        List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (onlinePlayers.isEmpty())
        {
            return null;
        }

        Map<String, Player> playersByName = new HashMap<>();
        List<String> playerNames = new ArrayList<>();
        for (Player player : onlinePlayers)
        {
            playersByName.put(player.getName().toLowerCase(Locale.ROOT), player);
            playerNames.add(player.getName());
        }
        playerNames.sort(Comparator.comparingInt(String::length).reversed());

        StringBuilder names = new StringBuilder();
        for (String playerName : playerNames)
        {
            if (names.length() > 0)
            {
                names.append('|');
            }
            names.append(Pattern.quote(playerName));
        }

        Pattern pattern = Pattern.compile(
                "(?i)(?<![A-Za-z0-9_])(@?)(everyone|" + names + ")(?![A-Za-z0-9_])");
        return new MentionContext(onlinePlayers, playersByName, pattern);
    }

    private static void playPings(TotalFreedomMod plugin, Set<UUID> pingedPlayers)
    {
        if (pingedPlayers.isEmpty())
        {
            return;
        }

        Runnable pingTask = () ->
        {
            for (UUID uuid : pingedPlayers)
            {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline())
                {
                    player.playSound(
                            player.getLocation(),
                            Sound.BLOCK_NOTE_BLOCK_PLING,
                            SoundCategory.MASTER,
                            1337F,
                            0.9F);
                }
            }
        };

        if (Bukkit.isPrimaryThread())
        {
            pingTask.run();
        }
        else
        {
            Bukkit.getScheduler().runTask(plugin, FTask.guard("ChatMentionUtil/ping", pingTask));
        }
    }

    private record MentionContext(List<Player> onlinePlayers, Map<String, Player> playersByName, Pattern pattern)
    {
    }
}

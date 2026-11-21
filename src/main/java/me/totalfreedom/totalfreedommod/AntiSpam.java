package me.totalfreedom.totalfreedommod;

import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.player.ChatSpamData;
import me.totalfreedom.totalfreedommod.player.FPlayer;
import me.totalfreedom.totalfreedommod.util.AdventureUtil;
import me.totalfreedom.totalfreedommod.util.FSync;
import me.totalfreedom.totalfreedommod.util.FUtil;

public class AntiSpam extends FreedomService
{

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)\\b(?:https?://|www\\.)[-a-z0-9+&@#/%?=~_|!:,.;]*[-a-z0-9+&@#/%=~_|]");

    private static final long WARN_INTERVAL_MS = 2500L;

    public AntiSpam(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
    }

    @Override
    protected void onStop()
    {
    }

    private static boolean enabled()
    {
        return ConfigEntry.ANTISPAM_ENABLED.getBoolean(true);
    }

    private static int limit()
    {
        return ConfigEntry.ANTISPAM_LIMIT.getInteger(8);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onAsyncPlayerChat(AsyncChatEvent event)
    {
        if (!enabled())
        {
            return;
        }
        final Player player = event.getPlayer();
        final String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        final FPlayer playerdata = plugin.pl.getPlayerSync(player);

        final int count = playerdata.incrementAndGetMsgCount();
        if (count >= limit())
        {
            event.setCancelled(true);
            if (count == limit())
            {
                FSync.bcastMsg(player.getName() + " was automatically kicked for spamming chat.", NamedTextColor.RED);
                FSync.autoEject(player, "Kicked for spamming chat.");
            }
            return;
        }

        if (blockIfChatSpam(player, playerdata, message))
        {
            event.setCancelled(true);
            return;
        }

        playerdata.setLastMessage(message);
    }

    private boolean blockIfChatSpam(Player player, FPlayer playerdata, String message)
    {
        if (plugin.al.isAdmin(player))
        {
            return false;
        }

        final long now = System.currentTimeMillis();
        final long cooldownMs = ConfigEntry.ANTISPAM_CHAT_COOLDOWN.getInteger(0);
        final boolean repeatEnabled = ConfigEntry.VAULT_CHAT_PREVENT_SPAM.getBoolean(true);
        final long repeatWindowMs = repeatEnabled ? ConfigEntry.ANTISPAM_REPEAT_WINDOW.getInteger(30000) : 0;
        final int historySize = ConfigEntry.ANTISPAM_REPEAT_HISTORY.getInteger(8);

        final ChatSpamData spamData = playerdata.getChatSpamData();
        final ChatSpamData.Verdict verdict =
                spamData.evaluate(repeatSignature(message), now, cooldownMs, repeatWindowMs, historySize);
        if (verdict == ChatSpamData.Verdict.OK)
        {
            return false;
        }

        if (spamData.shouldWarn(now, WARN_INTERVAL_MS))
        {
            FSync.playerMsg(player, verdict == ChatSpamData.Verdict.COOLDOWN
                    ? "Please wait before sending another message."
                    : "Please do not repeat messages or spam links.");
        }
        return true;
    }

    static boolean isBroadcastCommand(String rawCommand)
    {
        final String label = commandLabel(rawCommand);
        return label.equals("bc") || label.equals("broadcast") || label.equals("bcast");
    }

    static String broadcastBody(String rawCommand)
    {
        if (rawCommand == null || rawCommand.isEmpty())
        {
            return "";
        }
        String s = rawCommand.charAt(0) == '/' ? rawCommand.substring(1) : rawCommand;
        s = s.trim();
        final int space = s.indexOf(' ');
        if (space < 0)
        {
            return "";
        }
        return s.substring(space + 1).trim();
    }

    private static String commandLabel(String rawCommand)
    {
        if (rawCommand == null || rawCommand.isEmpty())
        {
            return "";
        }
        String s = rawCommand.charAt(0) == '/' ? rawCommand.substring(1) : rawCommand;
        s = s.trim();
        final int space = s.indexOf(' ');
        String label = (space < 0 ? s : s.substring(0, space)).toLowerCase(Locale.ROOT);
        final int colon = label.lastIndexOf(':');
        if (colon >= 0)
        {
            label = label.substring(colon + 1);
        }
        return label;
    }

    static String repeatSignature(String rawMessage)
    {
        if (rawMessage == null)
        {
            return "";
        }
        String s = AdventureUtil.stripColor(rawMessage).toLowerCase(Locale.ROOT);

        final Matcher matcher = URL_PATTERN.matcher(s);
        final TreeSet<String> urls = new TreeSet<>();
        while (matcher.find())
        {
            urls.add(canonicalUrl(matcher.group()));
        }
        if (!urls.isEmpty())
        {
            return "url:" + String.join(",", urls);
        }

        s = s.replaceAll("\\s+", " ").trim();
        final int lastSpace = s.lastIndexOf(' ');
        if (lastSpace > 0 && s.length() - lastSpace - 1 <= 3)
        {
            s = s.substring(0, lastSpace).trim();
        }
        return s;
    }

    private static String canonicalUrl(String rawUrl)
    {
        String u = rawUrl.toLowerCase(Locale.ROOT).trim();
        if (u.startsWith("http://"))
        {
            u = u.substring(7);
        }
        else if (u.startsWith("https://"))
        {
            u = u.substring(8);
        }
        if (u.startsWith("www."))
        {
            u = u.substring(4);
        }
        return u.replaceAll("[.,;:!?)]+$", "");
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event)
    {
        if (!enabled())
        {
            return;
        }
        final Player player = event.getPlayer();
        final FPlayer fPlayer = plugin.pl.getPlayer(player);

        if (fPlayer.allCommandsBlocked())
        {
            FUtil.playerMsg(player, "Your commands have been blocked by an admin.", NamedTextColor.RED);
            event.setCancelled(true);
            return;
        }

        final int count = fPlayer.incrementAndGetMsgCount();
        if (count >= limit())
        {
            event.setCancelled(true);
            if (count == limit())
            {
                FUtil.bcastMsg(player.getName() + " was automatically kicked for spamming commands.", NamedTextColor.RED);
                plugin.ae.autoEject(player, "Kicked for spamming commands.");
            }
            return;
        }

        final String commandLine = event.getMessage();
        if (isBroadcastCommand(commandLine)
                && blockIfChatSpam(player, fPlayer, broadcastBody(commandLine)))
        {
            event.setCancelled(true);
        }
    }

}

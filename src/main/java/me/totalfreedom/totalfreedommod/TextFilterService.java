package me.totalfreedom.totalfreedommod;

import me.totalfreedom.api.FreedomAPI;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.meta.BookMeta;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.banning.Ban;
import me.totalfreedom.totalfreedommod.cmd.MessageUtils;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;

public class TextFilterService extends FreedomService
{
    private static final Map<Character, Character> LEET = Map.ofEntries(
        Map.entry('0', 'O'), Map.entry('1', 'I'), Map.entry('3', 'E'),
        Map.entry('4', 'A'), Map.entry('5', 'S'), Map.entry('7', 'T'),
        Map.entry('!', 'I'), Map.entry('|', 'I'), Map.entry('+', 'T'));

    private List<Pattern> filters = List.of();

    public TextFilterService(FreedomAPI plugin)
    {
        super(plugin);
    }

    @Override
    public void onStart()
    {
        reloadFilters();
    }

    @Override
    public void onStop()
    {
        filters = List.of();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event)
    {
        if (isFilterDisabled())
        {
            return;
        }

        final String message = MessageUtils.toPlainText(event.message());
        if (!matchesFilter(message))
            return;

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> temporarilyBan(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event)
    {
        if (isFilterDisabled())
            return;

        if (!matchesFilter(event.getMessage()))
            return;

        event.setCancelled(true);
        temporarilyBan(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSignEdit(SignChangeEvent event)
    {
        if (isFilterDisabled())
            return;

        final StringBuilder builder = new StringBuilder();

        for (int i = 0; i < event.lines().size(); i++)
        {
            if (!builder.isEmpty())
            {
                builder.append(Component.newline());
            }
            builder.append(event.line(i));
        }

        if (!matchesFilter(builder.toString()))
            return;

        event.setCancelled(true);
        temporarilyBan(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBookEdit(PlayerEditBookEvent event)
    {
        final BookMeta meta = event.getNewBookMeta();
        final StringBuilder builder = new StringBuilder();

        if (isFilterDisabled())
            return;

        if (meta.hasTitle())
        {
            builder.append(MessageUtils.toPlainText(meta.title()));
        }

        for (Component page : meta.pages())
        {
            if (!builder.isEmpty())
                builder.append(Component.newline());
            builder.append(MessageUtils.toPlainText(page));
        }

        if (!matchesFilter(builder.toString()))
            return;

        event.setCancelled(true);
        temporarilyBan(event.getPlayer());
    }

    private void reloadFilters()
    {
        final List<Pattern> compiledFilters = new ArrayList<>();
        for (String filter : ConfigEntry.TEXT_FILTER_REGEX_FILTERS.getStringList())
        {
            if (filter == null || filter.isBlank())
            {
                continue;
            }

            try
            {
                compiledFilters.add(Pattern.compile(filter, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            }
            catch (PatternSyntaxException ex)
            {
                FLog.warn("Skipping invalid text filter regex: " + ex.getDescription());
            }
        }

        filters = List.copyOf(compiledFilters);
        FLog.info("Loaded " + filters.size() + " text filter regex pattern(s).");
    }

    private boolean isFilterDisabled()
    {
        return !ConfigEntry.TEXT_FILTER_ENABLED.getBoolean(true) || filters.isEmpty();
    }

    private boolean matchesFilter(String text)
    {
        if (text == null || text.isEmpty())
        {
            return false;
        }

        final String folded = fold(text);
        final String deleeted = deleet(folded);

        return filters.stream()
                      .anyMatch(filter -> filter.matcher(text).find()
                                        || filter.matcher(folded).find()
                                        || filter.matcher(deleeted).find());
    }

    private static String fold(String text)
    {
        return Normalizer.normalize(text, Normalizer.Form.NFKD)
                         .replaceAll("\\p{M}+", "");
    }

    private static String deleet(String text)
    {
        final StringBuilder out = new StringBuilder(text.length());
        text.chars().forEach(c -> out.append(LEET.getOrDefault((char) c, (char) c)));

        return out.toString();
    }

    private void temporarilyBan(Player player)
    {
        if (!player.isOnline())
        {
            return;
        }

        if (plugin.bans().getByUsername(player.getName()) != null)
        {
            player.kick(tempbanKickMessage());
            return;
        }

        final Ban ban = Ban.forPlayer(player, Bukkit.getConsoleSender(), FUtil.parseDateOffset("1d"), "Use of prohibited language");
        
        ban.addIp(player.getAddress().getAddress().getHostAddress());
        plugin.players().getData(player).getIps().forEach(ban::addIp);

        plugin.bans().addBan(ban);

        MessageUtils.broadcast("<red><player> has been temporarily banned for prohibited language.",
            Placeholder.unparsed("player", player.getName()));
        FLog.warn("[TextFilter] Temporarily banned " + player.getName() + " for prohibited language.", true);

        player.kick(tempbanKickMessage());
    }

    private Component tempbanKickMessage()
    {
        final String message = """
            <red>Your username is temporarily banned from this server.
            Release procedures are available at
            </red><gold><url></gold>""";

        return MessageUtils.parse(message, MessageUtils.unparsed("url", ConfigEntry.SERVER_BAN_URL.getString()));
    }
}

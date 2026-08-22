package me.totalfreedom.totalfreedommod;

import java.util.ArrayList;
import java.util.List;
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
    private List<Pattern> filters = List.of();

    public TextFilterService(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        reloadFilters();
    }

    @Override
    protected void onStop()
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
                FLog.warning("Skipping invalid text filter regex: " + ex.getDescription());
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

        return filters.stream()
                .anyMatch(filter -> filter.matcher(text).find());
    }

    private void temporarilyBan(Player player)
    {
        if (!player.isOnline())
        {
            return;
        }

        if (plugin.bm.getByUsername(player.getName()) != null)
        {
            player.kick(tempbanKickMessage());
            return;
        }

        final Ban ban = Ban.forPlayer(player, Bukkit.getConsoleSender(), FUtil.parseDateOffset("1d"), "Use of prohibited language");
        
        ban.addIp(player.getAddress().getAddress().getHostAddress());
        plugin.pl.getData(player).getIps().forEach(ban::addIp);

        plugin.bm.addBan(ban);

        MessageUtils.broadcast("<red><player> has been temporarily banned for prohibited language.",
            Placeholder.unparsed("player", player.getName()));
        FLog.warning("[TextFilter] Temporarily banned " + player.getName() + " for prohibited language.", true);

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

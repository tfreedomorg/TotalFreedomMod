package me.totalfreedom.totalfreedommod.tablist;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

import me.totalfreedom.totalfreedommod.ChatManager;
import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.util.AdventureUtil;
import me.totalfreedom.totalfreedommod.util.FTask;
import me.totalfreedom.totalfreedommod.util.FUtil;

public class TabList extends FreedomService
{
    private BukkitTask updateTask;
    private BukkitTask recolorTask;
    private boolean enabled;
    private boolean colorful;
    private Component colorfulHeader = Component.empty();
    private Component colorfulFooter = Component.empty();

    public TabList(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        enabled = ConfigEntry.TABLIST_ENABLED.getBoolean();

        if (!enabled)
        {
            return;
        }

        colorful = ConfigEntry.TABLIST_COLORFUL.getBoolean();

        if (colorful)
        {
            recolorHeaderFooter();
            long colorInterval = Math.max(1, ConfigEntry.TABLIST_CHANGE_COLORS.getInteger()) * 20L;
            recolorTask = plugin.getServer().getScheduler()
                    .runTaskTimer(plugin, FTask.guard("TabList/pushRecolor", this::pushRecolor), colorInterval, colorInterval);
        }

        long interval = ConfigEntry.TABLIST_UPDATE_INTERVAL.getInteger();
        updateTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, FTask.guard("TabList/updateAll", this::updateAll), interval, interval);

        server.getScheduler().runTask(plugin, FTask.guard("TabList/updateAll", this::updateAll));
    }

    @Override
    protected void onStop()
    {
        if (updateTask != null)
        {
            FUtil.cancel(updateTask);
            updateTask = null;
        }

        if (recolorTask != null)
        {
            FUtil.cancel(recolorTask);
            recolorTask = null;
        }

        for (Player player : server.getOnlinePlayers())
        {
            player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        if (!enabled)
        {
            return;
        }
        final Player player = event.getPlayer();
        final CycleContext ctx = newCycleContext();
        // Delay 1 tick so all other MONITOR-priority join handlers finish first.
        plugin.getServer().getScheduler().runTaskLater(plugin,
                FTask.guard("TabList/applyOnJoin", () -> applyToPlayer(player, ctx)), 1L);
    }

    private void updateAll()
    {
        final CycleContext ctx = newCycleContext();

        for (Player player : server.getOnlinePlayers())
        {
            applyToPlayer(player, ctx);
        }
    }

    private CycleContext newCycleContext()
    {
        final CycleContext ctx = new CycleContext();

        if (colorful)
        {
            ctx.header = colorfulHeader;
            ctx.footer = colorfulFooter;
        }
        else
        {
            ctx.header = AdventureUtil.legacyToComponent(ConfigEntry.TABLIST_HEADER.getString());
            ctx.footer = AdventureUtil.legacyToComponent(ConfigEntry.TABLIST_FOOTER.getString());
        }
        ctx.playerComponentTemplate = ConfigEntry.TABLIST_PLAYER_COMPONENT.getString();
        ctx.afkTag = ConfigEntry.TABLIST_AFK_TAG.getString();
        ctx.essentialsEnabled = plugin.esb.isEssentialsEnabled();
        return ctx;
    }

    private void applyToPlayer(Player player, CycleContext ctx)
    {
        if (!player.isOnline())
        {
            return;
        }
        player.sendPlayerListHeaderAndFooter(ctx.header, ctx.footer);
        player.playerListName(buildPlayerListName(player, ctx));
    }

    private Component buildPlayerListName(Player player, CycleContext ctx)
    {
        // ${prefix} — rank tag / custom tag, same logic as chat prefix
        String prefix = plugin.cm.buildPlayerPrefix(player, ChatManager.PrefixFormat.AMPERSAND);

        // ${afk_tag} — AFK indicator (empty if not AFK or Essentials unavailable)
        String afkTag = "";
        if (ctx.essentialsEnabled && plugin.esb.isAfk(player.getName()))
        {
            afkTag = ctx.afkTag;
        }

        // ${display_name} — nickname (with optional prepended indicator) or plain real name
        String displayName = resolveDisplayName(player, ctx);

        // ${name} — plain real username, no color applied
        String name = player.getName();

        String resolved = ctx.playerComponentTemplate
                .replace("${prefix}", prefix)
                .replace("${afk_tag}", afkTag)
                .replace("${display_name}", displayName)
                .replace("${name}", name);

        return AdventureUtil.legacyToComponent(resolved);
    }

    // Returns the player's display name for the tab list:
    private String resolveDisplayName(Player player, CycleContext ctx)
    {
        final PlayerData data = plugin.pl.getData(player);
        if (!data.hasCustomNickname())
            return "&r" + player.getName();
        return AdventureUtil.componentToLegacy(data.getDisplayedNickname()).replace('§', '&');
    }

    private void pushRecolor()
    {
        recolorHeaderFooter();
        for (Player player : server.getOnlinePlayers())
        {
            player.sendPlayerListHeaderAndFooter(colorfulHeader, colorfulFooter);
        }
    }

    private void recolorHeaderFooter()
    {
        colorfulHeader = colorizeWords(ConfigEntry.TABLIST_HEADER.getString());
        colorfulFooter = colorizeWords(ConfigEntry.TABLIST_FOOTER.getString());
    }

    private Component colorizeWords(String input)
    {
        if (input == null || input.isEmpty())
        {
            return Component.empty();
        }

        final TextComponent.Builder builder = Component.text();
        final String[] lines = input.split("\n", -1);

        for (int i = 0; i < lines.length; i++)
        {
            if (i > 0)
            {
                builder.appendNewline();
            }

            final String[] words = lines[i].split(" ", -1);

            for (int j = 0; j < words.length; j++)
            {
                if (j > 0)
                {
                    builder.append(Component.space());
                }

                final String word = words[j];

                if (startsWithColor(word))
                {
                    builder.append(AdventureUtil.legacyToComponent(word));
                }
                else
                {
                    builder.append(Component.text(word, FUtil.randomChatColor()));
                }
            }
        }

        return builder.build();
    }

    private boolean startsWithColor(String word)
    {
        if (word.length() < 2 || word.charAt(0) != '&')
        {
            return false;
        }
        final char code = Character.toLowerCase(word.charAt(1));
        return (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f') || code == 'r';
    }

    private static final class CycleContext
    {
        Component header;
        Component footer;
        String playerComponentTemplate;
        String afkTag;
        boolean essentialsEnabled;
    }
}

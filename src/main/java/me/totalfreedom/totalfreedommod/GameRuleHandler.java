package me.totalfreedom.totalfreedommod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.papermc.paper.event.world.WorldGameRuleChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FTask;
import me.totalfreedom.totalfreedommod.util.FUtil;

public class GameRuleHandler extends FreedomService
{
    private final Map<GameRule<?>, Object> defaultGameRuleValues = new HashMap<>();
    private final List<Key> maliciousGameRules = new ArrayList<>();
    private final Map<GameRule<?>, List<Integer>> gameRuleCaps = new HashMap<>();
    private final Map<String, RateWindow> changeWindows = new ConcurrentHashMap<>();
    //
    private BukkitTask commitTask = null;

    public GameRuleHandler(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        // Clear the gamerule defaults and known malicious entries from memory
        defaultGameRuleValues.clear();
        maliciousGameRules.clear();
        gameRuleCaps.clear();
        changeWindows.clear();

        // Don't do anything if we're no longer planning on managing gamerules
        if (!ConfigEntry.GAMERULES_ENABLED.getBoolean())
        {
            return;
        }

        // Load defaults from configuration
        final ConfigurationSection defaults = ConfigEntry.GAMERULES_DEFAULTS.getSection();
        for (final String key : defaults.getKeys(false))
        {
            // Ignore invalid namespaces
            if (!Key.parseable(key))
            {
                continue;
            }

            // IntelliJ, who cares if the string is unsubstituted, who asked for your opinion?
            final GameRule<?> rule = Registry.GAME_RULE.get(Key.key(key));

            // Ignore gamerules that don't exist
            if (rule == null)
            {
                FLog.warning("Ignoring default gamerule " + key + " as it doesn't exist");
                continue;
            }

            final Object value = defaults.get(key);

            // IntelliJ complained about nullability even though that makes no sense given how this code is done
            if (value == null)
            {
                FLog.warning("Ignoring default gamerule " + key + " as the value is somehow null");
                continue;
            }

            // Ignore gamerules with invalid values
            if (!rule.getType().isInstance(value))
            {
                FLog.warning("Ignoring default gamerule " + key + " as it uses an invalid type - Expected " + rule.getType() + ", got " + value.getClass().getName());
                continue;
            }

            defaultGameRuleValues.put(rule, value);
        }

        // Load known malicious entries from memory
        maliciousGameRules.addAll(ConfigEntry.GAMERULES_MALICIOUS.getStringList().stream().filter(Key::parseable)
                .map(Key::key).toList());

        // Load ranges from memory
        final ConfigurationSection caps = ConfigEntry.GAMERULES_CAPS.getSection();
        for (final String key : caps.getKeys(false))
        {
            // Ignore invalid namespaces
            if (!Key.parseable(key))
            {
                continue;
            }

            // IntelliJ, who cares if the string is unsubstituted, who asked for your opinion?
            final GameRule<?> rule = Registry.GAME_RULE.get(Key.key(key));

            // Ignore gamerules that don't exist
            if (rule == null)
            {
                FLog.warning("Ignoring cap for gamerule " + key + " as it doesn't exist");
                continue;
            }

            // Ignore gamerules that aren't numbers
            if (!Integer.class.isAssignableFrom(rule.getType()) )
            {
                FLog.warning("Ignoring cap for gamerule " + key + " as said gamerule isn't a number");
                continue;
            }

            // Determine the range
            final List<Integer> range = caps.getIntegerList(key);
            if (range.size() != 2 || range.getFirst() > range.getLast())
            {
                FLog.warning("Ignoring cap for gamerule " + key + " as the range given isn't valid (must be specified like [min, max] in the configuration)");
                continue;
            }

            gameRuleCaps.put(rule, range);
        }

        // Set up periodic enforcement
        if (ConfigEntry.GAMERULES_ENFORCEMENT_DELAY.getInteger() > 0)
        {
            // Just in case...
            if (commitTask != null && !commitTask.isCancelled())
            {
                commitTask.cancel();
            }

            commitTask = server.getScheduler().runTaskTimer(plugin,
                    FTask.guard("GameRuleHandler/enforceDefaults", this::enforceGameRuleDefaults),
                    0L,
                    ConfigEntry.GAMERULES_ENFORCEMENT_DELAY.getInteger());
        }
    }

    @Override
    protected void onStop()
    {
        changeWindows.clear();

        if (commitTask != null)
        {
            commitTask.cancel();
            commitTask = null;
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        changeWindows.remove(event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onGameRuleChange(WorldGameRuleChangeEvent event)
    {
        // Ignore if we're not bothering with game rule management
        if (!ConfigEntry.GAMERULES_ENABLED.getBoolean())
        {
            return;
        }

        final CommandSender source = event.getCommandSender();
        final GameRule<?> gameRule = event.getGameRule();

        // Prevent players we don't want from screwing with our game rules
        if (source != null && !plugin.rm.hasPermission(source, "tfm.world.gamerule"))
        {
            // Cancel the event, we don't want players without this permission to be able to mess with this
            event.setCancelled(true);

            final int maxPerSecond = ConfigEntry.GAMERULES_MAX_CHANGES_PER_SECOND.getInteger(20);
            if (maxPerSecond > 0)
            {
                final int perSecond = recordChange(source.getName());
                if (perSecond > maxPerSecond)
                {
                    if (perSecond == maxPerSecond + 1)
                    {
                        FLog.warning(source.getName() + " is spamming gamerule changes; rate-limiting"
                                + " and suppressing further warnings.");

                        if (ConfigEntry.GAMERULES_KICK_FLOODERS.getBoolean(true) && source instanceof Player player)
                        {
                            FUtil.bcastMsg(player.getName() + " was automatically kicked for spamming"
                                    + " gamerule changes.", NamedTextColor.RED);
                            plugin.ae.autoEject(player, "Kicked for spamming gamerule changes.");
                        }
                    }
                    return;
                }
            }

            FLog.warning(source.getName()
                    + " just tried to change "
                    + (maliciousGameRules.contains(gameRule.getKey().key()) ? "malicious " : "")
                    + "gamerule "
                    + gameRule.getKey().asString()
                    + " to value "
                    + event.getValue());

            // No need to bother proceeding if it was already a dealbreaker to begin with
            return;
        }

        // Cap the value if we need to
        if (Integer.class.isAssignableFrom(gameRule.getType()) && gameRuleCaps.containsKey(gameRule))
        {
            final List<Integer> limits = gameRuleCaps.get(gameRule);
            final int requested = Integer.parseInt(event.getValue());
            final int clamped = Math.clamp(requested, limits.getFirst(), limits.getLast());

            // Uh oh, we've been clamped!
            if (requested != clamped)
            {
                FLog.warning("Clamped requested gamerule value "
                        + gameRule.getKey()
                        + " from "
                        + requested
                        + " to "
                        + clamped);

                event.setValue(Integer.toString(clamped));
            }
        }
    }

    private void enforceGameRuleDefaults()
    {
        // For each world...
        for (World world : Bukkit.getWorlds())
        {
            enforceGameRuleDefaultsForWorld(world);
        }
    }

    public void enforceGameRuleDefaultsForWorld(World world)
    {
        // For each game rule...
        for (GameRule<?> key : defaultGameRuleValues.keySet())
        {
            // Enforce the default
            // This is stupid, but fuck it. I don't care
            enforceGameRuleDefault(world, key);
        }
    }

    private <T> void enforceGameRuleDefault(World world, GameRule<T> rule)
    {
        world.setGameRule(rule, rule.getType().cast(defaultGameRuleValues.get(rule)));
    }

    public <T> void setGameRule(GameRule<T> rule, T newValue)
    {
        defaultGameRuleValues.put(rule, newValue);

        // Commit our changes across all worlds
        enforceGameRuleDefaults();
    }

    private int recordChange(String key)
    {
        final long second = System.currentTimeMillis() / 1000L;
        final RateWindow window = changeWindows.computeIfAbsent(key, k -> new RateWindow());
        synchronized (window)
        {
            if (window.second != second)
            {
                window.second = second;
                window.count = 0;
            }
            return ++window.count;
        }
    }

    private static final class RateWindow
    {
        private long second;
        private int count;
    }
}

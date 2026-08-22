package me.totalfreedom.totalfreedommod.cmd;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Consumer;

import org.bukkit.GameRules;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.PluginProvider;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;

import static me.totalfreedom.totalfreedommod.config.ConfigEntry.*;

@Command(name = "settings", description = "Modify TotalFreedom Server Settings / Flags", usage = "/settings [toggle | set] <setting> [<radius> | <range> <count>]", aliases = {"toggle", "set", "tfset"})
@Permission(permission = "tfm.server.settings")
public class Command_settings extends FCommand
{
    private static final double DEFAULT_EXPLOSION_RADIUS = 4.0;
    private static final double DEFAULT_MONITOR_RANGE = 10.0;
    private static final int DEFAULT_BREAK_COUNT = 100;

    @Callback
    public void queryConfigOption(final CommandSender sender, final Setting setting)
    {
        switch (setting)
        {
            case Setting.EXPLOSIVES ->
            {
                msg(sender, "<gray><displayable> (<flag>) is currently <status:enabled:disabled> with a radius of <radius>",
                        Placeholder.unparsed("displayable", setting.getDisplayable()),
                        Placeholder.unparsed("flag", setting.getFlag()),
                        Formatter.number("radius", EXPLOSIVE_RADIUS.getDouble()),
                        Formatter.booleanChoice("status", setting.getValue().getBoolean())
                );
            }
            case Setting.NO_NUKE ->
            {
                msg(sender, "<gray><displayable> (<flag>) is currently <status:enabled:disabled> with a range of <range> and a break count of <count>",
                        Placeholder.unparsed("displayable", setting.getDisplayable()),
                        Placeholder.unparsed("flag", setting.getFlag()),
                        Formatter.number("range", NUKE_MONITOR_RANGE.getDouble()),
                        Formatter.number("count", NUKE_MONITOR_COUNT_BREAK.getInteger()),
                        Formatter.booleanChoice("status", setting.getValue().getBoolean())
                );
            }
            default ->
            {
                msg(sender, "<gray><displayable> (<flag>) is currently <status:enabled:disabled>",
                        Placeholder.unparsed("displayable", setting.getDisplayable()),
                        Placeholder.unparsed("flag", setting.getFlag()),
                        Formatter.booleanChoice("status", setting.getValue().getBoolean())
                );
            }
        }
    }

    @Callback
    @Subcommand("toggle")
    public void toggleConfigOption(final CommandSender sender, final Setting setting)
    {
        setting.checkSource(sender);

        if (setting.equals(Setting.EXPLOSIVES))
        {
            setExplosiveRadius(sender, setting.getValue().getBoolean() ? 0 : DEFAULT_EXPLOSION_RADIUS);
            return;
        }

        if (setting.equals(Setting.NO_NUKE))
        {
            setMonitorAndCount(sender, setting.getValue().getBoolean() ? 0 : DEFAULT_MONITOR_RANGE, setting.getValue().getBoolean() ? 0 : DEFAULT_BREAK_COUNT);
            return;
        }

        final boolean enabled = !setting.getValue().getBoolean();
        setting.getValue().setBoolean(enabled);
        setting.consume();

        adminAction(sender, 
                    "<red>Setting <displayable> to <status:enabled:disabled>",
                    Placeholder.unparsed("displayable", setting.getDisplayable()),
                    Formatter.booleanChoice("status", enabled)
                );

        msg(sender, 
            "<gray><displayable> (<flag>) changed to <status:enabled:disabled>",
            Placeholder.unparsed("displayable", setting.getDisplayable()),
            Placeholder.unparsed("flag", setting.getFlag()),
            Formatter.booleanChoice("status", enabled)
        );
    }


    @Callback
    @Subcommand("set")
    public void setExplicit(final CommandSender sender, final Setting setting, final boolean value)
    {
        setting.checkSource(sender);

        if (setting.equals(Setting.EXPLOSIVES))
        {
            setExplosiveRadius(sender, value ? DEFAULT_EXPLOSION_RADIUS : 0);
            return;
        }

        if (setting.equals(Setting.NO_NUKE))
        {
            setMonitorAndCount(sender, value ? DEFAULT_MONITOR_RANGE : 0, value ? DEFAULT_BREAK_COUNT : 0);
            return;
        }

        setting.getValue().setBoolean(value);
        setting.consume();

        adminAction(sender, 
            "<red>Setting <displayable> to <status:enabled:disabled>",
            Placeholder.unparsed("displayable", setting.getDisplayable()),
            Formatter.booleanChoice("status", value)
        );

        msg(sender, "<gray><displayable> (<flag>) changed to <status:enabled:disabled>",
                Placeholder.unparsed("displayable", setting.getDisplayable()),
                Placeholder.unparsed("flag", setting.getFlag()),
                Formatter.booleanChoice("status", value)
        );
    }

    @Callback
    @Subcommand("set")
    public void setNumeric(final CommandSender sender, final Setting setting, final double range)
    {
        switch (setting)
        {
            case EXPLOSIVES -> setExplosiveRadius(sender, range);
            case NO_NUKE -> setMonitorAndCount(sender, range, DEFAULT_BREAK_COUNT);
            default -> throw new CommandFailException(setting.getDisplayable() + " (" + setting.getFlag() + ") only accepts true or false.");
        }
    }

    @Callback
    @Subcommand("set")
    public void setRangeAndCount(final CommandSender sender, final Setting setting, final double range, final int count)
    {
        if (setting != Setting.NO_NUKE)
        {
            throw new CommandFailException(setting.getDisplayable() + " (" + setting.getFlag() + ") does not take a range and break count.");
        }

        setMonitorAndCount(sender, range, count);
    }

    private void setExplosiveRadius(final CommandSender sender, final double radius)
    {
        final Setting setting = Setting.EXPLOSIVES;
        setting.checkSource(sender);

        // anything bigger will cause issues
        final double maxExplosionRadius = 25.0;
        final double cappedRadius = Math.min(radius, maxExplosionRadius);

        if (cappedRadius != 0)
        {
            setting.getValue().setBoolean(true);
            EXPLOSIVE_RADIUS.setDouble(cappedRadius);
        }
        else
        {
            setting.getValue().setBoolean(false);
        }

        adminAction(sender, 
                    "<red>Setting <displayable> to <status:enabled:disabled> with a radius of <radius>",
                    Placeholder.unparsed("displayable", setting.getDisplayable()),
                    Formatter.number("radius", cappedRadius),
                    Formatter.booleanChoice("status", setting.getValue().getBoolean())
        );

        msg(sender, "<gray><displayable> (<flag>) changed to <status:enabled:disabled> with a radius of <radius>",
                Placeholder.unparsed("displayable", setting.getDisplayable()),
                Placeholder.unparsed("flag", setting.getFlag()),
                Formatter.number("radius", cappedRadius),
                Formatter.booleanChoice("status", setting.getValue().getBoolean())
        );
    }

    private void setMonitorAndCount(final CommandSender sender, final double range, final int count)
    {
        Setting setting = Setting.NO_NUKE;
        setting.checkSource(sender);

        // Both must be at least 1: a range of 0 means there is nothing to monitor, and a count of 0 would flag every broken block.
        if (range >= 1 && count >= 1)
        {
            setting.getValue().setBoolean(true);
            NUKE_MONITOR_RANGE.setDouble(range);
            NUKE_MONITOR_COUNT_BREAK.setInteger(count);
        }
        else
        {
            setting.getValue().setBoolean(false);
        }

        adminAction(sender, 
            "<red>Setting <displayable> to <status:enabled:disabled> with a range of <range> and a count of <count>.",
            Placeholder.unparsed("displayable", setting.getDisplayable()),
            Formatter.number("range", range),
            Formatter.number("count", count),
            Formatter.booleanChoice("status", setting.getValue().getBoolean())
        );

        msg(sender, "<gray><displayable> (<flag>) changed to <status:'enabled with range <range> and break count <count>':disabled>",
                Placeholder.unparsed("displayable", setting.getDisplayable()),
                Placeholder.unparsed("flag", setting.getFlag()),
                Formatter.number("range", range),
                Formatter.number("count", count),
                Formatter.booleanChoice("status", setting.getValue().getBoolean())
        );
    }

    // We don't need an entire Argument Resolver for this. We can just plug in as an Enum and EnumArgumentResolver will do the rest.
    private enum Setting
    {
        FIRE_PLACE("fireplace", "Fire Placement", ALLOW_FIRE_PLACE),
        LAVA_PLACE("lavaplace", "Lava Placement", ALLOW_LAVA_PLACE),
        SIGN_PLACE("signplace", "Sign Placement", ALLOW_SIGN_PLACE),
        WATER_PLACE("waterplace", "Water Placement", ALLOW_WATER_PLACE),
        FIRE_SPREAD("firespread", "Fire Spreading", ALLOW_FIRE_SPREAD, c -> PluginProvider.get().gr.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, c.getBoolean() ? 128 : 0)),
        FLUID_SPREAD("fluidspread", "Fluid Spreading", ALLOW_FLUID_SPREAD),

        LAVA_DAMAGE("lavadmg", "Lava Damage", ALLOW_LAVA_DAMAGE),

        FALLING_BLOCKS("fallingblocks", "Falling Blocks", ALLOW_FALLING_BLOCKS),
        FALLING_SIGNS("fallingsigns", "Falling Signs", ALLOW_FALLING_SIGNS),

        REDSTONE("redstone", "Redstone", ALLOW_REDSTONE),

        ADMIN_MODE("adminmode", "Admin-Only Mode", ADMIN_ONLY_MODE, SourceType.ONLY_CONSOLE),
        LOCKDOWN("lockdown", "Lockdown", LOCKDOWN_MODE, SourceType.ONLY_CONSOLE),
        ENTITY_WIPE("entitywipe", "Automatic Entity Wiping", AUTO_ENTITY_WIPE),

        EXPLOSIVES("explosives", "Allow Explosions", ALLOW_EXPLOSIONS),
        NO_NUKE("nonuke", "Nuker Detection", NUKE_MONITOR_ENABLED),
        PET_PROTECT("petprotect", "Tamed Pet Protection", ENABLE_PET_PROTECT),

        AUTOCLEAR("autoclear", "Clearing Inventories on Join", AUTO_CLEAR),
        AUTOTP("autotp", "Teleportation on Join", AUTO_TP);

        final String flag, displayable;
        final ConfigEntry value;
        final Consumer<ConfigEntry> consumer;
        final SourceType requiredSource;

        Setting(final String flag, final String displayable, final ConfigEntry value, final Consumer<ConfigEntry> consumer)
        {
            this(flag, displayable, value, consumer, SourceType.BOTH);
        }

        Setting(final String flag, final String displayable, final ConfigEntry value)
        {
            this(flag, displayable, value, null, SourceType.BOTH);
        }

        Setting(final String flag, final String displayable, final ConfigEntry value, final SourceType requiredSource)
        {
            this(flag, displayable, value, null, requiredSource);
        }

        Setting(final String flag, final String displayable, final ConfigEntry value, final Consumer<ConfigEntry> consumer, final SourceType requiredSource)
        {
            this.flag = flag;
            this.displayable = displayable;
            this.value = value;
            this.consumer = consumer;
            this.requiredSource = requiredSource;
        }

        void consume()
        {
            Optional.ofNullable(consumer)
                    .ifPresent(c -> c.accept(value));
        }

        /**
         * Throws a CommandFailException if the intended source doesn't match.
         * This maintains standard functionality that the old toggle command had, for example, lockdown / adminmode could be run by a superadmin but only from console.
         */
        void checkSource(final CommandSender sender)
        {
            if (requiredSource == SourceType.ONLY_CONSOLE && sender instanceof Player)
            {
                throw new CommandFailException(displayable + " can only be changed from the console.");
            }

            if (requiredSource == SourceType.ONLY_IN_GAME && !(sender instanceof Player))
            {
                throw new CommandFailException(displayable + " can only be changed in-game.");
            }
        }

        String getFlag()
        {
            return flag;
        }

        String getDisplayable()
        {
            return displayable;
        }

        ConfigEntry getValue()
        {
            return value;
        }

        /**
         * Reflective access point for enum resolver so we can accept either the flag ("nonuke") or the constant name ("NO_NUKE") in any casing. 
         * This is marked "unused" but it is called via reflection so please DO NOT REMOVE IT.
         */
        @SuppressWarnings("unused")
        static Setting fromString(final String input)
        {
            return Arrays.stream(values())
                    .filter(setting -> setting.flag.equalsIgnoreCase(input) || setting.name().equalsIgnoreCase(input))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown setting: " + input));
        }
    }
}
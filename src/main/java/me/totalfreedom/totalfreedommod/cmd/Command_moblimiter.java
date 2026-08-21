package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.GameRules;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;

@Permission(permission = "tfm.server.moblimiter")
@Command(name = "moblimiter", description = "Control the MobLimiter.", usage = "/<command> <<on | off> | limit <limit> | <allow | block <type>>>")
public class Command_moblimiter extends FCommand
{
    @Callback
    public void showStatus(CommandSender sender)
    {
        final boolean enabled = ConfigEntry.MOB_LIMITER_ENABLED.getBoolean();

        if (enabled)
        {
            msg(sender, "<gray>MobLimiter <status:'<green>enabled':'<red>disabled'> with a global limit of <white><limit></white> entities.",
                    Formatter.booleanChoice("status", enabled),
                    Formatter.number("limit", ConfigEntry.MOB_LIMITER_MAX.getInteger()));

            msg(sender, "<gray>Currently blocked entity types:");
            msg(sender, "<types>", MessageUtils.joinedList("types",
                    plugin().mb.getBlockedMobTypes().stream().map(key -> key.asString()).toList(),
                    NamedTextColor.WHITE));
        }
        else
        {
            msg(sender, "<gray>MobLimiter <status:'<green>enabled':'<red>disabled'>.",
                    Formatter.booleanChoice("status", enabled));
        }
    }

    @Callback
    @Subcommand("allow")
    public void allowEntityType(CommandSender sender, @Resolve(value = "EntityType", strategy = "mobs") EntityType type)
    {
        if (!plugin().mb.isMobTypeBlocked(type))
        {
            msg(sender, "<gray><type> is not a blocked mob type.", Placeholder.unparsed("type", type.key().asString()));
            return;
        }

        plugin().mb.allowMobType(type);
        showStatus(sender);
    }

    @Callback
    @Subcommand("block")
    public void blockEntityType(CommandSender sender, @Resolve(value = "EntityType", strategy = "mobs") EntityType type)
    {
        if (plugin().mb.isMobTypeBlocked(type))
        {
            msg(sender, "<gray><type> is already a blocked mob type.", Placeholder.unparsed("type", type.key().asString()));
            return;
        }

        plugin().mb.blockMobType(type);
        showStatus(sender);
    }

    @Callback
    @Subcommand("limit")
    public void setMobLimiterLimit(CommandSender sender, int limit)
    {
        ConfigEntry.MOB_LIMITER_MAX.setInteger(Math.clamp(limit, 1, 2000));
        showStatus(sender);
    }

    @Callback
    public void setMobLimiterState(CommandSender sender, boolean value)
    {
        ConfigEntry.MOB_LIMITER_ENABLED.setBoolean(value);
        plugin().gr.setGameRule(GameRules.SPAWN_MOBS, !ConfigEntry.MOB_LIMITER_ENABLED.getBoolean());
        showStatus(sender);
    }
}

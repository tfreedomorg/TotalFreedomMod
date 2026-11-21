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
@Command(name = "moblimiter", description = "Control mob and entity spawn limits.",
        usage = "/<command> [on | off | limit <count> | allow <mob> | block <mob> | global <entity> <count> | chunk <entity> <count> | clearglobal <entity> | clearchunk <entity>]")
public class Command_moblimiter extends FCommand
{
    @Callback
    public void showStatus(CommandSender sender)
    {
        final boolean enabled = ConfigEntry.MOB_LIMITER_ENABLED.getBoolean();

        if (enabled)
        {
            msg(sender, "<gray>MobLimiter <status:'<green>enabled':'<red>disabled'> with a world-wide mob cap of <white><limit></white>.",
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

        final java.util.List<String> globalLimits = formatLimits(false);
        if (!globalLimits.isEmpty())
        {
            msg(sender, "<gray>Per-type world-wide limits:");
            msg(sender, "<limits>", MessageUtils.joinedList("limits", globalLimits, NamedTextColor.WHITE));
        }

        final java.util.List<String> chunkLimits = formatLimits(true);
        if (!chunkLimits.isEmpty())
        {
            msg(sender, "<gray>Per-type per-chunk limits:");
            msg(sender, "<limits>", MessageUtils.joinedList("limits", chunkLimits, NamedTextColor.WHITE));
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
        plugin().config.save();
        showStatus(sender);
    }

    @Callback
    public void setMobLimiterState(CommandSender sender, boolean value)
    {
        ConfigEntry.MOB_LIMITER_ENABLED.setBoolean(value);
        plugin().gr.setGameRule(GameRules.SPAWN_MOBS, !ConfigEntry.MOB_LIMITER_ENABLED.getBoolean());
        plugin().config.save();
        showStatus(sender);
    }

    @Callback
    @Subcommand("global")
    public void setGlobalEntityLimit(CommandSender sender, EntityType type, int limit)
    {
        plugin().mb.setEntityLimit(type, false, Math.clamp(limit, 1, 2000));
        showStatus(sender);
    }

    @Callback
    @Subcommand("chunk")
    public void setChunkEntityLimit(CommandSender sender, EntityType type, int limit)
    {
        plugin().mb.setEntityLimit(type, true, Math.clamp(limit, 1, 2000));
        showStatus(sender);
    }

    @Callback
    @Subcommand("clearglobal")
    public void clearGlobalEntityLimit(CommandSender sender, EntityType type)
    {
        if (!plugin().mb.removeEntityLimit(type, false))
        {
            msg(sender, "<gray><type> has no world-wide entity limit.", Placeholder.unparsed("type", type.key().asString()));
            return;
        }
        showStatus(sender);
    }

    @Callback
    @Subcommand("clearchunk")
    public void clearChunkEntityLimit(CommandSender sender, EntityType type)
    {
        if (!plugin().mb.removeEntityLimit(type, true))
        {
            msg(sender, "<gray><type> has no per-chunk entity limit.", Placeholder.unparsed("type", type.key().asString()));
            return;
        }
        showStatus(sender);
    }

    private java.util.List<String> formatLimits(boolean perChunk)
    {
        return plugin().mb.getEntityLimits(perChunk)
                .entrySet()
                .stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> entry.getKey().asString() + ": " + entry.getValue())
                .toList();
    }
}

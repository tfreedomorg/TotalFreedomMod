package me.totalfreedom.totalfreedommod.blocking.entity;

import java.util.regex.Pattern;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.blocking.sweep.EntityVisitor;
import me.totalfreedom.totalfreedommod.blocking.sweep.SweepContext;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.ComponentScanner;
import me.totalfreedom.totalfreedommod.util.DetectionReporter;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;

/**
 * Strips format bomb / oversized custom names from entities before the client
 * is supposed to see them.
 */
public class EntityNameValidator extends FreedomService
{

    private static final long LOG_INTERVAL_TICKS = 100L;
    private static final int MAX_COMMAND_LENGTH = 16384;
    private static final int MAX_COMMAND_BRACE_DEPTH = 8;

    // %1$s, %12$d, etc. — the format-spec pattern that triggers MessageFormat recursion.
    private static final Pattern FORMAT_SPECIFIER = Pattern.compile("%\\d+\\$[a-zA-Z]");

    private static final String[] NAMING_COMMANDS = new String[]
    {
            "summon", "data", "execute", "entitydata", "nick", "rename"
    };

    private final EntityVisitor nameVisitor = new EntityVisitor()
    {
        @Override
        public boolean enabled()
        {
            return EntityNameValidator.this.enabled();
        }

        @Override
        public long sweepIntervalTicks()
        {
            return ConfigEntry.CRASH_ENTITIES_SWEEP_TICKS.getInteger();
        }

        @Override
        public void visit(Entity entity, SweepContext context)
        {
            inspectAndClean(entity, context.label());
        }
    };

    private final DetectionReporter reporter = new DetectionReporter(
            LOG_INTERVAL_TICKS, server::getCurrentTick,
            (count, reason, max, sample) -> "[EntityNameValidator] Blocked " + count
                    + " cursed entity name(s). Reason: " + reason
                    + " | max observed size: " + max
                    + " | sample: " + sample,
            DetectionReporter.warnAndBroadcastAdmins(plugin));

    public EntityNameValidator(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        plugin.sweepScheduler.register(nameVisitor);
        long ticks = ConfigEntry.CRASH_ENTITIES_SWEEP_TICKS.getInteger();
        FLog.info("[EntityNameValidator] active"
                + " [entity name filter]"
                + " [chunk-load sweep]"
                + " [periodic sweep every " + ticks + "t]");
    }

    @Override
    protected void onStop()
    {
    }

    private boolean enabled()
    {
        return Boolean.TRUE.equals(ConfigEntry.CRASH_ENTITIES_PREVENT.getBoolean());
    }

    private int maxNameLength()
    {
        int v = ConfigEntry.CRASH_ENTITIES_MAX_NAME_LENGTH.getInteger();
        return v > 0 ? v : 64;
    }

    private int maxComponentNodes()
    {
        return ConfigEntry.maxComponentNodes();
    }

    enum Reason
    {
        CLEAN,
        OVERSIZED_NAME,
        CURSED_COMPONENT
    }

    record Verdict(Reason reason, long observedSize)
    {
        static final Verdict CLEAN = new Verdict(Reason.CLEAN, 0L);

        boolean isCursed()
        {
            return reason != Reason.CLEAN;
        }
    }

    private Verdict inspect(Entity entity)
    {
        if (entity == null)
        {
            return Verdict.CLEAN;
        }
        Component name;
        try
        {
            name = entity.customName();
        }
        catch (Throwable t)
        {
            return Verdict.CLEAN;
        }
        if (name == null)
        {
            return Verdict.CLEAN;
        }
        if (ComponentScanner.isCursed(name, maxComponentNodes()))
        {
            return new Verdict(Reason.CURSED_COMPONENT, -1L);
        }
        int len = ComponentScanner.safePlainTextLength(name, maxComponentNodes());
        if (len > maxNameLength())
        {
            return new Verdict(Reason.OVERSIZED_NAME, len);
        }
        return Verdict.CLEAN;
    }

    private boolean inspectAndClean(Entity entity, String context)
    {
        Verdict v = inspect(entity);
        if (!v.isCursed())
        {
            return false;
        }
        try
        {
            entity.customName(null);
            if (Boolean.TRUE.equals(ConfigEntry.CRASH_ENTITIES_STRIP_NAME_VISIBLE.getBoolean()))
            {
                entity.setCustomNameVisible(false);
            }
        }
        catch (Throwable ignored)
        {
        }
        recordDetection(v, context + " on " + entity.getType() + "@" + locShort(entity));
        return true;
    }

    private static String locShort(Entity entity)
    {
        try
        {
            return entity.getWorld().getName()
                    + ":" + Math.round(entity.getLocation().getX())
                    + "," + Math.round(entity.getLocation().getY())
                    + "," + Math.round(entity.getLocation().getZ());
        }
        catch (Throwable t)
        {
            return "?";
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityAddToWorld(EntityAddToWorldEvent event)
    {
        if (!enabled())
        {
            return;
        }
        inspectAndClean(event.getEntity(), "addtoworld");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event)
    {
        if (!enabled() || !screenCommandsEnabled())
        {
            return;
        }
        if (isCursedNamingCommand(event.getMessage()))
        {
            event.setCancelled(true);
            FUtil.playerMsg(event.getPlayer(),
                    "Your command was rejected; it would create an invalid entity name.",
                    NamedTextColor.RED);
            recordDetection(
                    new Verdict(Reason.CURSED_COMPONENT, event.getMessage().length()),
                    "command by " + event.getPlayer().getName());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event)
    {
        if (!enabled() || !screenCommandsEnabled())
        {
            return;
        }
        if (isCursedNamingCommand(event.getCommand()))
        {
            event.setCancelled(true);
            recordDetection(
                    new Verdict(Reason.CURSED_COMPONENT, event.getCommand().length()),
                    "console: " + event.getSender().getName());
        }
    }

    private boolean screenCommandsEnabled()
    {
        return Boolean.TRUE.equals(ConfigEntry.CRASH_ENTITIES_SCREEN_COMMANDS.getBoolean());
    }

    private boolean isCursedNamingCommand(String raw)
    {
        if (raw == null || raw.isEmpty())
        {
            return false;
        }
        if (raw.length() > MAX_COMMAND_LENGTH)
        {
            return looksLikeNamingCommand(raw);
        }
        if (!looksLikeNamingCommand(raw))
        {
            return false;
        }
        int depth = 0;
        int peak = 0;
        for (int i = 0; i < raw.length(); i++)
        {
            char c = raw.charAt(i);
            if (c == '{' || c == '[')
            {
                depth++;
                if (depth > peak)
                {
                    peak = depth;
                }
            }
            else if (c == '}' || c == ']')
            {
                depth--;
            }
        }
        if (peak > MAX_COMMAND_BRACE_DEPTH)
        {
            return true;
        }
        return FORMAT_SPECIFIER.matcher(raw).find();
    }

    private boolean looksLikeNamingCommand(String raw)
    {
        String command = raw.trim();
        if (command.startsWith("/"))
        {
            command = command.substring(1);
        }
        int sp = command.indexOf(' ');
        String head = (sp < 0 ? command : command.substring(0, sp)).toLowerCase();
        // strip "minecraft:" / "essentials:" prefix
        int colon = head.indexOf(':');
        if (colon >= 0 && colon < head.length() - 1)
        {
            head = head.substring(colon + 1);
        }
        for (String name : NAMING_COMMANDS)
        {
            if (head.equals(name))
            {
                return true;
            }
        }
        return false;
    }

    private void recordDetection(Verdict v, String context)
    {
        reporter.record(v.reason().name(), v.observedSize(), context);
    }
}

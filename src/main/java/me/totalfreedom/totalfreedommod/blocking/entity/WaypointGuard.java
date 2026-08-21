package me.totalfreedom.totalfreedommod.blocking.entity;

import java.util.ArrayList;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.blocking.sweep.EntityVisitor;
import me.totalfreedom.totalfreedommod.blocking.sweep.SweepContext;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.DetectionReporter;
import me.totalfreedom.totalfreedommod.util.FLog;

public class WaypointGuard extends FreedomService
{

    private static final long LOG_INTERVAL_TICKS = 100L;

    private final EntityVisitor waypointVisitor = new EntityVisitor()
    {
        @Override
        public boolean enabled()
        {
            return WaypointGuard.this.enabled();
        }

        @Override
        public long sweepIntervalTicks()
        {
            return ConfigEntry.CRASH_ENTITIES_WAYPOINT_SWEEP_TICKS.getInteger();
        }

        @Override
        public void visit(Entity entity, SweepContext context)
        {
            neutralize(entity, context.label());
        }
    };

    private final DetectionReporter reporter = new DetectionReporter(
            LOG_INTERVAL_TICKS, server::getCurrentTick,
            (count, reason, max, sample) -> "[WaypointGuard] Removed " + count
                    + " entity waypoint(s). Reason: " + reason
                    + " | max range: " + max
                    + " | sample: " + sample,
            DetectionReporter.warnAndBroadcastAdmins(plugin));

    public WaypointGuard(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        plugin.sweepScheduler.register(waypointVisitor);
        FLog.info("[WaypointGuard] active"
                + " [remove waypoints=" + enabled() + "]"
                + " [periodic sweep every " + ConfigEntry.CRASH_ENTITIES_WAYPOINT_SWEEP_TICKS.getInteger() + "t]");
    }

    @Override
    protected void onStop()
    {
    }

    private boolean enabled()
    {
        return Boolean.TRUE.equals(ConfigEntry.CRASH_ENTITIES_NEUTRALIZE_WAYPOINTS.getBoolean());
    }

    private boolean neutralize(Entity entity, String context)
    {
        if (!enabled())
        {
            return false;
        }
        if (!(entity instanceof LivingEntity living) || entity instanceof Player)
        {
            return false;
        }

        AttributeInstance instance;
        try
        {
            instance = living.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE);
        }
        catch (Throwable t)
        {
            return false;
        }
        if (instance == null)
        {
            return false;
        }

        double effective;
        try
        {
            effective = instance.getValue();
        }
        catch (Throwable t)
        {
            effective = instance.getBaseValue();
        }
        if (effective <= 0.0)
        {
            return false;
        }

        long observed = Math.round(effective);
        try
        {
            for (AttributeModifier modifier : new ArrayList<>(instance.getModifiers()))
            {
                try
                {
                    instance.removeModifier(modifier);
                }
                catch (Throwable ignored)
                {
                }
            }
            if (instance.getBaseValue() > 0.0)
            {
                instance.setBaseValue(0.0);
            }
        }
        catch (Throwable t)
        {
            return false;
        }

        recordDetection(observed, "waypoint-transmit", context + " on " + entity.getType() + "@" + locShort(entity));
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
        neutralize(event.getEntity(), "addtoworld");
    }

    private void recordDetection(long observed, String reason, String context)
    {
        reporter.record(reason, observed, context);
    }
}

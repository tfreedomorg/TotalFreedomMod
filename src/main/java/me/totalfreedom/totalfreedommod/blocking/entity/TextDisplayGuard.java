package me.totalfreedom.totalfreedommod.blocking.entity;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntitySpawnEvent;

import net.kyori.adventure.text.Component;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.blocking.sweep.EntityVisitor;
import me.totalfreedom.totalfreedommod.blocking.sweep.SweepContext;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.ComponentScanner;
import me.totalfreedom.totalfreedommod.util.DetectionReporter;
import me.totalfreedom.totalfreedommod.util.FLog;

/**
 * Removes certain text display entities that can be used to crash clients.
 */
public class TextDisplayGuard extends FreedomService
{

    private static final long LOG_INTERVAL_TICKS = 100L;
    private static final float MAX_VIEW_RANGE = 64.0f;
    private static final int MAX_TEXT_LENGTH = 512;
    private static final float MAX_INTERACTION_SIZE = 16.0f;
    private static final float MAX_SHADOW_RADIUS = 8.0f;
    private static final float MAX_SHADOW_STRENGTH = 16.0f;

    private final EntityVisitor displayVisitor = new EntityVisitor()
    {
        @Override
        public boolean enabled()
        {
            return TextDisplayGuard.this.enabled();
        }

        @Override
        public long sweepIntervalTicks()
        {
            return ConfigEntry.CRASH_ENTITIES_SWEEP_TICKS.getInteger();
        }

        @Override
        public void visit(Entity entity, SweepContext context)
        {
            removeEntity(entity, context.label());
        }
    };

    private final DetectionReporter reporter = new DetectionReporter(
            LOG_INTERVAL_TICKS, server::getCurrentTick,
            (count, reason, max, sample) -> "[EntityValidator] Removed " + count
                    + " bad text display(s). Sample: " + sample,
            DetectionReporter.warnOnly());

    public TextDisplayGuard(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        plugin.sweepScheduler.register(displayVisitor);
        long ticks = ConfigEntry.CRASH_ENTITIES_SWEEP_TICKS.getInteger();
        FLog.info("[TextDisplayGuard] active"
                + " [max_text_length=" + MAX_TEXT_LENGTH + "]"
                + " [max_view_range=" + MAX_VIEW_RANGE + "]"
                + " [max_shadow_radius=" + MAX_SHADOW_RADIUS + "]"
                + " [max_shadow_strength=" + MAX_SHADOW_STRENGTH + "]"
                + " [max_interaction_size=" + MAX_INTERACTION_SIZE + "]"
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

    private int maxComponentNodes()
    {
        return ConfigEntry.maxComponentNodes();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event)
    {
        if (!enabled())
        {
            return;
        }
        Entity entity = event.getEntity();
        if (!isCursed(entity))
        {
            return;
        }
        event.setCancelled(true);
        recordDetection("spawn-cancel on " + entity.getType() + "@" + locShort(entity));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityAddToWorld(EntityAddToWorldEvent event)
    {
        if (!enabled())
        {
            return;
        }
        removeEntity(event.getEntity(), "addtoworld");
    }

    private boolean isCursed(Entity entity)
    {
        if (entity instanceof Display display && hasCursedShadow(display))
        {
            return true;
        }
        if (entity instanceof TextDisplay display)
        {
            return isCursedTextDisplay(display);
        }
        if (entity instanceof Interaction interaction)
        {
            return isOversizedInteraction(interaction);
        }
        return false;
    }

    private boolean hasCursedShadow(Display display)
    {
        try
        {
            float radius = display.getShadowRadius();
            float strength = display.getShadowStrength();
            return !Float.isFinite(radius) || radius < 0.0f || radius > MAX_SHADOW_RADIUS
                    || !Float.isFinite(strength) || strength < 0.0f || strength > MAX_SHADOW_STRENGTH;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    private boolean isCursedTextDisplay(TextDisplay display)
    {
        Component text;
        try
        {
            text = display.text();
        }
        catch (Throwable t)
        {
            return false;
        }
        if (text != null)
        {
            if (ComponentScanner.isCursed(text, maxComponentNodes()))
            {
                return true;
            }
            int len = ComponentScanner.safePlainTextLength(text, maxComponentNodes());
            if (len > MAX_TEXT_LENGTH)
            {
                return true;
            }
        }
        try
        {
            return display.getViewRange() > MAX_VIEW_RANGE;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    private boolean isOversizedInteraction(Interaction interaction)
    {
        try
        {
            return interaction.getInteractionWidth() > MAX_INTERACTION_SIZE
                    || interaction.getInteractionHeight() > MAX_INTERACTION_SIZE;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    private void removeEntity(Entity entity, String context)
    {
        if (!isCursed(entity))
        {
            return;
        }
        try
        {
            entity.remove();
        }
        catch (Throwable ignored)
        {
            return;
        }
        recordDetection(context + " on " + entity.getType() + "@" + locShort(entity));
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

    private void recordDetection(String context)
    {
        reporter.record(context);
    }
}

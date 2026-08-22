package me.totalfreedom.totalfreedommod.util;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;

public final class DetectionReporter
{

    @FunctionalInterface
    public interface Summary
    {
        String format(long count, String dominantReason, long maxObservedSize, String sample);
    }

    private final long intervalTicks;
    private final LongSupplier clock;
    private final Summary summary;
    private final Consumer<String> sink;

    private long lastSummaryTick;
    private long count;
    private long maxObservedSize;
    private String dominantReason;
    private String sample;

    public DetectionReporter(long intervalTicks, LongSupplier clock, Summary summary, Consumer<String> sink)
    {
        this.intervalTicks = intervalTicks;
        this.clock = clock;
        this.summary = summary;
        this.sink = sink;
    }

    public void record(String context)
    {
        record(null, 0L, context);
    }

    public void record(String reason, String context)
    {
        record(reason, 0L, context);
    }

    public void record(String reason, long observedSize, String context)
    {
        long now = clock.getAsLong();
        if (count > 0 && lastSummaryTick != 0L && now - lastSummaryTick >= intervalTicks)
        {
            emit(now);
        }

        count++;
        if (observedSize > maxObservedSize)
        {
            maxObservedSize = observedSize;
        }
        if (dominantReason == null && reason != null)
        {
            dominantReason = reason;
        }
        if (sample == null)
        {
            sample = context;
        }

        if (lastSummaryTick == 0L || now - lastSummaryTick >= intervalTicks)
        {
            emit(now);
        }
    }

    private void emit(long now)
    {
        if (count == 0L)
        {
            return;
        }
        sink.accept(summary.format(count, dominantReason, maxObservedSize, sample));
        lastSummaryTick = now;
        count = 0L;
        maxObservedSize = 0L;
        dominantReason = null;
        sample = null;
    }

    public static Consumer<String> warnOnly()
    {
        return message -> FLog.warning(message, true);
    }

    public static Consumer<String> warnAndBroadcastAdmins(TotalFreedomMod plugin)
    {
        return message ->
        {
            FLog.warning(message, true);
            Component component = Component.text(message, NamedTextColor.RED);
            for (Player p : Bukkit.getOnlinePlayers())
            {
                if (plugin.al.isAdmin(p))
                {
                    p.sendMessage(component);
                }
            }
        };
    }
}

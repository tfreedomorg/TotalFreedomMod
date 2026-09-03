package me.totalfreedom.totalfreedommod.blocking.packet;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class MovementGuard
{

    private final ConcurrentHashMap<UUID, State> states = new ConcurrentHashMap<>();

    private final double maxHorizontalDeltaSq;
    private final int maxOversizedPerWindow;
    private final double maxBlocksPerSecond;

    MovementGuard(int maxHorizontalDelta, int maxOversizedPerWindow, int maxBlocksPerSecond)
    {
        this.maxHorizontalDeltaSq = (double) maxHorizontalDelta * (double) maxHorizontalDelta;
        this.maxOversizedPerWindow = maxOversizedPerWindow;
        this.maxBlocksPerSecond = maxBlocksPerSecond;
    }

    Decision recordAndCheck(UUID id, double x, double z)
    {
        if ((maxOversizedPerWindow <= 0 && maxBlocksPerSecond <= 0.0) || id == null)
            return Decision.ALLOW;

        final State state = states.computeIfAbsent(id, k -> new State());
        synchronized (state)
        {
            if (!state.hasLast)
            {
                state.lastX = x;
                state.lastZ = z;
                state.hasLast = true;
                return Decision.ALLOW;
            }

            final double dx = x - state.lastX;
            final double dz = z - state.lastZ;
            state.lastX = x;
            state.lastZ = z;

            if (System.currentTimeMillis() < state.graceUntil)
                return Decision.ALLOW;

            if (state.flagged)
                return Decision.BLOCK;

            final double distSq = dx * dx + dz * dz;

            final long second = System.currentTimeMillis() / 1000L;
            if (state.windowSecond != second)
            {
                state.windowSecond = second;
                state.oversizedCount = 0;
                state.windowDistance = 0.0;
            }

            if (distSq > maxHorizontalDeltaSq)
            {
                if (maxOversizedPerWindow <= 0)
                    return Decision.ALLOW;

                state.oversizedCount++;
                if (state.oversizedCount >= maxOversizedPerWindow)
                {
                    state.flagged = true;
                    return Decision.PUNISH;
                }
                return Decision.ALLOW;
            }

            if (maxBlocksPerSecond <= 0.0)
                return Decision.ALLOW;

            state.windowDistance += Math.sqrt(distSq);
            if (state.windowDistance > maxBlocksPerSecond)
            {
                state.flagged = true;
                return Decision.PUNISH;
            }

            return Decision.ALLOW;
        }
    }
    
    void grace(UUID id, long millis)
    {
        if (id == null || millis <= 0)
            return;

        final State state = states.computeIfAbsent(id, k -> new State());
        synchronized (state)
        {
            state.hasLast = false;
            state.flagged = false;
            state.oversizedCount = 0;
            state.windowDistance = 0.0;
            state.graceUntil = System.currentTimeMillis() + millis;
        }
    }

    void forget(UUID id)
    {
        if (id != null)
            states.remove(id);
    }

    void clear()
    {
        states.clear();
    }

    enum Decision
    {
        ALLOW,
        BLOCK,
        PUNISH
    }

    private static final class State
    {
        private boolean hasLast;
        private double lastX;
        private double lastZ;
        private long windowSecond;
        private int oversizedCount;
        private double windowDistance;
        private boolean flagged;
        private long graceUntil;
    }
}

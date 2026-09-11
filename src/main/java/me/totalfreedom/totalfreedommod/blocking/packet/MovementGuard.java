package me.totalfreedom.totalfreedommod.blocking.packet;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

final class MovementGuard
{

    private static final double PENDING_MOVEMENT_DISTANCE = 5.0;
    private static final long PENDING_MOVEMENT_WINDOW_MILLIS = 350L;

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
        {
            return Decision.ALLOW;
        }

        final State state = states.computeIfAbsent(id, ignored -> new State());
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

            if (state.flagged)
            {
                return Decision.BLOCK;
            }

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
                {
                    return Decision.ALLOW;
                }
                state.oversizedCount++;
                if (state.oversizedCount >= maxOversizedPerWindow)
                {
                    state.flagged = true;
                    return Decision.PUNISH;
                }
                return Decision.ALLOW;
            }

            if (maxBlocksPerSecond <= 0.0)
            {
                return Decision.ALLOW;
            }
            state.windowDistance += Math.sqrt(distSq);
            if (state.windowDistance > maxBlocksPerSecond)
            {
                state.flagged = true;
                return Decision.PUNISH;
            }

            return Decision.ALLOW;
        }
    }

    boolean recordAndCheckPendingJump(UUID id, double x, double y, double z)
    {
        if (id == null)
        {
            return false;
        }

        final State state = states.computeIfAbsent(id, ignored -> new State());
        synchronized (state)
        {
            if (!state.hasLastPosition)
            {
                final Player player = Bukkit.getPlayer(id);
                if (player != null)
                {
                    state.lastPositionX = player.getX();
                    state.lastPositionY = player.getY();
                    state.lastPositionZ = player.getZ();
                }
                else
                {
                    state.lastPositionX = x;
                    state.lastPositionY = y;
                    state.lastPositionZ = z;
                }
                state.pendingWindowStart = System.currentTimeMillis();
                state.pendingWindowDistance = 0.0;
                state.hasLastPosition = true;

                if ((x - state.lastPositionX) * (x - state.lastPositionX)
                    + (y - state.lastPositionY) * (y - state.lastPositionY)
                    + (z - state.lastPositionZ) * (z - state.lastPositionZ)
                    >= PENDING_MOVEMENT_DISTANCE * PENDING_MOVEMENT_DISTANCE)
                {
                    return true;
                }

                state.lastPositionX = x;
                state.lastPositionY = y;
                state.lastPositionZ = z;
                return false;
            }

            final long now = System.currentTimeMillis();
            if (now - state.pendingWindowStart > PENDING_MOVEMENT_WINDOW_MILLIS)
            {
                state.pendingWindowStart = now;
                state.pendingWindowDistance = 0.0;
            }

            final double distanceSquared = (x - state.lastPositionX) * (x - state.lastPositionX)
                    + (y - state.lastPositionY) * (y - state.lastPositionY)
                    + (z - state.lastPositionZ) * (z - state.lastPositionZ);
            if (distanceSquared >= PENDING_MOVEMENT_DISTANCE * PENDING_MOVEMENT_DISTANCE)
            {
                return true;
            }

            state.pendingWindowDistance += Math.sqrt(distanceSquared);
            if (state.pendingWindowDistance >= PENDING_MOVEMENT_DISTANCE)
            {
                return true;
            }

            state.lastPositionX = x;
            state.lastPositionY = y;
            state.lastPositionZ = z;
            return false;
        }
    }

    void seedPosition(UUID id, double x, double y, double z)
    {
        if (id == null)
        {
            return;
        }

        final State state = states.computeIfAbsent(id, ignored -> new State());
        synchronized (state)
        {
            state.lastX = x;
            state.lastZ = z;
            state.lastPositionX = x;
            state.lastPositionY = y;
            state.lastPositionZ = z;
            state.pendingWindowStart = System.currentTimeMillis();
            state.pendingWindowDistance = 0.0;
            state.hasLast = true;
            state.hasLastPosition = true;
        }
    }

    void forget(UUID id)
    {
        if (id != null)
        {
            states.remove(id);
        }
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
        private boolean hasLastPosition;
        private double lastPositionX;
        private double lastPositionY;
        private double lastPositionZ;
        private long pendingWindowStart;
        private double pendingWindowDistance;
        private long windowSecond;
        private int oversizedCount;
        private double windowDistance;
        private boolean flagged;
    }
}

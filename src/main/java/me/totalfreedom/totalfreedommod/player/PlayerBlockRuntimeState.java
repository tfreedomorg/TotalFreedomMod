package me.totalfreedom.totalfreedommod.player;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.Component;

/**
 * Thread-safe transient state used by {@link PlayerBlockEnforcer}.
 */
final class PlayerBlockRuntimeState
{
    static final String BLOCKED_IDENTITY_PLACEHOLDER = "[blocked player]";

    private final Map<PlayerPair, Long> separationGraceDeadlines = new ConcurrentHashMap<>();
    private final Map<DirectedPlayerPair, Long> temporaryRevealDeadlines = new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;
    private final long separationGraceNanos;
    private final long temporaryRevealNanos;

    PlayerBlockRuntimeState(
            final LongSupplier nanoTime,
            final Duration separationGrace,
            final Duration temporaryReveal)
    {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.separationGraceNanos = requirePositiveDuration(separationGrace, "separationGrace");
        this.temporaryRevealNanos = requirePositiveDuration(temporaryReveal, "temporaryReveal");
    }

    void beginSeparationGrace(final UUID first, final UUID second)
    {
        separationGraceDeadlines.put(PlayerPair.of(first, second), deadlineAfter(separationGraceNanos));
    }

    void clearSeparationGrace(final UUID first, final UUID second)
    {
        separationGraceDeadlines.remove(PlayerPair.of(first, second));
    }

    boolean isInSeparationGrace(final UUID first, final UUID second)
    {
        final PlayerPair pair = PlayerPair.of(first, second);
        final Long deadline = separationGraceDeadlines.get(pair);
        return deadline != null && keepIfActive(separationGraceDeadlines, pair, deadline);
    }

    long beginTemporaryReveal(final UUID viewer, final UUID target)
    {
        final long deadline = deadlineAfter(temporaryRevealNanos);
        temporaryRevealDeadlines.put(new DirectedPlayerPair(viewer, target), deadline);
        return deadline;
    }

    boolean isTemporarilyRevealed(final UUID viewer, final UUID target)
    {
        final DirectedPlayerPair pair = new DirectedPlayerPair(viewer, target);
        final Long deadline = temporaryRevealDeadlines.get(pair);
        return deadline != null && keepIfActive(temporaryRevealDeadlines, pair, deadline);
    }

    boolean expireTemporaryReveal(final UUID viewer, final UUID target, final long expectedDeadline)
    {
        final DirectedPlayerPair pair = new DirectedPlayerPair(viewer, target);
        final Long currentDeadline = temporaryRevealDeadlines.get(pair);
        if (currentDeadline == null
                || currentDeadline.longValue() != expectedDeadline
                || isActive(currentDeadline))
        {
            return false;
        }

        return temporaryRevealDeadlines.remove(pair, currentDeadline);
    }

    boolean isCurrentTemporaryReveal(
            final UUID viewer,
            final UUID target,
            final long expectedDeadline)
    {
        final Long currentDeadline = temporaryRevealDeadlines.get(new DirectedPlayerPair(viewer, target));
        return currentDeadline != null && currentDeadline.longValue() == expectedDeadline;
    }

    void clearTemporaryReveal(final UUID viewer, final UUID target)
    {
        temporaryRevealDeadlines.remove(new DirectedPlayerPair(viewer, target));
    }

    void clearTemporaryRevealsFor(final UUID player)
    {
        Objects.requireNonNull(player, "player");
        temporaryRevealDeadlines.keySet()
                .removeIf(pair -> pair.viewer().equals(player) || pair.target().equals(player));
    }

    void pruneExpired()
    {
        final long now = nanoTime.getAsLong();
        separationGraceDeadlines.entrySet()
                .removeIf(entry -> entry.getValue() <= now);
        // Temporary reveals have one scheduled expiry task per directed pair. Keeping their
        // deadline until that task consumes it ensures visibility is re-applied even when the
        // general cleanup task happens to run first in the same tick.
    }

    void clear()
    {
        separationGraceDeadlines.clear();
        temporaryRevealDeadlines.clear();
    }

    static Component redactBlockedIdentities(
            final Component component,
            final Collection<String> identities)
    {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(identities, "identities");

        return BlockedIdentityFilter.fromIdentities(identities).redact(component);
    }

    private static long requirePositiveDuration(final Duration duration, final String description)
    {
        Objects.requireNonNull(duration, description);
        if (duration.isZero() || duration.isNegative())
            throw new IllegalArgumentException(String.format("%s must be positive", description));
        return duration.toNanos();
    }

    private long deadlineAfter(final long duration)
    {
        return Math.addExact(nanoTime.getAsLong(), duration);
    }

    private <T> boolean keepIfActive(final Map<T, Long> deadlines, final T key, final Long deadline)
    {
        if (isActive(deadline))
            return true;

        deadlines.remove(key, deadline);
        return false;
    }

    private boolean isActive(final long deadline)
    {
        return nanoTime.getAsLong() < deadline;
    }

    private record PlayerPair(UUID first, UUID second)
    {
        private static PlayerPair of(final UUID first, final UUID second)
        {
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(second, "second");
            return first.compareTo(second) <= 0
                    ? new PlayerPair(first, second)
                    : new PlayerPair(second, first);
        }
    }

    private record DirectedPlayerPair(UUID viewer, UUID target)
    {
        private DirectedPlayerPair
        {
            Objects.requireNonNull(viewer, "viewer");
            Objects.requireNonNull(target, "target");
        }
    }
}

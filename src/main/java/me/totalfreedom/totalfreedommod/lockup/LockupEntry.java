package me.totalfreedom.totalfreedommod.lockup;

import java.util.concurrent.TimeUnit;

/**
 * One standing lockup, keyed by the IP it was placed on rather than by username.
 * <p>
 * Spambots cycle names faster than they can cycle addresses, so the address is
 * the only part of a bot's identity worth remembering for the 24 hours a lockup
 * lasts. {@code username} is kept purely so {@code /lockup list} can name whoever
 * was locked up, and is not used for matching.
 */
public record LockupEntry(String ip, String lockedBy, String username, long expiresAtMs)
{
    public boolean isExpired(long nowMs)
    {
        return nowMs >= expiresAtMs;
    }

    /**
     * Time left on the lockup in a form fit for chat (e.g. {@code 23h 41m} or
     * {@code 4m}).
     */
    public String remainingLabel(long nowMs)
    {
        final long remainingMs = Math.max(0L, expiresAtMs - nowMs);
        final long hours = TimeUnit.MILLISECONDS.toHours(remainingMs);
        final long minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs) - TimeUnit.HOURS.toMinutes(hours);

        if (hours > 0L)
            return String.format("%dh %dm", hours, minutes);

        return String.format("%dm", minutes);
    }
}
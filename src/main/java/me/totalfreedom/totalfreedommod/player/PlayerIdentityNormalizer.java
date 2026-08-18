package me.totalfreedom.totalfreedommod.player;

/**
 * Shared identity canonicalization for nickname admission and viewer-specific redaction.
 */
public final class PlayerIdentityNormalizer
{
    private PlayerIdentityNormalizer()
    {
        throw new UnsupportedOperationException("Utility class");
    }

    public static String comparable(final String identity)
    {
        return BlockedIdentityFilter.normalizedComparableIdentity(identity);
    }
}

package me.totalfreedom.totalfreedommod.bridge;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BiPredicate;

final class SimpleVoiceChatPolicy
{
    private SimpleVoiceChatPolicy()
    {
    }

    static boolean shouldCancel(
            final UUID sender,
            final UUID receiver,
            final BiPredicate<UUID, UUID> blockedLookup)
    {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(blockedLookup, "blockedLookup");
        return !sender.equals(receiver) && blockedLookup.test(receiver, sender);
    }
}

package me.totalfreedom.totalfreedommod.bridge;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleVoiceChatPolicyTest
{
    private static final UUID SENDER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RECEIVER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void cancelsOnlyWhenTheReceiverBlockedTheSender()
    {
        assertTrue(SimpleVoiceChatPolicy.shouldCancel(
                SENDER,
                RECEIVER,
                (blocker, blocked) -> blocker.equals(RECEIVER) && blocked.equals(SENDER)));
        assertFalse(SimpleVoiceChatPolicy.shouldCancel(
                SENDER,
                RECEIVER,
                (blocker, blocked) -> blocker.equals(SENDER) && blocked.equals(RECEIVER)));
    }

    @Test
    void neverCancelsSelfAudio()
    {
        assertFalse(SimpleVoiceChatPolicy.shouldCancel(
                SENDER,
                SENDER,
                (blocker, blocked) -> true));
    }
}

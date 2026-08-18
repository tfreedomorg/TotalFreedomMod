package me.totalfreedom.totalfreedommod.bridge;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.LocationalSoundPacketEvent;
import de.maxhenkel.voicechat.api.events.PacketEvent;
import de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent;

/**
 * Simple Voice Chat API entry point. The API invokes sound-packet events once
 * for every receiver, which permits the directed block policy to cancel only
 * the receiver who blocked the speaker.
 */
public final class SimpleVoiceChatPluginAdapter implements VoicechatPlugin
{
    private static final BiPredicate<UUID, UUID> ALLOW_ALL = (blocker, blocked) -> false;

    private final AtomicReference<BiPredicate<UUID, UUID>> blockedLookup =
            new AtomicReference<>(ALLOW_ALL);

    public SimpleVoiceChatPluginAdapter(final BiPredicate<UUID, UUID> blockedLookup)
    {
        activate(blockedLookup);
    }

    @Override
    public String getPluginId()
    {
        return "totalfreedommod_player_block";
    }

    @Override
    public void registerEvents(final EventRegistration registration)
    {
        registration.registerEvent(LocationalSoundPacketEvent.class, this::filterSoundPacket);
        registration.registerEvent(EntitySoundPacketEvent.class, this::filterSoundPacket);
        registration.registerEvent(StaticSoundPacketEvent.class, this::filterSoundPacket);
    }

    public void activate(final BiPredicate<UUID, UUID> refreshedBlockedLookup)
    {
        blockedLookup.set(Objects.requireNonNull(refreshedBlockedLookup, "refreshedBlockedLookup"));
    }

    public void deactivate()
    {
        blockedLookup.set(ALLOW_ALL);
    }

    private void filterSoundPacket(final PacketEvent<?> event)
    {
        final VoicechatConnection senderConnection = event.getSenderConnection();
        final VoicechatConnection receiverConnection = event.getReceiverConnection();
        if (senderConnection == null
                || receiverConnection == null
                || senderConnection.getPlayer() == null
                || receiverConnection.getPlayer() == null)
        {
            return;
        }

        final UUID senderUuid = senderConnection.getPlayer().getUuid();
        final UUID receiverUuid = receiverConnection.getPlayer().getUuid();
        if (SimpleVoiceChatPolicy.shouldCancel(
                senderUuid,
                receiverUuid,
                blockedLookup.get()))
        {
            event.cancel();
        }
    }
}

package me.totalfreedom.totalfreedommod.blocking.packet;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.format.NamedTextColor;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemAttributeModifiers;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.recipe.data.MerchantOffer;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.*;
import com.github.retrooper.packetevents.wrapper.play.server.*;

import io.netty.buffer.ByteBuf;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.blocking.entity.EntityMetaPacketGuard;
import me.totalfreedom.totalfreedommod.blocking.item.ContainerPacketGuard;
import me.totalfreedom.totalfreedommod.blocking.sign.SignPacketGuard;
import me.totalfreedom.totalfreedommod.blocking.spawner.SpawnerPacketGuard;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.player.FPlayer;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FSync;
import me.totalfreedom.totalfreedommod.util.FTask;

import io.github.retrooper.packetevents.util.SpigotConversionUtil;

final class CrashPacketListener extends PacketListenerAbstract
{

    /**
     * Must stay at or below {@code ItemScanner.MAX_ATTRIBUTE_MODIFIERS}; this is the outer of two
     * gates on the same bomb, with the scanner catching whatever arrives by another route.
     */
    private static final int MAX_CREATIVE_ATTRIBUTE_MODIFIERS = 64;
    private static final int MAX_PACKET_BYTES = 2_097_152;
    private static final int CHUNK_REENCODE_SAFE_BYTES = MAX_PACKET_BYTES - 256_000;

    private static final com.github.retrooper.packetevents.protocol.item.ItemStack EMPTY =
            com.github.retrooper.packetevents.protocol.item.ItemStack.EMPTY;

    private final TotalFreedomMod plugin;
    private final boolean sanitizeOutbound;
    private final boolean entityMetadataGuard;
    private final EntityMetaPacketGuard.Limits entityLimits;
    private final PacketSpamLimiter spamLimiter;
    private final MovementGuard movementGuard;
    private final CommandDepthGuard commandDepthGuard;
    // Last epoch-second we logged a depth block for a given user, so a flood produces at most one
    // console line per attacker per second instead of re-creating the log spam we are guarding against.
    private final ConcurrentHashMap<UUID, Long> lastDepthLogSecond = new ConcurrentHashMap<>();
    private final boolean signBlockEntityGuard;
    private final boolean signChunkGuard;
    private final boolean blockAllSignPackets;
    private final boolean spawnerBlockEntityGuard;
    private final boolean spawnerChunkGuard;
    private final boolean containerBlockEntityGuard;
    private final boolean containerChunkGuard;

    CrashPacketListener(TotalFreedomMod plugin, boolean sanitizeOutbound, boolean entityMetadataGuard,
                             EntityMetaPacketGuard.Limits entityLimits, PacketSpamLimiter spamLimiter,
                             MovementGuard movementGuard, CommandDepthGuard commandDepthGuard,
                             boolean signBlockEntityGuard,
                             boolean signChunkGuard, boolean blockAllSignPackets,
                             boolean spawnerBlockEntityGuard, boolean spawnerChunkGuard,
                             boolean containerBlockEntityGuard, boolean containerChunkGuard)
    {
        super(PacketListenerPriority.HIGH);
        this.plugin = plugin;
        this.sanitizeOutbound = sanitizeOutbound;
        this.entityMetadataGuard = entityMetadataGuard;
        this.entityLimits = entityLimits;
        this.spamLimiter = spamLimiter;
        this.movementGuard = movementGuard;
        this.commandDepthGuard = commandDepthGuard;
        this.signBlockEntityGuard = signBlockEntityGuard;
        this.signChunkGuard = signChunkGuard;
        this.blockAllSignPackets = blockAllSignPackets;
        this.spawnerBlockEntityGuard = spawnerBlockEntityGuard;
        this.spawnerChunkGuard = spawnerChunkGuard;
        this.containerBlockEntityGuard = containerBlockEntityGuard;
        this.containerChunkGuard = containerChunkGuard;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event)
    {
        try
        {
            final PacketTypeCommon type = event.getPacketType();
            final UUID id = event.getUser().getUUID();
            if (id == null)
            {
                return;
            }

            if (type == PacketType.Play.Client.CHAT_MESSAGE && handleEmptyChatPacket(event, id))
            {
                return;
            }

            if (type == PacketType.Play.Client.CREATIVE_INVENTORY_ACTION && handleCreativeSlot(event, id))
            {
                return;
            }

            if (commandDepthGuard != null && handleCommandDepth(event, type, id))
            {
                return;
            }

            if (spamLimiter == null && movementGuard == null)
            {
                return;
            }

            // see if it's already been canceled
            if (movementGuard != null && handleMovementSpeed(event, type, id))
            {
                return;
            }

            if (spamLimiter == null)
            {
                return;
            }
            if (type == PacketType.Play.Client.HELD_ITEM_CHANGE)
            {
                if (!spamLimiter.allowHeldSwitch(id))
                {
                    event.setCancelled(true);
                }
            }
            else if (type == PacketType.Play.Client.CHANGE_GAME_MODE)
            {
                if (!spamLimiter.allowGameModeSwitch(id))
                {
                    event.setCancelled(true);
                }
            }
            else if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT
                    || type == PacketType.Play.Client.USE_ITEM
                    || type == PacketType.Play.Client.PLAYER_DIGGING
                    || type == PacketType.Play.Client.INTERACT_ENTITY
                    || type == PacketType.Play.Client.ANIMATION
                    || type == PacketType.Play.Client.CREATIVE_INVENTORY_ACTION
                    || type == PacketType.Play.Client.ENTITY_ACTION)
            {
                if (!spamLimiter.allowInteraction(id))
                {
                    event.setCancelled(true);
                }
            }
            else if (type == PacketType.Play.Client.PLAYER_POSITION
                    || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
                    || type == PacketType.Play.Client.PLAYER_ROTATION
                    || type == PacketType.Play.Client.PLAYER_FLYING
                    || type == PacketType.Play.Client.VEHICLE_MOVE)
            {
                if (!spamLimiter.allowMovement(id))
                {
                    event.setCancelled(true);
                }
            }
            else if (type == PacketType.Play.Client.CHAT_COMMAND
                    || type == PacketType.Play.Client.CHAT_COMMAND_UNSIGNED
                    || type == PacketType.Play.Client.CHAT_MESSAGE)
            {
                if (!spamLimiter.allowChat(id))
                {
                    event.setCancelled(true);
                }
            }
            else if (type == PacketType.Play.Client.TAB_COMPLETE)
            {
                if (!spamLimiter.allowSuggestion(id))
                {
                    event.setCancelled(true);
                }
            }
        }
        catch (Throwable ignored)
        {
        }
    }

    /**
     * Drops creative set-slot packets carrying an attribute-modifier bomb.
     * 
     * @return true if the packet was cancelled
     */
    private boolean handleCreativeSlot(PacketReceiveEvent event, UUID id)
    {
        final int modifiers;
        try
        {
            final com.github.retrooper.packetevents.protocol.item.ItemStack item =
                    new WrapperPlayClientCreativeInventoryAction(event).getItemStack();
            if (item == null)
            {
                return false;
            }

            final ItemAttributeModifiers attributes = item.getComponentOr(
                    ComponentTypes.ATTRIBUTE_MODIFIERS, null);
            if (attributes == null)
            {
                return false;
            }
            modifiers = attributes.getModifiers().size();
        }
        catch (Throwable ignored)
        {
            return false;
        }

        if (modifiers <= MAX_CREATIVE_ATTRIBUTE_MODIFIERS)
        {
            return false;
        }

        event.setCancelled(true);

        final Player player = Bukkit.getPlayer(id);
        final String who = player != null ? player.getName() : String.valueOf(id);
        FSync.bcastMsg(who + " tried to place an item carrying " + modifiers
                + " attribute modifiers; the packet was dropped.", NamedTextColor.RED);

        return true;
    }

    private boolean handleEmptyChatPacket(PacketReceiveEvent event, UUID id)
    {
        if (!ConfigEntry.ANTISPAM_ENABLED.getBoolean(true))
        {
            return false;
        }

        final String raw;
        try
        {
            raw = new WrapperPlayClientChatMessage(event).getMessage();
        }
        catch (Throwable ignored)
        {
            return false;
        }

        if (raw != null && !raw.trim().isEmpty())
        {
            return false;
        }

        event.setCancelled(true);

        final Player player = Bukkit.getPlayer(id);
        if (player == null)
        {
            return true;
        }

        final FPlayer playerdata = plugin.pl.getPlayerSync(player);
        final int count = playerdata.incrementAndGetMsgCount();
        final int limit = ConfigEntry.ANTISPAM_LIMIT.getInteger(8);
        if (count >= limit)
        {
            if (count == limit)
            {
                FSync.bcastMsg(player.getName() + " was automatically kicked for spamming chat.", NamedTextColor.RED);
                FSync.autoEject(player, "Kicked for spamming chat.");
            }
        }

        return true;
    }

    private static String describeUser(PacketReceiveEvent event)
    {
        try
        {
            String name = event.getUser().getName();
            if (name != null)
            {
                return name;
            }
            UUID id = event.getUser().getUUID();
            return id == null ? "unknown" : id.toString();
        }
        catch (Throwable t)
        {
            return "unknown";
        }
    }

    private boolean handleCommandDepth(PacketReceiveEvent event, PacketTypeCommon type, UUID id)
    {
        final String text = commandText(event, type);
        if (text == null || commandDepthGuard.depthOf(text) <= commandDepthGuard.maxDepth())
        {
            return false;
        }

        event.setCancelled(true);
        logDepthBlock(event, id);
        return true;
    }

    private static String commandText(PacketReceiveEvent event, PacketTypeCommon type)
    {
        if (type == PacketType.Play.Client.CHAT_COMMAND)
        {
            return new WrapperPlayClientChatCommand(event).getCommand();
        }
        if (type == PacketType.Play.Client.CHAT_COMMAND_UNSIGNED)
        {
            return new WrapperPlayClientChatCommandUnsigned(event).getCommand();
        }
        if (type == PacketType.Play.Client.TAB_COMPLETE)
        {
            return new WrapperPlayClientTabComplete(event).getText();
        }
        return null;
    }

    private void logDepthBlock(PacketReceiveEvent event, UUID id)
    {
        final long second = System.currentTimeMillis() / 1000L;
        final Long previous = lastDepthLogSecond.put(id, second);
        if (previous != null && previous == second)
        {
            return;
        }
        FLog.warning("[CommandDepthGuard] Blocked nested command from "
                + describeUser(event) + " (>" + commandDepthGuard.maxDepth() + " brackets).");
    }

    private boolean handleMovementSpeed(PacketReceiveEvent event, PacketTypeCommon type, UUID id)
    {
        final Vector3d position = movementPosition(event, type);
        if (position == null)
        {
            return false;
        }

        final MovementGuard.Decision decision = movementGuard.recordAndCheck(id, position.getX(), position.getZ());
        if (decision == MovementGuard.Decision.ALLOW)
        {
            return false;
        }

        event.setCancelled(true);
        if (decision == MovementGuard.Decision.PUNISH)
        {
            punishSpeed(event, id);
        }
        return true;
    }

    private static Vector3d movementPosition(PacketReceiveEvent event, PacketTypeCommon type)
    {
        if (type == PacketType.Play.Client.PLAYER_POSITION)
        {
            return new WrapperPlayClientPlayerPosition(event).getPosition();
        }
        if (type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)
        {
            return new WrapperPlayClientPlayerPositionAndRotation(event).getPosition();
        }
        if (type == PacketType.Play.Client.VEHICLE_MOVE)
        {
            return new WrapperPlayClientVehicleMove(event).getPosition();
        }
        // PLAYER_FLYING / PLAYER_ROTATION carry no position.
        return null;
    }

    private void punishSpeed(PacketReceiveEvent event, UUID id)
    {
        final String name = event.getUser().getName();
        final String who = name != null ? name : id.toString();

        FSync.bcastMsg(who + " is moving too quickly across chunks!", NamedTextColor.RED);

        Bukkit.getScheduler().runTask(plugin, FTask.guard("CrashPacketListener/ejectFastMover", () ->
        {
            final Player player = Bukkit.getPlayer(id);
            if (player != null)
            {
                plugin.ae.autoEject(player, "Moving too quickly across chunks is not permitted.");
            }
        }));
    }

    @Override
    public void onPacketSend(PacketSendEvent event)
    {
        try
        {
            final PacketTypeCommon type = event.getPacketType();

            if (type == PacketType.Play.Server.ENTITY_METADATA
                    && (sanitizeOutbound || entityMetadataGuard))
            {
                handleEntityMetadata(event);
                return;
            }
            if (entityMetadataGuard)
            {
                if (type == PacketType.Play.Server.SPAWN_LIVING_ENTITY)
                {
                    handleSpawnLivingEntity(event);
                    return;
                }
                if (type == PacketType.Play.Server.UPDATE_ATTRIBUTES)
                {
                    handleUpdateAttributes(event);
                    return;
                }
            }
            if (sanitizeOutbound)
            {
                if (type == PacketType.Play.Server.ENTITY_EQUIPMENT)
                {
                    handleEntityEquipment(event);
                    return;
                }
                if (type == PacketType.Play.Server.SET_SLOT)
                {
                    handleSetSlot(event);
                    return;
                }
                if (type == PacketType.Play.Server.WINDOW_ITEMS)
                {
                    handleWindowItems(event);
                    return;
                }
                if (type == PacketType.Play.Server.MERCHANT_OFFERS)
                {
                    handleMerchantOffers(event);
                    return;
                }
            }

            if ((blockAllSignPackets || signBlockEntityGuard || spawnerBlockEntityGuard || containerBlockEntityGuard)
                    && type == PacketType.Play.Server.BLOCK_ENTITY_DATA)
            {
                handleBlockEntityData(event);
            }
            else if ((blockAllSignPackets || signChunkGuard || spawnerChunkGuard || containerChunkGuard)
                    && type == PacketType.Play.Server.CHUNK_DATA)
            {
                handleChunkData(event);
            }
        }
        catch (Throwable ignored)
        {
        }
    }

    private void handleBlockEntityData(PacketSendEvent event)
    {
        WrapperPlayServerBlockEntityData wrapper = new WrapperPlayServerBlockEntityData(event);
        NBTCompound nbt = wrapper.getNBT();

        if ((blockAllSignPackets || signBlockEntityGuard) && SignPacketGuard.isSignBlockEntity(nbt))
        {
            if (blockAllSignPackets || SignPacketGuard.isUnsafe(nbt))
            {
                event.setCancelled(true);
            }
            return;
        }

        if (spawnerBlockEntityGuard
                && SpawnerPacketGuard.isSpawnerBlockEntity(nbt)
                && SpawnerPacketGuard.isUnsafe(nbt))
        {
            event.setCancelled(true);
            return;
        }

        if (containerBlockEntityGuard
                && ContainerPacketGuard.isItemBearingBlockEntity(nbt)
                && ContainerPacketGuard.isUnsafe(nbt))
        {
            event.setCancelled(true);
        }
    }

    private void handleChunkData(PacketSendEvent event)
    {
        if (!safeToTouchChunkPacket(event))
        {
            return;
        }

        WrapperPlayServerChunkData wrapper = new WrapperPlayServerChunkData(event);
        Column column = wrapper.getColumn();
        boolean dirty = false;

        if (blockAllSignPackets)
        {
            dirty |= SignPacketGuard.stripAllSignsInColumn(column) > 0;
        }
        else if (signChunkGuard)
        {
            dirty |= SignPacketGuard.sanitizeColumn(column) > 0;
        }

        if (spawnerChunkGuard)
        {
            dirty |= SpawnerPacketGuard.sanitizeColumn(column) > 0;
        }

        if (containerChunkGuard)
        {
            dirty |= ContainerPacketGuard.sanitizeColumn(column) > 0;
        }

        if (dirty)
        {
            event.markForReEncode(true);
        }
    }

    private static boolean safeToTouchChunkPacket(PacketSendEvent event)
    {
        ClientVersion client = event.getUser().getClientVersion();
        if (client != null && client.isOlderThanOrEquals(ClientVersion.V_1_12_2))
        {
            return false;
        }
        int bytes = packetByteLength(event);
        return bytes == 0 || bytes < CHUNK_REENCODE_SAFE_BYTES;
    }

    private static int packetByteLength(PacketSendEvent event)
    {
        Object buf = event.getByteBuf();
        if (buf instanceof ByteBuf byteBuf)
        {
            return byteBuf.readableBytes();
        }
        return 0;
    }

    private void handleEntityEquipment(PacketSendEvent event)
    {
        WrapperPlayServerEntityEquipment wrapper = new WrapperPlayServerEntityEquipment(event);
        List<Equipment> equipment = wrapper.getEquipment();
        boolean dirty = false;
        for (Equipment slot : equipment)
        {
            if (isCursed(slot.getItem()))
            {
                slot.setItem(EMPTY);
                dirty = true;
            }
        }
        if (dirty)
        {
            wrapper.setEquipment(equipment);
            event.markForReEncode(true);
        }
    }

    private void handleSetSlot(PacketSendEvent event)
    {
        WrapperPlayServerSetSlot wrapper = new WrapperPlayServerSetSlot(event);
        if (isCursed(wrapper.getItem()))
        {
            wrapper.setItem(EMPTY);
            event.markForReEncode(true);
        }
    }

    private void handleWindowItems(PacketSendEvent event)
    {
        WrapperPlayServerWindowItems wrapper = new WrapperPlayServerWindowItems(event);
        boolean dirty = false;

        List<com.github.retrooper.packetevents.protocol.item.ItemStack> items = wrapper.getItems();
        for (int i = 0; i < items.size(); i++)
        {
            if (isCursed(items.get(i)))
            {
                items.set(i, EMPTY);
                dirty = true;
            }
        }
        if (dirty)
        {
            wrapper.setItems(items);
        }

        Optional<com.github.retrooper.packetevents.protocol.item.ItemStack> carried = wrapper.getCarriedItem();
        if (carried.isPresent() && isCursed(carried.get()))
        {
            wrapper.setCarriedItem(EMPTY);
            dirty = true;
        }

        if (dirty)
        {
            event.markForReEncode(true);
        }
    }

    private void handleMerchantOffers(PacketSendEvent event)
    {
        WrapperPlayServerMerchantOffers wrapper = new WrapperPlayServerMerchantOffers(event);
        List<MerchantOffer> offers = wrapper.getMerchantOffers();
        List<MerchantOffer> safe = new ArrayList<>(offers.size());
        boolean dirty = false;
        for (MerchantOffer offer : offers)
        {
            if (offer != null
                    && (isCursed(offer.getFirstInputItem())
                    || isCursed(offer.getSecondInputItem())
                    || isCursed(offer.getOutputItem())))
            {
                dirty = true;
                continue;
            }
            safe.add(offer);
        }
        if (dirty)
        {
            wrapper.setMerchantOffers(safe);
            event.markForReEncode(true);
        }
    }

    private void handleEntityMetadata(PacketSendEvent event)
    {
        WrapperPlayServerEntityMetadata wrapper = new WrapperPlayServerEntityMetadata(event);
        List<EntityData<?>> metadata = wrapper.getEntityMetadata();
        boolean dirty = false;
        if (sanitizeOutbound)
        {
            for (EntityData<?> data : metadata)
            {
                if (sanitizeItemMetadataEntry(data))
                {
                    dirty = true;
                }
            }
        }
        if (entityMetadataGuard && entityLimits != null)
        {
            dirty |= EntityMetaPacketGuard.sanitizeMetadata(metadata, entityLimits);
        }
        if (dirty)
        {
            wrapper.setEntityMetadata(metadata);
            event.markForReEncode(true);
        }
    }

    private void handleSpawnLivingEntity(PacketSendEvent event)
    {
        WrapperPlayServerSpawnLivingEntity wrapper = new WrapperPlayServerSpawnLivingEntity(event);
        List<EntityData<?>> metadata = wrapper.getEntityMetadata();
        boolean dirty = false;
        if (sanitizeOutbound)
        {
            for (EntityData<?> data : metadata)
            {
                if (sanitizeItemMetadataEntry(data))
                {
                    dirty = true;
                }
            }
        }
        if (entityLimits != null)
        {
            dirty |= EntityMetaPacketGuard.sanitizeMetadata(metadata, entityLimits);
        }
        if (dirty)
        {
            wrapper.setEntityMetadata(metadata);
            event.markForReEncode(true);
        }
    }

    private void handleUpdateAttributes(PacketSendEvent event)
    {
        if (entityLimits == null || entityLimits.maxScale() <= 0.0)
        {
            return;
        }
        WrapperPlayServerUpdateAttributes wrapper = new WrapperPlayServerUpdateAttributes(event);
        List<WrapperPlayServerUpdateAttributes.Property> properties = wrapper.getProperties();
        if (EntityMetaPacketGuard.sanitizeAttributes(properties, entityLimits.maxScale()))
        {
            wrapper.setProperties(properties);
            event.markForReEncode(true);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean sanitizeItemMetadataEntry(EntityData<?> data)
    {
        Object value = data.getValue();
        if (value instanceof com.github.retrooper.packetevents.protocol.item.ItemStack peItem)
        {
            if (isCursed(peItem))
            {
                ((EntityData) data).setValue(EMPTY);
                return true;
            }
            return false;
        }
        if (value instanceof Optional<?> opt
                && opt.isPresent()
                && opt.get() instanceof com.github.retrooper.packetevents.protocol.item.ItemStack peItem
                && isCursed(peItem))
        {
            ((EntityData) data).setValue(Optional.empty());
            return true;
        }
        return false;
    }

    private boolean isCursed(com.github.retrooper.packetevents.protocol.item.ItemStack peItem)
    {
        if (peItem == null || peItem.isEmpty())
        {
            return false;
        }
        org.bukkit.inventory.ItemStack bukkit;
        try
        {
            bukkit = SpigotConversionUtil.toBukkitItemStack(peItem);
        }
        catch (Throwable t)
        {
            return false;
        }
        return plugin.iv.isCursed(bukkit);
    }

}

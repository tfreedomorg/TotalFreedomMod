package me.totalfreedom.totalfreedommod.blocking.entity;

import java.util.List;
import java.util.Optional;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import com.github.retrooper.packetevents.protocol.attribute.Attribute;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataType;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;

import me.totalfreedom.totalfreedommod.util.ComponentScanner;

public final class EntityMetaPacketGuard
{

    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    public record Limits(int maxNameLength, int maxComponentNodes, double maxScale)
    {
    }

    private EntityMetaPacketGuard()
    {
    }

    public static boolean sanitizeMetadata(List<EntityData<?>> metadata, Limits limits)
    {
        if (metadata == null || metadata.isEmpty())
        {
            return false;
        }
        boolean dirty = false;
        for (EntityData<?> data : metadata)
        {
            if (sanitizeMetadataEntry(data, limits))
            {
                dirty = true;
            }
        }
        return dirty;
    }

    public static boolean sanitizeAttributes(List<WrapperPlayServerUpdateAttributes.Property> properties, double maxScale)
    {
        if (properties == null || properties.isEmpty() || maxScale <= 0.0)
        {
            return false;
        }
        boolean dirty = false;
        for (WrapperPlayServerUpdateAttributes.Property property : properties)
        {
            if (property == null || !isScaleAttribute(property.getAttribute()))
            {
                continue;
            }
            double clampedBase = clampScaleMagnitude(property.getValue(), maxScale);
            if (clampedBase != property.getValue())
            {
                property.setValue(clampedBase);
                dirty = true;
            }
            for (WrapperPlayServerUpdateAttributes.PropertyModifier modifier : property.getModifiers())
            {
                if (modifier == null)
                {
                    continue;
                }
                double clamped = clampScaleMagnitude(modifier.getAmount(), maxScale);
                if (clamped != modifier.getAmount())
                {
                    modifier.setAmount(clamped);
                    dirty = true;
                }
            }
        }
        return dirty;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean sanitizeMetadataEntry(EntityData<?> data, Limits limits)
    {
        if (data == null)
        {
            return false;
        }
        EntityDataType<?> type = data.getType();
        if (!isNameType(type))
        {
            return false;
        }
        Object value = data.getValue();
        if (value == null)
        {
            return false;
        }
        if (value instanceof Optional<?> opt)
        {
            if (opt.isEmpty())
            {
                return false;
            }
            Object inner = opt.get();
            if (!isCursedNameValue(inner, limits))
            {
                return false;
            }
            ((EntityData) data).setValue(emptyOptional(type));
            return true;
        }
        if (!isCursedNameValue(value, limits))
        {
            return false;
        }
        ((EntityData) data).setValue(emptyValueFor(value));
        return true;
    }

    private static boolean isNameType(EntityDataType<?> type)
    {
        return type == EntityDataTypes.OPTIONAL_ADV_COMPONENT
                || type == EntityDataTypes.OPTIONAL_COMPONENT
                || type == EntityDataTypes.ADV_COMPONENT
                || type == EntityDataTypes.COMPONENT
                || type == EntityDataTypes.STRING;
    }

    private static boolean isCursedNameValue(Object value, Limits limits)
    {
        if (value instanceof Component component)
        {
            return isCursedComponent(component, limits);
        }
        if (value instanceof String raw)
        {
            return isCursedRawName(raw, limits);
        }
        return false;
    }

    private static boolean isCursedComponent(Component component, Limits limits)
    {
        if (ComponentScanner.isCursed(component, limits.maxComponentNodes()))
        {
            return true;
        }
        return ComponentScanner.safePlainTextLength(component, limits.maxComponentNodes()) > limits.maxNameLength();
    }

    private static boolean isCursedRawName(String raw, Limits limits)
    {
        if (raw == null || raw.isEmpty())
        {
            return false;
        }
        if (raw.length() > limits.maxNameLength())
        {
            return true;
        }
        if (containsSuspiciousJson(raw))
        {
            return true;
        }
        try
        {
            Component parsed = GSON.deserialize(raw);
            return isCursedComponent(parsed, limits);
        }
        catch (Throwable ignored)
        {
            return raw.length() > limits.maxNameLength();
        }
    }

    private static boolean containsSuspiciousJson(String raw)
    {
        return raw.contains("click_event")
                || raw.contains("hover_event")
                || raw.contains("%1$");
    }

    private static Object emptyOptional(EntityDataType<?> type)
    {
        return Optional.empty();
    }

    private static Object emptyValueFor(Object value)
    {
        if (value instanceof Component)
        {
            return Component.empty();
        }
        if (value instanceof String)
        {
            return "";
        }
        return null;
    }

    private static boolean isScaleAttribute(Attribute attribute)
    {
        return attribute == Attributes.SCALE || attribute == Attributes.GENERIC_SCALE;
    }

    private static double clampScaleMagnitude(double value, double maxScale)
    {
        if (Math.abs(value) <= maxScale)
        {
            return value;
        }
        return Math.copySign(maxScale, value == 0.0 ? 1.0 : value);
    }
}

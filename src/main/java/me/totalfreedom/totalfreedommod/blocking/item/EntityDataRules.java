package me.totalfreedom.totalfreedommod.blocking.item;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.bukkit.Material;

final class EntityDataRules
{

    private static final String COMPOUND_TAG = "net.minecraft.nbt.CompoundTag";
    private static final String LIST_TAG = "net.minecraft.nbt.ListTag";
    private static final String STRING_TAG = "net.minecraft.nbt.StringTag";

    // NBT fields that must encode as [I; ...] int-arrays, not string lists prefixed with "I".
    private static final Set<String> INT_ARRAY_FIELD_NAMES = Set.of(
            "Owner", "UUID", "LoveCause", "TargetUUID",
            "beam_target"
    );

    private static final Set<String> PROJECTILE_ENTITY_IDS = Set.of(
            "minecraft:ender_pearl",
            "minecraft:snowball",
            "minecraft:egg",
            "minecraft:experience_bottle",
            "minecraft:potion",
            "minecraft:firework_rocket",
            "minecraft:arrow",
            "minecraft:spectral_arrow",
            "minecraft:trident"
    );

    // TypedEntityData fields that must encode as a numeric NBT tag, not a string.
    private static final Set<String> TYPED_NUMERIC_FIELDS = Set.of(
            "acceleration_power",
            // base-entity numeric flags shared by every malformed template
            "Glowing", "HasVisualFire", "Invulnerable", "NoGravity", "Silent",
            "Fire", "Air", "PortalCooldown",
            // interaction-specific numeric fields
            "width", "height", "response"
    );

    private static final Set<String> NUMERIC_TAG_NAMES = Set.of(
            "net.minecraft.nbt.ByteTag",
            "net.minecraft.nbt.ShortTag",
            "net.minecraft.nbt.IntTag",
            "net.minecraft.nbt.LongTag",
            "net.minecraft.nbt.FloatTag",
            "net.minecraft.nbt.DoubleTag"
    );

    // CompoundTag#getString(String): Optional<String> on 1.21.5+, String before.
    private static final Method COMPOUND_GET_STRING;
    // CompoundTag#get(String): nullable Tag (defensively unwrapped in case it is Optional).
    private static final Method COMPOUND_GET;
    // CompoundTag#keySet() (1.21.5+) or #getAllKeys() (older).
    private static final Method COMPOUND_KEYS;
    private static final Method LIST_SIZE;
    private static final Method LIST_GET;
    // StringTag value accessor: getAsString() (older) or value()/asString() (1.21.5+).
    private static final Method STRING_VALUE;

    static
    {
        Method getString = null;
        Method get = null;
        Method keys = null;
        Method listSize = null;
        Method listGet = null;
        Method stringValue = null;
        try
        {
            Class<?> compound = Class.forName(COMPOUND_TAG);
            getString = method(compound, "getString", String.class);
            get = method(compound, "get", String.class);
            keys = firstMethod(compound, "keySet", "getAllKeys");

            Class<?> list = Class.forName(LIST_TAG);
            listSize = method(list, "size");
            listGet = method(list, "get", int.class);

            Class<?> stringTag = Class.forName(STRING_TAG);
            stringValue = firstMethod(stringTag, "getAsString", "value", "asString");
        }
        catch (Throwable ignored)
        {
        }
        COMPOUND_GET_STRING = getString;
        COMPOUND_GET = get;
        COMPOUND_KEYS = keys;
        LIST_SIZE = listSize;
        LIST_GET = listGet;
        STRING_VALUE = stringValue;
    }

    enum Violation
    {
        SPAWN_EGG_ID_MISMATCH,
        MALFORMED_INT_ARRAY_FIELD,
        MALFORMED_TYPED_FIELD,
        PROJECTILE_ENTITY_DATA
    }

    private EntityDataRules()
    {
    }

    static boolean isAvailable()
    {
        return COMPOUND_GET_STRING != null
                && COMPOUND_GET != null
                && COMPOUND_KEYS != null
                && LIST_SIZE != null
                && LIST_GET != null
                && STRING_VALUE != null;
    }

    /**
     * @param entityDataRoot root compound from TypedEntityData
     * @param material       host item material (for spawn-egg id matching)
     * @return first violation found, or null if clean
     */
    static Violation checkRoot(Object entityDataRoot, Material material)
    {
        if (!isAvailable() || entityDataRoot == null)
        {
            return null;
        }
        if (!COMPOUND_TAG.equals(entityDataRoot.getClass().getName()))
        {
            return null;
        }
        try
        {
            Violation intArrayViolation = checkIntArrayFields(entityDataRoot);
            if (intArrayViolation != null)
            {
                return intArrayViolation;
            }

            Violation typedViolation = checkTypedNumericFields(entityDataRoot);
            if (typedViolation != null)
            {
                return typedViolation;
            }

            String entityId = getString(entityDataRoot, "id");
            if (entityId == null || entityId.isEmpty())
            {
                return null;
            }

            if (PROJECTILE_ENTITY_IDS.contains(entityId))
            {
                return Violation.PROJECTILE_ENTITY_DATA;
            }

            if (material != null && material.name().endsWith("_SPAWN_EGG"))
            {
                String expected = expectedSpawnEggEntityId(material);
                if (expected != null && !expected.equals(entityId))
                {
                    return Violation.SPAWN_EGG_ID_MISMATCH;
                }
            }
        }
        catch (Throwable ignored)
        {
            return null;
        }
        return null;
    }

    static boolean hasOwner(Object entityDataRoot)
    {
        if (!isAvailable() || entityDataRoot == null)
        {
            return false;
        }
        if (!COMPOUND_TAG.equals(entityDataRoot.getClass().getName()))
        {
            return false;
        }
        try
        {
            return unwrap(COMPOUND_GET.invoke(entityDataRoot, "Owner")) != null
                    || unwrap(COMPOUND_GET.invoke(entityDataRoot, "owner")) != null;
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    /**
     * Returns true when any compound in the tree carries a {@code click_event} tag,
     * which can make clients execute commands from item or block NBT text.
     */
    static boolean containsClickEvent(Object node)
    {
        if (!isAvailable() || node == null || !COMPOUND_TAG.equals(node.getClass().getName()))
        {
            return false;
        }
        try
        {
            for (Object keyObj : (Iterable<?>) COMPOUND_KEYS.invoke(node))
            {
                String key = keyObj.toString();
                if ("click_event".equals(key))
                {
                    return true;
                }
                Object child = unwrap(COMPOUND_GET.invoke(node, key));
                if ("hover_event".equals(key) && isDangerousHover(child))
                {
                    return true;
                }
                if (child != null && COMPOUND_TAG.equals(child.getClass().getName())
                        && containsClickEvent(child))
                {
                    return true;
                }
            }
        }
        catch (Throwable ignored)
        {
        }
        return false;
    }

    private static boolean isDangerousHover(Object hoverTag)
    {
        if (hoverTag == null)
        {
            return false;
        }
        if (!COMPOUND_TAG.equals(hoverTag.getClass().getName()))
        {
            return true;
        }
        String action;
        try
        {
            action = getString(hoverTag, "action");
        }
        catch (Throwable t)
        {
            return true;
        }
        if (action == null || action.isEmpty())
        {
            return true;
        }
        String normalized = action.toLowerCase(Locale.ROOT);
        return !normalized.equals("show_text") && !normalized.equals("minecraft:show_text");
    }

    /**
     * Recursively scan nested compounds for malformed int-array list encodings.
     */
    static Violation checkNested(Object node)
    {
        if (!isAvailable() || node == null)
        {
            return null;
        }
        if (!COMPOUND_TAG.equals(node.getClass().getName()))
        {
            return null;
        }
        try
        {
            Violation direct = checkIntArrayFields(node);
            if (direct != null)
            {
                return direct;
            }
            Violation typed = checkTypedNumericFields(node);
            if (typed != null)
            {
                return typed;
            }
            for (Object keyObj : (Iterable<?>) COMPOUND_KEYS.invoke(node))
            {
                String key = keyObj.toString();
                Object child = unwrap(COMPOUND_GET.invoke(node, key));
                if (child != null && COMPOUND_TAG.equals(child.getClass().getName()))
                {
                    Violation nested = checkNested(child);
                    if (nested != null)
                    {
                        return nested;
                    }
                }
            }
        }
        catch (Throwable ignored)
        {
            return null;
        }
        return null;
    }

    private static Violation checkIntArrayFields(Object compound) throws Exception
    {
        for (String key : INT_ARRAY_FIELD_NAMES)
        {
            Object tag = unwrap(COMPOUND_GET.invoke(compound, key));
            if (tag != null && isMalformedIntArrayList(tag))
            {
                return Violation.MALFORMED_INT_ARRAY_FIELD;
            }
        }
        return null;
    }

    private static Violation checkTypedNumericFields(Object compound) throws Exception
    {
        for (String key : TYPED_NUMERIC_FIELDS)
        {
            Object tag = unwrap(COMPOUND_GET.invoke(compound, key));
            if (tag != null && !isNumericTag(tag))
            {
                return Violation.MALFORMED_TYPED_FIELD;
            }
        }
        return null;
    }

    private static boolean isNumericTag(Object tag)
    {
        if (tag == null)
        {
            return false;
        }
        String name = tag.getClass().getName();
        if (NUMERIC_TAG_NAMES.contains(name))
        {
            return true;
        }
        // 1.21.5+ may expose a shared numeric supertype.
        for (Class<?> type = tag.getClass(); type != null && type != Object.class; type = type.getSuperclass())
        {
            if ("net.minecraft.nbt.NumericTag".equals(type.getName()))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean isMalformedIntArrayList(Object tag) throws Exception
    {
        if (tag == null || !LIST_TAG.equals(tag.getClass().getName()))
        {
            return false;
        }
        int size = (int) LIST_SIZE.invoke(tag);
        if (size < 4)
        {
            return false;
        }
        // first element is "I" is the malformed encoding that crashes the reader.
        Object first = unwrap(LIST_GET.invoke(tag, 0));
        if (first == null || !STRING_TAG.equals(first.getClass().getName()))
        {
            return false;
        }
        Object value = unwrap(STRING_VALUE.invoke(first));
        return "I".equals(value);
    }

    private static String getString(Object compound, String key) throws Exception
    {
        Object value = unwrap(COMPOUND_GET_STRING.invoke(compound, key));
        return value instanceof String s ? s : null;
    }

    private static String expectedSpawnEggEntityId(Material material)
    {
        String suffix = "_SPAWN_EGG";
        String name = material.name();
        if (!name.endsWith(suffix))
        {
            return null;
        }
        String base = name.substring(0, name.length() - suffix.length()).toLowerCase(Locale.ROOT);
        return "minecraft:" + base;
    }

    private static Object unwrap(Object value)
    {
        return value instanceof Optional<?> optional ? optional.orElse(null) : value;
    }

    private static Method method(Class<?> owner, String name, Class<?>... params)
    {
        try
        {
            Method method = owner.getMethod(name, params);
            method.setAccessible(true);
            return method;
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    private static Method firstMethod(Class<?> owner, String... names)
    {
        for (String name : names)
        {
            Method method = method(owner, name);
            if (method != null)
            {
                return method;
            }
        }
        return null;
    }
}

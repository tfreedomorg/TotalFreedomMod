package me.totalfreedom.totalfreedommod.blocking.item;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Bounded, codec-free inspection of an item's raw NBT components
 * ({@code minecraft:custom_data}, {@code minecraft:block_entity_data},
 * {@code minecraft:entity_data}, and {@code minecraft:bucket_entity_data}).
 */
final class RawNbtInspector
{

    enum Result
    {
        CLEAN,
        OVERSIZED,
        MALFORMED,
        ERROR,
        UNAVAILABLE
    }

    private static final String NBT_PACKAGE = "net.minecraft.nbt.";
    private static final String COMPOUND_TAG = "net.minecraft.nbt.CompoundTag";

    private static final boolean AVAILABLE;
    private static final Method AS_NMS_COPY;
    private static final Method AS_BUKKIT_COPY;
    private static final Method ITEMSTACK_GET;
    private static final Method ITEMSTACK_REMOVE;
    private static final Object CUSTOM_DATA_TYPE;
    private static final Object BLOCK_ENTITY_DATA_TYPE;
    private static final Object ENTITY_DATA_TYPE;
    private static final Object BUCKET_ENTITY_DATA_TYPE;
    private static final Class<?> CUSTOM_DATA_CLASS;
    private static final Field CUSTOM_DATA_TAG_FIELD;
    private static final Method TYPED_ENTITY_TAG_METHOD;
    private static final Field TYPED_ENTITY_TAG_FIELD;

    private static final Map<Class<?>, Field[]> FIELD_CACHE = new ConcurrentHashMap<>();

    static
    {
        boolean ok = false;
        Method asNmsCopy = null;
        Method asBukkitCopy = null;
        Method itemStackGet = null;
        Method itemStackRemove = null;
        Object customDataType = null;
        Object blockEntityDataType = null;
        Object entityDataType = null;
        Object bucketEntityDataType = null;
        Class<?> customDataClass = null;
        Field customDataTagField = null;
        Method typedEntityTagMethod = null;
        Field typedEntityTagField = null;

        try
        {
            String craftBase = org.bukkit.Bukkit.getServer().getClass().getPackage().getName();
            Class<?> craftItemStack = Class.forName(craftBase + ".inventory.CraftItemStack");
            asNmsCopy = craftItemStack.getMethod("asNMSCopy", ItemStack.class);
            asNmsCopy.setAccessible(true);

            Class<?> nmsItemStack = asNmsCopy.getReturnType();
            Class<?> dataComponentType = Class.forName("net.minecraft.core.component.DataComponentType");
            itemStackGet = nmsItemStack.getMethod("get", dataComponentType);
            itemStackGet.setAccessible(true);

            try
            {
                asBukkitCopy = craftItemStack.getMethod("asBukkitCopy", nmsItemStack);
                asBukkitCopy.setAccessible(true);
                itemStackRemove = nmsItemStack.getMethod("remove", dataComponentType);
                itemStackRemove.setAccessible(true);
            }
            catch (Throwable ignored)
            {
                asBukkitCopy = null;
                itemStackRemove = null;
            }

            Class<?> dataComponents = Class.forName("net.minecraft.core.component.DataComponents");
            customDataType = dataComponents.getField("CUSTOM_DATA").get(null);
            blockEntityDataType = dataComponents.getField("BLOCK_ENTITY_DATA").get(null);
            entityDataType = dataComponents.getField("ENTITY_DATA").get(null);
            bucketEntityDataType = dataComponents.getField("BUCKET_ENTITY_DATA").get(null);

            customDataClass = Class.forName("net.minecraft.world.item.component.CustomData");
            for (Field f : customDataClass.getDeclaredFields())
            {
                if (f.getType().getName().equals(COMPOUND_TAG))
                {
                    f.setAccessible(true);
                    customDataTagField = f;
                    break;
                }
            }

            Class<?> typedEntityData = Class.forName("net.minecraft.world.item.component.TypedEntityData");
            for (String methodName : new String[]{"copyTagWithEntityId", "tag", "getUnsafe"})
            {
                try
                {
                    Method method = typedEntityData.getMethod(methodName);
                    if (COMPOUND_TAG.equals(method.getReturnType().getName()))
                    {
                        method.setAccessible(true);
                        typedEntityTagMethod = method;
                        break;
                    }
                }
                catch (NoSuchMethodException ignored)
                {
                }
            }
            if (typedEntityTagMethod == null)
            {
                for (Field field : typedEntityData.getDeclaredFields())
                {
                    if (COMPOUND_TAG.equals(field.getType().getName()))
                    {
                        field.setAccessible(true);
                        typedEntityTagField = field;
                        break;
                    }
                }
            }

            ok = customDataType != null
                    && blockEntityDataType != null
                    && entityDataType != null
                    && bucketEntityDataType != null
                    && customDataTagField != null
                    && (typedEntityTagMethod != null || typedEntityTagField != null);
        }
        catch (Throwable ignored)
        {
            ok = false;
        }

        AVAILABLE = ok;
        AS_NMS_COPY = asNmsCopy;
        AS_BUKKIT_COPY = asBukkitCopy;
        ITEMSTACK_GET = itemStackGet;
        ITEMSTACK_REMOVE = itemStackRemove;
        CUSTOM_DATA_TYPE = customDataType;
        BLOCK_ENTITY_DATA_TYPE = blockEntityDataType;
        ENTITY_DATA_TYPE = entityDataType;
        BUCKET_ENTITY_DATA_TYPE = bucketEntityDataType;
        CUSTOM_DATA_CLASS = customDataClass;
        CUSTOM_DATA_TAG_FIELD = customDataTagField;
        TYPED_ENTITY_TAG_METHOD = typedEntityTagMethod;
        TYPED_ENTITY_TAG_FIELD = typedEntityTagField;
    }

    private RawNbtInspector()
    {
    }

    static boolean isAvailable()
    {
        return AVAILABLE;
    }

    static boolean hasEntityOrBucketData(ItemStack item)
    {
        if (!AVAILABLE || item == null || item.isEmpty())
        {
            return false;
        }
        try
        {
            Object nms = AS_NMS_COPY.invoke(null, item);
            if (nms == null)
            {
                return false;
            }
            return ITEMSTACK_GET.invoke(nms, ENTITY_DATA_TYPE) != null
                    || ITEMSTACK_GET.invoke(nms, BUCKET_ENTITY_DATA_TYPE) != null;
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    static boolean hasEntityDataOwner(ItemStack item)
    {
        if (!AVAILABLE || item == null || item.isEmpty())
        {
            return false;
        }
        try
        {
            Object nms = AS_NMS_COPY.invoke(null, item);
            if (nms == null)
            {
                return false;
            }
            Object typed = ITEMSTACK_GET.invoke(nms, ENTITY_DATA_TYPE);
            if (typed == null)
            {
                return false;
            }
            return EntityDataRules.hasOwner(extractTypedEntityTag(typed));
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    static ItemStack stripEntityData(ItemStack item)
    {
        if (!AVAILABLE || ITEMSTACK_REMOVE == null || AS_BUKKIT_COPY == null || item == null)
        {
            return item;
        }
        try
        {
            Object nms = AS_NMS_COPY.invoke(null, item);
            if (nms == null)
            {
                return item;
            }
            ITEMSTACK_REMOVE.invoke(nms, ENTITY_DATA_TYPE);
            Object bukkit = AS_BUKKIT_COPY.invoke(null, nms);
            return bukkit instanceof ItemStack stack ? stack : item;
        }
        catch (Throwable ignored)
        {
            return item;
        }
    }

    /**
     * @param maxNodes hard cap on total tag nodes (compounds, list entries,
     *                 collection/array elements) before the item is rejected
     * @param maxDepth hard cap on nesting depth; also bounds recursion so a
     *                 depth-bomb cannot overflow the stack
     */
    static Result inspect(ItemStack item, int maxNodes, int maxDepth)
    {
        if (!AVAILABLE)
        {
            return Result.UNAVAILABLE;
        }
        try
        {
            Object nms = AS_NMS_COPY.invoke(null, item);
            if (nms == null)
            {
                return Result.CLEAN;
            }
            Material material = item.getType();

            if (overBudgetCustomData(ITEMSTACK_GET.invoke(nms, CUSTOM_DATA_TYPE), maxNodes, maxDepth)
                    || overBudgetCustomData(ITEMSTACK_GET.invoke(nms, BLOCK_ENTITY_DATA_TYPE), maxNodes, maxDepth))
            {
                return Result.OVERSIZED;
            }

            Result entityResult = inspectTypedEntityData(
                    ITEMSTACK_GET.invoke(nms, ENTITY_DATA_TYPE), material, maxNodes, maxDepth);
            if (entityResult != Result.CLEAN)
            {
                return entityResult;
            }

            Result bucketResult = inspectTypedEntityData(
                    ITEMSTACK_GET.invoke(nms, BUCKET_ENTITY_DATA_TYPE), material, maxNodes, maxDepth);
            if (bucketResult != Result.CLEAN)
            {
                return bucketResult;
            }

            return Result.CLEAN;
        }
        catch (Throwable t)
        {
            return Result.ERROR;
        }
    }

    private static Result inspectTypedEntityData(Object typedEntityData, Material material, int maxNodes, int maxDepth)
            throws Exception
    {
        if (typedEntityData == null)
        {
            return Result.CLEAN;
        }
        Object tag = extractTypedEntityTag(typedEntityData);
        if (tag == null)
        {
            return Result.CLEAN;
        }
        if (overBudgetTag(tag, maxNodes, maxDepth))
        {
            return Result.OVERSIZED;
        }
        if (EntityDataRules.isAvailable())
        {
            EntityDataRules.Violation root = EntityDataRules.checkRoot(tag, material);
            if (root != null)
            {
                return Result.MALFORMED;
            }
            EntityDataRules.Violation nested = EntityDataRules.checkNested(tag);
            if (nested != null)
            {
                return Result.MALFORMED;
            }
        }
        return Result.CLEAN;
    }

    private static boolean overBudgetCustomData(Object customData, int maxNodes, int maxDepth) throws IllegalAccessException
    {
        if (customData == null)
        {
            return false;
        }
        Object tag = CUSTOM_DATA_TAG_FIELD.get(customData);
        return overBudgetTag(tag, maxNodes, maxDepth);
    }

    private static boolean overBudgetTag(Object tag, int maxNodes, int maxDepth)
    {
        if (tag == null)
        {
            return false;
        }
        Budget budget = new Budget(maxNodes, maxDepth);
        return !walk(tag, 0, budget);
    }

    private static Object extractTypedEntityTag(Object typedEntityData) throws IllegalAccessException, java.lang.reflect.InvocationTargetException
    {
        if (typedEntityData == null)
        {
            return null;
        }
        // Need this so every fish bucket isn't UNINSPECTABLE_NBT; every entity-bucket item carries
        // an empty CustomData, which the TypedEntityData accessor throws on
        if (CUSTOM_DATA_CLASS != null && CUSTOM_DATA_CLASS.isInstance(typedEntityData))
        {
            return CUSTOM_DATA_TAG_FIELD != null ? CUSTOM_DATA_TAG_FIELD.get(typedEntityData) : null;
        }
        if (TYPED_ENTITY_TAG_METHOD != null)
        {
            return TYPED_ENTITY_TAG_METHOD.invoke(typedEntityData);
        }
        if (TYPED_ENTITY_TAG_FIELD != null)
        {
            return TYPED_ENTITY_TAG_FIELD.get(typedEntityData);
        }
        return null;
    }

    private static boolean walk(Object node, int depth, Budget budget)
    {
        if (node == null)
        {
            return true;
        }
        if (depth > budget.maxDepth)
        {
            return false;
        }
        if (++budget.nodes > budget.maxNodes)
        {
            return false;
        }
        if (COMPOUND_TAG.equals(node.getClass().getName())
                && EntityDataRules.isAvailable()
                && EntityDataRules.containsClickEvent(node))
        {
            return false;
        }

        if (node instanceof Map<?, ?> map)
        {
            return walkValues(map.values(), map.size(), depth, budget);
        }
        if (node instanceof Collection<?> collection)
        {
            return walkValues(collection, collection.size(), depth, budget);
        }
        if (node instanceof CharSequence text)
        {
            budget.nodes += text.length();
            return budget.nodes <= budget.maxNodes;
        }

        for (Field field : tagFields(node.getClass()))
        {
            Object value;
            try
            {
                value = field.get(node);
            }
            catch (Throwable ignored)
            {
                continue;
            }
            if (value == null)
            {
                continue;
            }
            if (value instanceof Map<?, ?> map)
            {
                if (!walkValues(map.values(), map.size(), depth, budget))
                {
                    return false;
                }
            }
            else if (value instanceof Collection<?> collection)
            {
                if (!walkValues(collection, collection.size(), depth, budget))
                {
                    return false;
                }
            }
            else if (value.getClass().isArray())
            {
                budget.nodes += Array.getLength(value);
                if (budget.nodes > budget.maxNodes)
                {
                    return false;
                }
            }
            else if (value instanceof CharSequence text)
            {
                budget.nodes += text.length();
                if (budget.nodes > budget.maxNodes)
                {
                    return false;
                }
            }
            else if (isTag(value))
            {
                if (!walk(value, depth + 1, budget))
                {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean walkValues(Collection<?> values, int size, int depth, Budget budget)
    {
        budget.nodes += size;
        if (budget.nodes > budget.maxNodes)
        {
            return false;
        }
        for (Object value : values)
        {
            if (isTag(value) && !walk(value, depth + 1, budget))
            {
                return false;
            }
        }
        return true;
    }

    private static boolean isTag(Object value)
    {
        return value != null && value.getClass().getName().startsWith(NBT_PACKAGE);
    }

    private static Field[] tagFields(Class<?> type)
    {
        return FIELD_CACHE.computeIfAbsent(type, RawNbtInspector::collectFields);
    }

    private static Field[] collectFields(Class<?> type)
    {
        ArrayList<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass())
        {
            for (Field f : c.getDeclaredFields())
            {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive())
                {
                    continue;
                }
                try
                {
                    f.setAccessible(true);
                    fields.add(f);
                }
                catch (Throwable ignored)
                {
                    // Inaccessible field (module restriction); skip it.
                }
            }
        }
        return fields.toArray(new Field[0]);
    }

    private static final class Budget
    {
        private final int maxNodes;
        private final int maxDepth;
        private int nodes;

        private Budget(int maxNodes, int maxDepth)
        {
            this.maxNodes = maxNodes;
            this.maxDepth = maxDepth;
        }
    }
}

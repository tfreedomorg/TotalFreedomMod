package me.totalfreedom.totalfreedommod.blocking.item;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BundleContents;
import io.papermc.paper.datacomponent.item.ChargedProjectiles;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.PotionContents;
import io.papermc.paper.datacomponent.item.SuspiciousStewEffects;
import io.papermc.paper.datacomponent.item.WritableBookContent;
import io.papermc.paper.datacomponent.item.WrittenBookContent;
import io.papermc.paper.text.Filtered;
import java.util.List;
import me.totalfreedom.totalfreedommod.util.ComponentScanner;
import net.kyori.adventure.text.Component;
import org.bukkit.damage.DamageType;
import org.bukkit.inventory.ItemStack;

final class ItemScanner
{

    private static final int MAX_CONTAINER_DEPTH = 2;
    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_LORE_LINES = 24;
    private static final int MAX_LORE_LINE_LENGTH = 256;
    private static final int MAX_COMPONENT_NODES = 1024;
    private static final int MAX_NBT_NODES = 8192;
    private static final int MAX_NBT_DEPTH = 16;

    private ItemScanner()
    {
    }

    enum Reason
    {
        CLEAN,
        CONTAINER_TOO_DEEP,
        OVERSIZED_NAME,
        OVERSIZED_LORE,
        OVERSIZED_TOTAL,
        OVERSIZED_NBT,
        OVERSIZED_POTION,
        UNINSPECTABLE_NBT,
        MALFORMED_ENTITY_DATA,
        CURSED_COMPONENT,
        PANIC_BLANKET_REJECT
    }

    record Verdict(Reason reason, long observedSize, int depth)
    {
        static final Verdict CLEAN = new Verdict(Reason.CLEAN, 0L, 0);

        boolean isCursed()
        {
            return reason != Reason.CLEAN;
        }
    }

    static Verdict scan(ItemStack item, boolean panicMode, int maxPotionEffects)
    {
        return scan(item, panicMode, maxPotionEffects, 0);
    }

    private static Verdict scan(ItemStack item, boolean panicMode, int maxPotionEffects, int depth)
    {
        if (item == null || item.isEmpty())
        {
            return Verdict.CLEAN;
        }

        if (depth > MAX_CONTAINER_DEPTH)
        {
            return new Verdict(Reason.CONTAINER_TOO_DEEP, 0L, depth);
        }

        if (panicMode)
        {
            if (item.hasData(DataComponentTypes.CONTAINER)
                    || item.hasData(DataComponentTypes.BUNDLE_CONTENTS)
                    || item.hasData(DataComponentTypes.CONTAINER_LOOT)
                    || item.hasData(DataComponentTypes.CHARGED_PROJECTILES)
                    || RawNbtInspector.hasEntityOrBucketData(item))
            {
                return new Verdict(Reason.PANIC_BLANKET_REJECT, 0L, depth);
            }
        }

        // POTION_CONTENTS / SUSPICIOUS_STEW_EFFECTS — status-effect spam bombs.
        Verdict effects = inspectEffectBearer(item, maxPotionEffects, depth);
        if (effects.isCursed())
        {
            return effects;
        }

        // CUSTOM_NAME — gate the serializer behind a safe-graph check
        if (item.hasData(DataComponentTypes.CUSTOM_NAME))
        {
            Component name = item.getData(DataComponentTypes.CUSTOM_NAME);
            Verdict nameVerdict = inspectNamedComponent(name, depth);
            if (nameVerdict.isCursed())
            {
                return nameVerdict;
            }
        }

        // LORE — same gating per line
        if (item.hasData(DataComponentTypes.LORE))
        {
            ItemLore lore = item.getData(DataComponentTypes.LORE);
            if (lore != null)
            {
                List<Component> lines = lore.lines();
                if (lines.size() > MAX_LORE_LINES)
                {
                    return new Verdict(Reason.OVERSIZED_LORE, lines.size(), depth);
                }
                for (Component line : lines)
                {
                    Verdict lineVerdict = inspectLoreLine(line, depth);
                    if (lineVerdict.isCursed())
                    {
                        return lineVerdict;
                    }
                }
            }
        }

        if (item.hasData(DataComponentTypes.WRITTEN_BOOK_CONTENT))
        {
            WrittenBookContent book = item.getData(DataComponentTypes.WRITTEN_BOOK_CONTENT);
            if (book != null)
            {
                for (Filtered<Component> page : book.pages())
                {
                    Component raw = page != null ? page.raw() : null;
                    if (ComponentScanner.isCursed(raw, MAX_COMPONENT_NODES))
                    {
                        return new Verdict(Reason.CURSED_COMPONENT, -1L, depth);
                    }
                }
            }
        }

        if (item.hasData(DataComponentTypes.WRITABLE_BOOK_CONTENT))
        {
            WritableBookContent book = item.getData(DataComponentTypes.WRITABLE_BOOK_CONTENT);
            if (book != null)
            {
                for (Filtered<String> page : book.pages())
                {
                    String raw = page != null ? page.raw() : null;
                    if (isCursedWritablePage(raw))
                    {
                        return new Verdict(Reason.CURSED_COMPONENT, -1L, depth);
                    }
                }
            }
        }

        // CONTAINER recursion — primary vector for shulker-in-shulker hang
        if (item.hasData(DataComponentTypes.CONTAINER))
        {
            ItemContainerContents container = item.getData(DataComponentTypes.CONTAINER);
            if (container != null)
            {
                for (ItemStack inner : container.contents())
                {
                    Verdict v = scan(inner, panicMode, maxPotionEffects, depth + 1);
                    if (v.isCursed())
                    {
                        return v;
                    }
                }
            }
        }

        // BUNDLE recursion
        if (item.hasData(DataComponentTypes.BUNDLE_CONTENTS))
        {
            BundleContents bundle = item.getData(DataComponentTypes.BUNDLE_CONTENTS);
            if (bundle != null)
            {
                for (ItemStack inner : bundle.contents())
                {
                    Verdict v = scan(inner, panicMode, maxPotionEffects, depth + 1);
                    if (v.isCursed())
                    {
                        return v;
                    }
                }
            }
        }

        if (item.hasData(DataComponentTypes.CHARGED_PROJECTILES))
        {
            ChargedProjectiles charged = item.getData(DataComponentTypes.CHARGED_PROJECTILES);
            if (charged != null)
            {
                for (ItemStack projectile : charged.projectiles())
                {
                    Verdict v = scan(projectile, panicMode, maxPotionEffects, depth + 1);
                    if (v.isCursed())
                    {
                        return v;
                    }
                }
            }
        }

        if(item.hasData(DataComponentTypes.DAMAGE_TYPE))
        {
            DamageType type = item.getData(DataComponentTypes.DAMAGE_TYPE);

            Verdict v = inspectDamageType(type, depth);
            if (v.isCursed())
            {
                return v;
            }
        }

        switch (RawNbtInspector.inspect(item, MAX_NBT_NODES, MAX_NBT_DEPTH))
        {
            case OVERSIZED -> {
                return new Verdict(Reason.OVERSIZED_NBT, MAX_NBT_NODES, depth);
            }
            case MALFORMED -> {
                return new Verdict(Reason.MALFORMED_ENTITY_DATA, -1L, depth);
            }
            case ERROR -> {
                return new Verdict(Reason.UNINSPECTABLE_NBT, -1L, depth);
            }
            default -> {
            }
        }

        return Verdict.CLEAN;
    }

    private static Verdict inspectEffectBearer(ItemStack item, int maxPotionEffects, int depth)
    {
        if (maxPotionEffects <= 0)
        {
            return Verdict.CLEAN;
        }

        if (item.hasData(DataComponentTypes.POTION_CONTENTS))
        {
            PotionContents contents = item.getData(DataComponentTypes.POTION_CONTENTS);
            if (contents != null)
            {
                int count = contents.customEffects().size();
                if (count > maxPotionEffects)
                {
                    return new Verdict(Reason.OVERSIZED_POTION, count, depth);
                }
            }
        }

        if (item.hasData(DataComponentTypes.SUSPICIOUS_STEW_EFFECTS))
        {
            SuspiciousStewEffects stew = item.getData(DataComponentTypes.SUSPICIOUS_STEW_EFFECTS);
            if (stew != null)
            {
                int count = stew.effects().size();
                if (count > maxPotionEffects)
                {
                    return new Verdict(Reason.OVERSIZED_POTION, count, depth);
                }
            }
        }

        return Verdict.CLEAN;
    }

    private static Verdict inspectNamedComponent(Component name, int depth)
    {
        if (name == null)
        {
            return Verdict.CLEAN;
        }
        if (ComponentScanner.isCursed(name, MAX_COMPONENT_NODES))
        {
            return new Verdict(Reason.CURSED_COMPONENT, -1L, depth);
        }
        int len = ComponentScanner.safePlainTextLength(name, MAX_COMPONENT_NODES);
        if (len > MAX_NAME_LENGTH)
        {
            return new Verdict(Reason.OVERSIZED_NAME, len, depth);
        }
        return Verdict.CLEAN;
    }

    private static Verdict inspectLoreLine(Component line, int depth)
    {
        if (line == null)
        {
            return Verdict.CLEAN;
        }
        if (ComponentScanner.isCursed(line, MAX_COMPONENT_NODES))
        {
            return new Verdict(Reason.CURSED_COMPONENT, -1L, depth);
        }
        int len = ComponentScanner.safePlainTextLength(line, MAX_COMPONENT_NODES);
        if (len > MAX_LORE_LINE_LENGTH)
        {
            return new Verdict(Reason.OVERSIZED_LORE, len, depth);
        }
        return Verdict.CLEAN;
    }

    private static Verdict inspectDamageType(DamageType type, int depth)
    {
        if(type != null)
        {
            // DamageType GENERIC_KILL and OUT_OF_WORLD can be used to kill people in creative mode
            if (type == DamageType.GENERIC_KILL || type == DamageType.OUT_OF_WORLD)
            {
                return new Verdict(Reason.CURSED_COMPONENT, -1L, depth);
            }
        }
        return Verdict.CLEAN;
    }

    private static boolean isCursedWritablePage(String page)
    {
        if (page == null || page.isEmpty())
        {
            return false;
        }
        return page.indexOf("click_event") >= 0;
    }
}

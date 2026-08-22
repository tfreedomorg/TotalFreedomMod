package me.totalfreedom.totalfreedommod.blocking.item;

import java.util.List;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.*;
import io.papermc.paper.datacomponent.item.attribute.AttributeModifierDisplay;
import io.papermc.paper.datacomponent.item.blocksattacks.DamageReduction;
import io.papermc.paper.registry.set.RegistryKeySet;
import io.papermc.paper.text.Filtered;
import org.bukkit.FireworkEffect;
import org.bukkit.JukeboxSong;
import org.bukkit.MusicInstrument;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.banner.PatternType;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;

import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.ComponentScanner;

final class ItemScanner
{

    private static final int MAX_CONTAINER_DEPTH = 2;
    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_LORE_LINES = 24;
    private static final int MAX_LORE_LINE_LENGTH = 256;
    private static final int MAX_BOOK_PAGE_LENGTH = 1024;
    private static final int MAX_BOOK_PAGES = 100;
    private static final int MAX_INSTRUMENT_DESC_LENGTH = 256;
    private static final long MAX_AGGREGATE_TEXT = 262_144L;
    private static final double MAX_ITEM_SCALE = 4.0;
    private static final double MAX_WAYPOINT_RANGE = 16_384.0;
    private static final int MAX_FIREWORK_EXPLOSIONS = 64;
    private static final int MAX_FIREWORK_COLORS = 256;
    private static final int MAX_BANNER_PATTERNS = 256;
    private static final int MAX_EQUIPPABLE_ENTITIES = 256;
    private static final int MAX_CUSTOM_MODEL_DATA_ENTRIES = 256;
    private static final int MAX_CUSTOM_MODEL_DATA_STRING_LENGTH = 256;
    private static final int MAX_ATTRIBUTE_MODIFIERS = 64;
    private static final int MAX_DAMAGE_REDUCTIONS = 64;
    private static final int MAX_DAMAGE_TYPE_SET = 256;
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
        OVERSIZED_AGGREGATE,
        OVERSIZED_TOTAL,
        OVERSIZED_NBT,
        OVERSIZED_POTION,
        OVERSIZED_FIREWORK,
        OVERSIZED_BANNER_PATTERNS,
        OVERSIZED_EQUIPPABLE,
        OVERSIZED_CUSTOM_MODEL_DATA,
        OVERSIZED_BLOCKS_ATTACKS,
        OVERSIZED_BOOK,
        OVERSIZED_ATTRIBUTE,
        OVERSIZED_ATTRIBUTE_COUNT,
        ILLEGAL_STACK_SIZE,
        UNINSPECTABLE_NBT,
        MALFORMED_ENTITY_DATA,
        CURSED_COMPONENT,
        CURSED_EQUIP_SOUND,
        SCAN_BUDGET_EXCEEDED,
        PANIC_BLANKET_REJECT;

        boolean isHangClass()
        {
            return switch (this)
            {
                case CLEAN, OVERSIZED_NAME, OVERSIZED_LORE, OVERSIZED_BOOK, CURSED_EQUIP_SOUND,
                     OVERSIZED_ATTRIBUTE_COUNT -> false;
                default -> true;
            };
        }
    }

    record Verdict(Reason reason, long observedSize, int depth)
    {
        static final Verdict CLEAN = new Verdict(Reason.CLEAN, 0L, 0);

        boolean isCursed()
        {
            return reason != Reason.CLEAN;
        }
    }

    /**
     * Running total of display text summed across an item and its nested
     * contents, shared by every level of a single {@link #scan} so a container's
     * children are measured against one budget rather than each in isolation.
     */
    private static final class Aggregate
    {
        private long text;
    }

    static Verdict scan(ItemStack item, boolean panicMode, int maxPotionEffects)
    {
        return scan(item, panicMode, maxPotionEffects, 0L);
    }

    static Verdict scan(ItemStack item, boolean panicMode, int maxPotionEffects, long deadlineNanos)
    {
        return scan(item, panicMode, maxPotionEffects, deadlineNanos, 0, new Aggregate());
    }

    private static Verdict scan(ItemStack item, boolean panicMode, int maxPotionEffects, long deadlineNanos, int depth,
            Aggregate agg)
    {
        if (budgetExceeded(deadlineNanos))
        {
            return new Verdict(Reason.SCAN_BUDGET_EXCEEDED, 0L, depth);
        }
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
                    || item.hasData(DataComponentTypes.USE_REMAINDER)
                    || RawNbtInspector.hasEntityOrBucketData(item))
            {
                return new Verdict(Reason.PANIC_BLANKET_REJECT, 0L, depth);
            }
        }

        Verdict stackSize = inspectStackSize(item, depth);
        if (stackSize.isCursed())
        {
            return stackSize;
        }

        // POTION_CONTENTS / SUSPICIOUS_STEW_EFFECTS — status-effect spam bombs.
        Verdict effects = inspectEffectBearer(item, maxPotionEffects, depth);
        if (effects.isCursed())
        {
            return effects;
        }

        Verdict firework = inspectFirework(item, depth);
        if (firework.isCursed())
        {
            return firework;
        }

        Verdict banner = inspectBannerPatterns(item, depth);
        if (banner.isCursed())
        {
            return banner;
        }

        Verdict equippable = inspectEquippable(item, depth);
        if (equippable.isCursed())
        {
            return equippable;
        }

        Verdict customModelData = inspectCustomModelData(item, depth);
        if (customModelData.isCursed())
        {
            return customModelData;
        }

        Verdict blocksAttacks = inspectBlocksAttacks(item, depth);
        if (blocksAttacks.isCursed())
        {
            return blocksAttacks;
        }

        // CUSTOM_NAME / ITEM_NAME — gate the serializer behind a safe-graph check
        if (item.hasData(DataComponentTypes.CUSTOM_NAME))
        {
            Component name = item.getData(DataComponentTypes.CUSTOM_NAME);
            Verdict nameVerdict = inspectNamedComponent(name, depth, deadlineNanos, agg);
            if (nameVerdict.isCursed())
            {
                return nameVerdict;
            }
        }
        if (item.hasData(DataComponentTypes.ITEM_NAME))
        {
            Component name = item.getData(DataComponentTypes.ITEM_NAME);
            Verdict nameVerdict = inspectNamedComponent(name, depth, deadlineNanos, agg);
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
                    Verdict lineVerdict = inspectLoreLine(line, depth, deadlineNanos, agg);
                    if (lineVerdict.isCursed())
                    {
                        return lineVerdict;
                    }
                }
            }
        }

        Verdict bookVerdict = inspectBookContent(item, depth, deadlineNanos, agg);
        if (bookVerdict.isCursed())
        {
            return bookVerdict;
        }

        Verdict attributeVerdict = inspectAttributeModifiers(item, depth, deadlineNanos, agg);
        if (attributeVerdict.isCursed())
        {
            return attributeVerdict;
        }

        Verdict instrumentVerdict = inspectInstrument(item, depth, deadlineNanos, agg);
        if (instrumentVerdict.isCursed())
        {
            return instrumentVerdict;
        }

        Verdict jukeboxVerdict = inspectJukebox(item, depth, deadlineNanos, agg);
        if (jukeboxVerdict.isCursed())
        {
            return jukeboxVerdict;
        }

        if (agg.text > MAX_AGGREGATE_TEXT)
        {
            return new Verdict(Reason.OVERSIZED_AGGREGATE, agg.text, depth);
        }

        // CONTAINER recursion — primary vector for shulker-in-shulker hang
        if (item.hasData(DataComponentTypes.CONTAINER))
        {
            ItemContainerContents container = item.getData(DataComponentTypes.CONTAINER);
            if (container != null)
            {
                for (ItemStack inner : container.contents())
                {
                    Verdict v = scan(inner, panicMode, maxPotionEffects, deadlineNanos, depth + 1, agg);
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
                    Verdict v = scan(inner, panicMode, maxPotionEffects, deadlineNanos, depth + 1, agg);
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
                    Verdict v = scan(projectile, panicMode, maxPotionEffects, deadlineNanos, depth + 1, agg);
                    if (v.isCursed())
                    {
                        return v;
                    }
                }
            }
        }

        if (item.hasData(DataComponentTypes.USE_REMAINDER))
        {
            UseRemainder remainder = item.getData(DataComponentTypes.USE_REMAINDER);
            if (remainder != null)
            {
                Verdict v = scan(remainder.transformInto(), panicMode, maxPotionEffects, deadlineNanos, depth + 1, agg);
                if (v.isCursed())
                {
                    return v;
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

    private static Verdict inspectStackSize(ItemStack item, int depth)
    {
        if (item.getType().getMaxDurability() <= 0)
        {
            return Verdict.CLEAN;
        }
        if (!item.hasData(DataComponentTypes.MAX_STACK_SIZE))
        {
            return Verdict.CLEAN;
        }
        Integer maxStackSize = item.getData(DataComponentTypes.MAX_STACK_SIZE);
        if (maxStackSize != null && maxStackSize > 1)
        {
            return new Verdict(Reason.ILLEGAL_STACK_SIZE, maxStackSize, depth);
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

    private static Verdict inspectFirework(ItemStack item, int depth)
    {
        if (item.hasData(DataComponentTypes.FIREWORKS))
        {
            Fireworks fireworks = item.getData(DataComponentTypes.FIREWORKS);
            if (fireworks != null)
            {
                List<FireworkEffect> explosions = fireworks.effects();
                if (explosions.size() > MAX_FIREWORK_EXPLOSIONS)
                {
                    return new Verdict(Reason.OVERSIZED_FIREWORK, explosions.size(), depth);
                }
                for (FireworkEffect explosion : explosions)
                {
                    Verdict v = inspectFireworkEffect(explosion, depth);
                    if (v.isCursed())
                    {
                        return v;
                    }
                }
            }
        }

        if (item.hasData(DataComponentTypes.FIREWORK_EXPLOSION))
        {
            return inspectFireworkEffect(item.getData(DataComponentTypes.FIREWORK_EXPLOSION), depth);
        }

        return Verdict.CLEAN;
    }

    private static Verdict inspectFireworkEffect(FireworkEffect effect, int depth)
    {
        if (effect == null)
        {
            return Verdict.CLEAN;
        }
        int colors = effect.getColors().size();
        int fadeColors = effect.getFadeColors().size();
        if (colors > MAX_FIREWORK_COLORS || fadeColors > MAX_FIREWORK_COLORS)
        {
            return new Verdict(Reason.OVERSIZED_FIREWORK, Math.max(colors, fadeColors), depth);
        }
        return Verdict.CLEAN;
    }

    private static Verdict inspectBannerPatterns(ItemStack item, int depth)
    {
        try
        {
            if (item.hasData(DataComponentTypes.PROVIDES_BANNER_PATTERNS))
            {
                RegistryKeySet<PatternType> provides = item.getData(DataComponentTypes.PROVIDES_BANNER_PATTERNS);
                if (provides != null && provides.size() > MAX_BANNER_PATTERNS)
                {
                    return new Verdict(Reason.OVERSIZED_BANNER_PATTERNS, provides.size(), depth);
                }
            }
            if (item.hasData(DataComponentTypes.BANNER_PATTERNS))
            {
                BannerPatternLayers layers = item.getData(DataComponentTypes.BANNER_PATTERNS);
                if (layers != null && layers.patterns().size() > MAX_BANNER_PATTERNS)
                {
                    return new Verdict(Reason.OVERSIZED_BANNER_PATTERNS, layers.patterns().size(), depth);
                }
            }
        }
        catch (Throwable t)
        {
            return new Verdict(Reason.UNINSPECTABLE_NBT, -1L, depth);
        }
        return Verdict.CLEAN;
    }

    private static Verdict inspectEquippable(ItemStack item, int depth)
    {
        try
        {
            if (item.hasData(DataComponentTypes.EQUIPPABLE))
            {
                Equippable equippable = item.getData(DataComponentTypes.EQUIPPABLE);
                if (equippable != null)
                {
                    RegistryKeySet<EntityType> allowed = equippable.allowedEntities();
                    if (allowed != null && allowed.size() > MAX_EQUIPPABLE_ENTITIES)
                    {
                        return new Verdict(Reason.OVERSIZED_EQUIPPABLE, allowed.size(), depth);
                    }
                    if (!isAllowedEquipSound(item, equippable.equipSound()))
                    {
                        return new Verdict(Reason.CURSED_EQUIP_SOUND, -1L, depth);
                    }
                }
            }
        }
        catch (Throwable t)
        {
            return new Verdict(Reason.UNINSPECTABLE_NBT, -1L, depth);
        }
        return Verdict.CLEAN;
    }

    private static boolean isAllowedEquipSound(final ItemStack item, final Key sound)
    {
        if (sound == null)
        {
            return true;
        }
        try
        {
            final ItemType type = item.getType().asItemType();
            if (type == null || !type.hasDefaultData(DataComponentTypes.EQUIPPABLE))
            {
                return false;
            }
            final Equippable defaults = type.getDefaultData(DataComponentTypes.EQUIPPABLE);
            return defaults != null && sound.equals(defaults.equipSound());
        }
        catch (final Throwable t)
        {
            return true;
        }
    }

    private static Verdict inspectCustomModelData(final ItemStack item, final int depth)
    {
        try
        {
            if (item.hasData(DataComponentTypes.CUSTOM_MODEL_DATA))
            {
                final CustomModelData cmd = item.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
                if (cmd != null)
                {
                    final int widest = Math.max(
                            Math.max(cmd.colors().size(), cmd.floats().size()),
                            Math.max(cmd.flags().size(), cmd.strings().size()));
                    if (widest > MAX_CUSTOM_MODEL_DATA_ENTRIES)
                    {
                        return new Verdict(Reason.OVERSIZED_CUSTOM_MODEL_DATA, widest, depth);
                    }
                    final boolean oversizedString = cmd.strings()
                            .stream()
                            .anyMatch(s -> s != null && s.length() > MAX_CUSTOM_MODEL_DATA_STRING_LENGTH);
                    if (oversizedString)
                    {
                        return new Verdict(Reason.OVERSIZED_CUSTOM_MODEL_DATA, -1L, depth);
                    }
                }
            }
        }
        catch (final Throwable t)
        {
            return new Verdict(Reason.UNINSPECTABLE_NBT, -1L, depth);
        }
        return Verdict.CLEAN;
    }

    private static Verdict inspectBlocksAttacks(final ItemStack item, final int depth)
    {
        try
        {
            if (!item.hasData(DataComponentTypes.BLOCKS_ATTACKS))
            {
                return Verdict.CLEAN;
            }
            final BlocksAttacks blocksAttacks = item.getData(DataComponentTypes.BLOCKS_ATTACKS);
            if (blocksAttacks == null)
            {
                return Verdict.CLEAN;
            }
            final RegistryKeySet<DamageType> bypassedBy = blocksAttacks.bypassedBy();
            if (bypassedBy != null && bypassedBy.size() > MAX_DAMAGE_TYPE_SET)
            {
                return new Verdict(Reason.OVERSIZED_BLOCKS_ATTACKS, bypassedBy.size(), depth);
            }
            final List<DamageReduction> reductions = blocksAttacks.damageReductions();
            if (reductions != null)
            {
                if (reductions.size() > MAX_DAMAGE_REDUCTIONS)
                {
                    return new Verdict(Reason.OVERSIZED_BLOCKS_ATTACKS, reductions.size(), depth);
                }
                for (final DamageReduction reduction : reductions)
                {
                    if (reduction == null)
                    {
                        continue;
                    }
                    final RegistryKeySet<DamageType> type = reduction.type();
                    if (type != null && type.size() > MAX_DAMAGE_TYPE_SET)
                    {
                        return new Verdict(Reason.OVERSIZED_BLOCKS_ATTACKS, type.size(), depth);
                    }
                }
            }
        }
        catch (final Throwable t)
        {
            return new Verdict(Reason.UNINSPECTABLE_NBT, -1L, depth);
        }
        return Verdict.CLEAN;
    }

    private static Verdict inspectNamedComponent(Component name, int depth, long deadlineNanos, Aggregate agg)
    {
        if (name == null)
        {
            return Verdict.CLEAN;
        }
        ComponentScanner.ComponentMetrics metrics = ComponentScanner.inspectComponent(name, ConfigEntry.maxComponentNodes(), deadlineNanos);
        if (metrics.unsafe())
        {
            return new Verdict(Reason.CURSED_COMPONENT, -1L, depth);
        }
        if (metrics.plainTextLength() > MAX_NAME_LENGTH)
        {
            return new Verdict(Reason.OVERSIZED_NAME, metrics.plainTextLength(), depth);
        }
        agg.text += metrics.plainTextLength();
        return Verdict.CLEAN;
    }

    private static Verdict inspectLoreLine(Component line, int depth, long deadlineNanos, Aggregate agg)
    {
        if (line == null)
        {
            return Verdict.CLEAN;
        }
        ComponentScanner.ComponentMetrics metrics = ComponentScanner.inspectComponent(line, ConfigEntry.maxComponentNodes(), deadlineNanos);
        if (metrics.unsafe())
        {
            return new Verdict(Reason.CURSED_COMPONENT, -1L, depth);
        }
        if (metrics.plainTextLength() > MAX_LORE_LINE_LENGTH)
        {
            return new Verdict(Reason.OVERSIZED_LORE, metrics.plainTextLength(), depth);
        }
        agg.text += metrics.plainTextLength();
        return Verdict.CLEAN;
    }

    private static Verdict inspectBookContent(ItemStack item, int depth, long deadlineNanos, Aggregate agg)
    {
        if (item.hasData(DataComponentTypes.WRITTEN_BOOK_CONTENT))
        {
            WrittenBookContent book = item.getData(DataComponentTypes.WRITTEN_BOOK_CONTENT);
            if (book != null)
            {
                List<Filtered<Component>> pages = book.pages();
                if (pages.size() > MAX_BOOK_PAGES)
                {
                    return new Verdict(Reason.OVERSIZED_BOOK, pages.size(), depth);
                }
                for (Filtered<Component> page : pages)
                {
                    if (page == null)
                    {
                        continue;
                    }
                    Verdict rawVerdict = inspectBookPage(page.raw(), depth, deadlineNanos, agg);
                    if (rawVerdict.isCursed())
                    {
                        return rawVerdict;
                    }
                    Verdict filteredVerdict = inspectBookPage(page.filtered(), depth, deadlineNanos, agg);
                    if (filteredVerdict.isCursed())
                    {
                        return filteredVerdict;
                    }
                }
            }
        }

        if (item.hasData(DataComponentTypes.WRITABLE_BOOK_CONTENT))
        {
            WritableBookContent book = item.getData(DataComponentTypes.WRITABLE_BOOK_CONTENT);
            if (book != null)
            {
                List<Filtered<String>> pages = book.pages();
                if (pages.size() > MAX_BOOK_PAGES)
                {
                    return new Verdict(Reason.OVERSIZED_BOOK, pages.size(), depth);
                }
                for (Filtered<String> page : pages)
                {
                    String raw = page != null ? page.raw() : null;
                    Verdict pageVerdict = inspectWritablePage(raw, depth, agg);
                    if (pageVerdict.isCursed())
                    {
                        return pageVerdict;
                    }
                }
            }
        }

        return Verdict.CLEAN;
    }

    private static Verdict inspectBookPage(Component page, int depth, long deadlineNanos, Aggregate agg)
    {
        if (page == null)
        {
            return Verdict.CLEAN;
        }
        ComponentScanner.ComponentMetrics metrics = ComponentScanner.inspectComponent(
                page, ConfigEntry.maxComponentNodes(), deadlineNanos);
        if (metrics.unsafe())
        {
            return new Verdict(Reason.CURSED_COMPONENT, -1L, depth);
        }
        if (metrics.plainTextLength() > MAX_BOOK_PAGE_LENGTH)
        {
            return new Verdict(Reason.OVERSIZED_BOOK, metrics.plainTextLength(), depth);
        }
        agg.text += metrics.plainTextLength();
        return Verdict.CLEAN;
    }

    private static Verdict inspectWritablePage(String page, int depth, Aggregate agg)
    {
        if (page == null || page.isEmpty())
        {
            return Verdict.CLEAN;
        }
        if (page.contains("click_event") || page.contains("hover_event"))
        {
            return new Verdict(Reason.CURSED_COMPONENT, -1L, depth);
        }
        if (page.length() > MAX_BOOK_PAGE_LENGTH)
        {
            return new Verdict(Reason.OVERSIZED_BOOK, page.length(), depth);
        }
        agg.text += page.length();
        return Verdict.CLEAN;
    }

    private static Verdict inspectInstrument(ItemStack item, int depth, long deadlineNanos, Aggregate agg)
    {
        if (!item.hasData(DataComponentTypes.INSTRUMENT))
        {
            return Verdict.CLEAN;
        }
        Component description;
        try
        {
            MusicInstrument instrument = item.getData(DataComponentTypes.INSTRUMENT);
            if (instrument == null)
            {
                return Verdict.CLEAN;
            }
            description = instrument.description();
        }
        catch (Throwable t)
        {
            return new Verdict(Reason.CURSED_COMPONENT, -1L, depth);
        }
        if (description == null)
        {
            return Verdict.CLEAN;
        }
        ComponentScanner.ComponentMetrics metrics = ComponentScanner.inspectComponent(
                description, ConfigEntry.maxComponentNodes(), deadlineNanos);
        if (metrics.unsafe())
        {
            return new Verdict(Reason.CURSED_COMPONENT, -1L, depth);
        }
        if (metrics.plainTextLength() > MAX_INSTRUMENT_DESC_LENGTH)
        {
            return new Verdict(Reason.OVERSIZED_LORE, metrics.plainTextLength(), depth);
        }
        agg.text += metrics.plainTextLength();
        return Verdict.CLEAN;
    }

    private static Verdict inspectJukebox(ItemStack item, int depth, long deadlineNanos, Aggregate agg)
    {
        if (!item.hasData(DataComponentTypes.JUKEBOX_PLAYABLE))
        {
            return Verdict.CLEAN;
        }
        Component description;
        try
        {
            JukeboxPlayable playable = item.getData(DataComponentTypes.JUKEBOX_PLAYABLE);
            if (playable == null)
            {
                return Verdict.CLEAN;
            }
            JukeboxSong song = playable.jukeboxSong();
            if (song == null)
            {
                return Verdict.CLEAN;
            }
            description = song.getDescription();
        }
        catch (Throwable t)
        {
            return new Verdict(Reason.CURSED_COMPONENT, -1L, depth);
        }
        if (description == null)
        {
            return Verdict.CLEAN;
        }
        ComponentScanner.ComponentMetrics metrics = ComponentScanner.inspectComponent(
                description, ConfigEntry.maxComponentNodes(), deadlineNanos);
        if (metrics.unsafe())
        {
            return new Verdict(Reason.CURSED_COMPONENT, -1L, depth);
        }
        if (metrics.plainTextLength() > MAX_INSTRUMENT_DESC_LENGTH)
        {
            return new Verdict(Reason.OVERSIZED_LORE, metrics.plainTextLength(), depth);
        }
        agg.text += metrics.plainTextLength();
        return Verdict.CLEAN;
    }

    private static Verdict inspectDamageType(DamageType type, int depth)
    {
        if (type != null)
        {
            // DamageType GENERIC_KILL and OUT_OF_WORLD can be used to kill people in creative mode
            if (type == DamageType.GENERIC_KILL || type == DamageType.OUT_OF_WORLD)
            {
                return new Verdict(Reason.CURSED_COMPONENT, -1L, depth);
            }
        }
        return Verdict.CLEAN;
    }

    private static Verdict inspectAttributeModifiers(ItemStack item, int depth, long deadlineNanos, Aggregate agg)
    {
        if (!item.hasData(DataComponentTypes.ATTRIBUTE_MODIFIERS))
        {
            return Verdict.CLEAN;
        }
        ItemAttributeModifiers modifiers = item.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (modifiers == null)
        {
            return Verdict.CLEAN;
        }

        final List<ItemAttributeModifiers.Entry> entries = modifiers.modifiers();
        if (entries.size() > MAX_ATTRIBUTE_MODIFIERS)
        {
            return new Verdict(Reason.OVERSIZED_ATTRIBUTE_COUNT, entries.size(), depth);
        }

        for (ItemAttributeModifiers.Entry entry : entries)
        {
            if (entry == null)
            {
                continue;
            }

            Verdict displayVerdict = inspectAttributeDisplay(entry, depth, deadlineNanos, agg);
            if (displayVerdict.isCursed())
            {
                return displayVerdict;
            }

            double cap = capForAttribute(entry.attribute());
            if (cap < 0.0)
            {
                continue;
            }
            AttributeModifier modifier = entry.modifier();
            if (modifier == null)
            {
                continue;
            }
            double raw = modifier.getAmount();
            if (Double.isNaN(raw) || Double.isInfinite(raw))
            {
                return new Verdict(Reason.OVERSIZED_ATTRIBUTE, Long.MAX_VALUE, depth);
            }
            double amount = Math.abs(raw);
            if (amount > cap)
            {
                return new Verdict(Reason.OVERSIZED_ATTRIBUTE, (long) amount, depth);
            }
        }
        return Verdict.CLEAN;
    }

    private static double capForAttribute(Attribute attribute)
    {
        if (attribute == Attribute.SCALE)
        {
            return MAX_ITEM_SCALE;
        }
        if (attribute == Attribute.WAYPOINT_TRANSMIT_RANGE || attribute == Attribute.WAYPOINT_RECEIVE_RANGE)
        {
            return MAX_WAYPOINT_RANGE;
        }
        return -1.0;
    }

    private static Verdict inspectAttributeDisplay(ItemAttributeModifiers.Entry entry, int depth, long deadlineNanos,
            Aggregate agg)
    {
        Component text;
        try
        {
            AttributeModifierDisplay display = entry.display();
            if (!(display instanceof AttributeModifierDisplay.OverrideText override))
            {
                return Verdict.CLEAN;
            }
            text = override.text();
        }
        catch (Throwable t)
        {
            return new Verdict(Reason.CURSED_COMPONENT, -1L, depth);
        }
        return inspectNamedComponent(text, depth, deadlineNanos, agg);
    }

    private static boolean budgetExceeded(long deadlineNanos)
    {
        return deadlineNanos > 0L && System.nanoTime() > deadlineNanos;
    }
}

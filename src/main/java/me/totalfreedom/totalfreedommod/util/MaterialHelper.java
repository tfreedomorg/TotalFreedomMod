package me.totalfreedom.totalfreedommod.util;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.Material;

/**
 * Helper class for material name resolution without triggering legacy material support.
 * Tries modern material names first, then falls back to legacy names only if necessary.
 */
public class MaterialHelper
{

    // Common legacy material name mappings (only for fallback)
    private static final Map<String, String> LEGACY_MATERIAL_MAP = new HashMap<>();

    static
    {
        // Common legacy material name mappings (expanded list)
        // These are common legacy names that might appear in old configs
        LEGACY_MATERIAL_MAP.put("grass", "GRASS_BLOCK");
        LEGACY_MATERIAL_MAP.put("dirt", "DIRT");
        LEGACY_MATERIAL_MAP.put("stone", "STONE");
        LEGACY_MATERIAL_MAP.put("cobblestone", "COBBLESTONE");
        LEGACY_MATERIAL_MAP.put("wood", "OAK_PLANKS");
        LEGACY_MATERIAL_MAP.put("log", "OAK_LOG");
        LEGACY_MATERIAL_MAP.put("leaves", "OAK_LEAVES");
        LEGACY_MATERIAL_MAP.put("glass", "GLASS");
        LEGACY_MATERIAL_MAP.put("sand", "SAND");
        LEGACY_MATERIAL_MAP.put("gravel", "GRAVEL");
        LEGACY_MATERIAL_MAP.put("gold_ore", "GOLD_ORE");
        LEGACY_MATERIAL_MAP.put("iron_ore", "IRON_ORE");
        LEGACY_MATERIAL_MAP.put("coal_ore", "COAL_ORE");
        LEGACY_MATERIAL_MAP.put("wool", "WHITE_WOOL");
        LEGACY_MATERIAL_MAP.put("yellow_flower", "DANDELION");
        LEGACY_MATERIAL_MAP.put("red_flower", "POPPY");
        LEGACY_MATERIAL_MAP.put("brown_mushroom", "BROWN_MUSHROOM");
        LEGACY_MATERIAL_MAP.put("red_mushroom", "RED_MUSHROOM");
        LEGACY_MATERIAL_MAP.put("tnt", "TNT");
        LEGACY_MATERIAL_MAP.put("mob_spawner", "SPAWNER");
        LEGACY_MATERIAL_MAP.put("chest", "CHEST");
        LEGACY_MATERIAL_MAP.put("furnace", "FURNACE");
        LEGACY_MATERIAL_MAP.put("water", "WATER");
        LEGACY_MATERIAL_MAP.put("lava", "LAVA");
        LEGACY_MATERIAL_MAP.put("fire", "FIRE");
        LEGACY_MATERIAL_MAP.put("air", "AIR");
        // Additional common legacy names
        LEGACY_MATERIAL_MAP.put("bedrock", "BEDROCK");
        LEGACY_MATERIAL_MAP.put("obsidian", "OBSIDIAN");
        LEGACY_MATERIAL_MAP.put("diamond_ore", "DIAMOND_ORE");
        LEGACY_MATERIAL_MAP.put("lapis_ore", "LAPIS_ORE");
        LEGACY_MATERIAL_MAP.put("redstone_ore", "REDSTONE_ORE");
        LEGACY_MATERIAL_MAP.put("emerald_ore", "EMERALD_ORE");
        LEGACY_MATERIAL_MAP.put("netherrack", "NETHERRACK");
        LEGACY_MATERIAL_MAP.put("soul_sand", "SOUL_SAND");
        LEGACY_MATERIAL_MAP.put("glowstone", "GLOWSTONE");
        LEGACY_MATERIAL_MAP.put("end_stone", "END_STONE");
    }

    /**
     * Gets a Material by name without triggering legacy material support.
     * Tries modern names first, then falls back to legacy mappings only if needed.
     * 
     * @param name Material name (case-insensitive)
     * @return Material or null if not found
     */
    public static Material getMaterial(String name)
    {
        if (name == null || name.isEmpty())
        {
            return null;
        }

        // First, try modern material name (uppercase, with underscores)
        String upperName = name.toUpperCase().replace(" ", "_");
        Material mat = Material.getMaterial(upperName);
        if (mat != null)
        {
            return mat;
        }

        // If not found, try legacy mapping (only for known legacy names)
        String legacyMapping = LEGACY_MATERIAL_MAP.get(name.toLowerCase());
        if (legacyMapping != null)
        {
            mat = Material.getMaterial(legacyMapping);
            if (mat != null)
            {
                return mat;
            }
        }

        // DO NOT use Material.matchMaterial() as it triggers legacy material support initialization
        // If we can't find it via modern methods or legacy mapping, return null
        // This ensures we never trigger legacy support
        return null;
    }
}


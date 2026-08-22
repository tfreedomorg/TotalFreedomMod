/*
 * Cleanroom Generator
 * Copyright (C) 2011-2012 nvx
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package me.totalfreedom.totalfreedommod.world;

import java.util.Random;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator;

/**
 * CleanroomChunkGenerator - Generates flat, cleanroom-style worlds.
 * 
 * Based on CleanroomGenerator v1.2.1 by nvx
 * https://github.com/nvx/CleanroomGenerator
 * 
 * Syntax: [prefix]height|block|height|block|...
 * 
 * Prefixes:
 *   . = Skip bedrock generation (void world if only ".")
 *   ^ = Start generation at y=-64 instead of y=0 (1.18+ deep worlds)
 * 
 * Examples:
 *   "64|stone"                    - 1 bedrock + 64 stone (starts at y=0)
 *   ".64|stone"                   - 64 stone, no bedrock
 *   "^64|stone"                   - 1 bedrock + 64 stone (starts at y=-64)
 *   "."                           - Void world (no blocks at all)
 *   "16|stone|32|dirt|1|grass_block" - Layered world
 * 
 * Also supports legacy comma-separated format for backward compatibility:
 *   "16,stone,32,dirt,1,grass"
 */
public class CleanroomChunkGenerator extends ChunkGenerator
{
    private static final Logger log = Bukkit.getLogger();
    
    private BlockData[] layerBlock;
    private int[] layerHeight;
    private boolean noBedrock = false;
    private boolean newHeight = false;

    public CleanroomChunkGenerator()
    {
        this("64|stone");
    }

    public CleanroomChunkGenerator(String id)
    {
        if (id == null || id.isEmpty())
        {
            id = "64|stone";
        }

        // Check for void world (just ".")
        if (id.equals("."))
        {
            layerBlock = new BlockData[0];
            layerHeight = new int[0];
            return;
        }

        try
        {
            // Parse prefixes
            while (id.length() > 0 && (id.charAt(0) == '.' || id.charAt(0) == '^'))
            {
                if (id.charAt(0) == '.')
                {
                    noBedrock = true;
                }
                if (id.charAt(0) == '^')
                {
                    newHeight = true;
                }
                id = id.substring(1);
            }

            // Add bedrock layer unless disabled
            if (!noBedrock)
            {
                id = "1|minecraft:bedrock|" + id;
            }

            // Determine separator (support both pipe and legacy comma)
            String separator;
            if (id.contains("|"))
            {
                separator = "[|]";
            }
            else
            {
                // Legacy comma-separated format
                separator = "[,]";
                log.info("[CleanroomGenerator] Using legacy comma-separated format. Consider updating to pipe-separated format.");
            }

            String[] tokens = id.split(separator);

            if ((tokens.length % 2) != 0)
            {
                throw new Exception("Invalid layer specification: odd number of tokens");
            }

            int layerCount = tokens.length / 2;
            layerBlock = new BlockData[layerCount];
            layerHeight = new int[layerCount];

            for (int i = 0; i < layerCount; i++)
            {
                int j = i * 2;
                
                // Parse height
                int height = Integer.parseInt(tokens[j].trim());
                if (height <= 0)
                {
                    log.warning("[CleanroomGenerator] Invalid height '" + tokens[j] + "'. Using 64 instead.");
                    height = 64;
                }

                // Parse block
                String blockName = tokens[j + 1].trim();
                BlockData blockData;
                try
                {
                    // Try modern block data format first (e.g., "minecraft:grass_block[snowy=true]")
                    blockData = Bukkit.createBlockData(blockName);
                }
                catch (Exception e)
                {
                    // Try with minecraft: prefix
                    try
                    {
                        blockData = Bukkit.createBlockData("minecraft:" + blockName);
                    }
                    catch (Exception e2)
                    {
                        // Try Material enum as fallback
                        try
                        {
                            Material mat = Material.valueOf(blockName.toUpperCase().replace(" ", "_"));
                            if (mat.isBlock())
                            {
                                blockData = mat.createBlockData();
                            }
                            else
                            {
                                throw new Exception("Not a block");
                            }
                        }
                        catch (Exception e3)
                        {
                            log.warning("[CleanroomGenerator] Failed to lookup block '" + blockName + "'. Using stone instead. Exception: " + e.toString());
                            blockData = Material.STONE.createBlockData();
                        }
                    }
                }

                layerBlock[i] = blockData;
                layerHeight[i] = height;
            }
        }
        catch (Exception e)
        {
            log.severe("[CleanroomGenerator] Error parsing CleanroomGenerator ID '" + id + "'. Using defaults '1|bedrock|64|stone': " + e.toString());
            e.printStackTrace();

            layerBlock = new BlockData[2];
            layerBlock[0] = Material.BEDROCK.createBlockData();
            layerBlock[1] = Material.STONE.createBlockData();

            layerHeight = new int[2];
            layerHeight[0] = 1;
            layerHeight[1] = 64;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public ChunkData generateChunkData(World world, Random random, int chunkX, int chunkZ, BiomeGrid biome)
    {
        ChunkData chunk = createChunkData(world);

        int y = newHeight ? world.getMinHeight() : 0;
        
        for (int i = 0; i < layerBlock.length; i++)
        {
            if (layerBlock[i] != null)
            {
                chunk.setRegion(0, y, 0, 16, y + layerHeight[i], 16, layerBlock[i]);
            }
            y += layerHeight[i];
        }

        return chunk;
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random)
    {
        if (!world.isChunkLoaded(0, 0))
        {
            world.loadChunk(0, 0);
        }

        int highestBlock = world.getHighestBlockYAt(0, 0);

        if ((highestBlock <= world.getMinHeight()) && (world.getBlockAt(0, world.getMinHeight(), 0).getType() == Material.AIR))
        {
            // SPACE! Let people drop a little before hitting the void
            return new Location(world, 0, 64, 0);
        }

        return new Location(world, 0, highestBlock, 0);
    }
}

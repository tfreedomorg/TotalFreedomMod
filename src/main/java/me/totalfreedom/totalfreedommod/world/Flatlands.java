package me.totalfreedom.totalfreedommod.world;

import java.io.File;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;

import org.apache.commons.io.FileUtils;

public class Flatlands extends CustomWorld
{

    private static final String GENERATION_PARAMETERS = ConfigEntry.FLATLANDS_GENERATE_PARAMS.getString();

    public Flatlands(TotalFreedomMod plugin)
    {
        super(plugin, "flatlands", "Flatlands");
    }

    @Override
    protected World generateWorld()
    {
        if (!ConfigEntry.FLATLANDS_GENERATE.getBoolean())
        {
            return null;
        }

        wipeFlatlandsIfFlagged();

        final WorldCreator worldCreator = new WorldCreator(getName());
        worldCreator.generateStructures(false);
        worldCreator.type(WorldType.NORMAL);
        worldCreator.environment(World.Environment.NORMAL);
        worldCreator.generator(new CleanroomChunkGenerator(GENERATION_PARAMETERS));

        final World world = Bukkit.getServer().createWorld(worldCreator);

        world.setSpawnFlags(false, false);
        world.setSpawnLocation(0, 50, 0);

        return world;
    }

    public void wipeFlatlandsIfFlagged()
    {
        boolean doFlatlandsWipe = false;
        try
        {
            doFlatlandsWipe = plugin.sf.getSavedFlag("do_wipe_flatlands");
        }
        catch (Exception ex)
        {
        }

        if (doFlatlandsWipe)
        {
            if (Bukkit.getServer().getWorld("flatlands") == null)
            {
                FLog.info("Wiping flatlands.");
                plugin.sf.setSavedFlag("do_wipe_flatlands", false);
                FileUtils.deleteQuietly(new File("./flatlands"));
            }
            else
            {
                FLog.severe("Can't wipe flatlands, it is already loaded.");
            }
        }
    }

}

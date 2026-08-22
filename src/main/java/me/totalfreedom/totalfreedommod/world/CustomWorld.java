package me.totalfreedom.totalfreedommod.world;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.framework.PluginComponent;
import me.totalfreedom.totalfreedommod.util.FLog;

import lombok.Getter;

public abstract class CustomWorld extends PluginComponent<TotalFreedomMod>
{

    @Getter
    private final String name;

    @Getter
    private final String displayName;
    //
    private World world;

    public CustomWorld(TotalFreedomMod plugin, String name, String displayName)
    {
        super(plugin);
        this.name = name;
        this.displayName = displayName;
    }

    public CustomWorld(TotalFreedomMod plugin, String name)
    {
        this(plugin, name, name);
    }

    public final World getWorld()
    {
        if (world == null || !Bukkit.getWorlds().contains(world))
        {
            world = generateWorld();

            final Block welcomeSignBlock = world.getBlockAt(0, 50, 0);
            welcomeSignBlock.setType(Material.OAK_SIGN);
            // Use BlockData API instead of deprecated MaterialData
            org.bukkit.block.data.type.Sign signData = (org.bukkit.block.data.type.Sign) Material.OAK_SIGN.createBlockData();
            signData.setRotation(BlockFace.NORTH);
            welcomeSignBlock.setBlockData(signData);

            org.bukkit.block.Sign welcomeSign = (org.bukkit.block.Sign) welcomeSignBlock.getState();

            Component[] lines = {
                    Component.text(this.displayName, NamedTextColor.GREEN),
                    Component.text("---", NamedTextColor.DARK_GRAY),
                    Component.text("Spawn Point", NamedTextColor.YELLOW),
                    Component.text("---", NamedTextColor.DARK_GRAY)
            };

            org.bukkit.block.sign.SignSide front = welcomeSign.getSide(org.bukkit.block.sign.Side.FRONT);
            org.bukkit.block.sign.SignSide back = welcomeSign.getSide(org.bukkit.block.sign.Side.BACK);

            for (int i = 0; i < lines.length; i++)
            {
                front.line(i, lines[i]);
                back.line(i, lines[i]);
            }

            welcomeSign.update();

            plugin.gr.enforceGameRuleDefaultsForWorld(world);
        }

        if (world == null)
        {
            FLog.warning("Could not load world: " + name);
        }

        return world;
    }

    public void sendToWorld(Player player)
    {
        try
        {
            player.teleport(getWorld().getSpawnLocation());
        }
        catch (Exception ex)
        {
            player.sendMessage(ex.getMessage());
        }
    }

    protected abstract World generateWorld();
}

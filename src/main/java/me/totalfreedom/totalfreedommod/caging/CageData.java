package me.totalfreedom.totalfreedommod.caging;

import java.util.ArrayList;
import java.util.List;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.entity.Player;

import me.totalfreedom.totalfreedommod.player.FPlayer;

public class CageData
{

    private static final ResolvableProfile DARTH_PROFILE = ResolvableProfile.resolvableProfile().name("Prozza").build();

    private final FPlayer fPlayer;
    private final List<BlockData> cageHistory = new ArrayList<>();

    private boolean caged = false;
    private Location location;
    private Material outerMaterial = Material.GLASS;
    private Material innerMaterial = Material.AIR;
    private GameMode previousGameMode = null;
    private boolean wasOp = false;

    public CageData(FPlayer player)
    {
        this.fPlayer = player;
    }

    public boolean isCaged()
    {
        return caged;
    }

    public Location getLocation()
    {
        return location;
    }

    public Material getOuterMaterial()
    {
        return outerMaterial;
    }

    public Material getInnerMaterial()
    {
        return innerMaterial;
    }

    public void setCaged(boolean cage)
    {
        if (cage)
        {
            final Player player = fPlayer.getPlayer();
            if (player == null)
            {
                return;
            }

            cage(player.getLocation().clone().add(0, 1, 0), Material.GLASS, Material.AIR);
        }
        else
        {
            uncage();
        }
    }

    public void cage(Location location, Material outer, Material inner)
    {
        final Player player = fPlayer.getPlayer();
        if (player == null)
        {
            return;
        }

        if (isCaged())
        {
            regenerateHistory();
            clearHistory();
        }
        else
        {
            previousGameMode = player.getGameMode();
            wasOp = player.isOp();
        }

        this.caged = true;
        this.location = location;
        this.outerMaterial = outer;
        this.innerMaterial = inner;

        player.setOp(false);
        player.setGameMode(GameMode.ADVENTURE);

        buildHistory(location, 2);
        regenerate();
    }

    public void uncage()
    {
        if (!caged)
        {
            return;
        }

        this.caged = false;
        this.location = null;
        regenerateHistory();
        clearHistory();

        final Player player = fPlayer.getPlayer();
        if (player != null)
        {
            player.setOp(wasOp);
            if (previousGameMode != null)
            {
                player.setGameMode(previousGameMode);
            }
        }

        previousGameMode = null;
        wasOp = false;
    }

    public void teleportToCenter()
    {
        final Player player = fPlayer.getPlayer();
        if (player == null || location == null)
        {
            return;
        }

        final Location target = location.clone().subtract(0, 1, 0);
        target.setYaw(player.getLocation().getYaw());
        target.setPitch(player.getLocation().getPitch());
        player.teleport(target);
    }

    public void regenerate()
    {
        if (!caged
                || location == null
                || outerMaterial == null
                || innerMaterial == null)
        {
            return;
        }

        generate(location, 2, outerMaterial, true);
        generate(location, 1, innerMaterial, false);
    }

    public void playerJoin()
    {
        if (!isCaged())
        {
            return;
        }

        cage(fPlayer.getPlayer().getLocation().clone().add(0, 1, 0), outerMaterial, innerMaterial);
    }

    public void playerQuit()
    {
        regenerateHistory();
        clearHistory();
    }

    public void clearHistory()
    {
        cageHistory.clear();
    }

    private void insertHistoryBlock(Location location, Material material)
    {
        cageHistory.add(new BlockData(location, material));
    }

    private void regenerateHistory()
    {
        for (BlockData blockdata : this.cageHistory)
        {
            blockdata.location.getBlock().setType(blockdata.material);
        }
    }

    private void buildHistory(Location location, int length)
    {
        final Block center = location.getBlock();
        for (int xOffset = -length; xOffset <= length; xOffset++)
        {
            for (int yOffset = -length; yOffset <= length; yOffset++)
            {
                for (int zOffset = -length; zOffset <= length; zOffset++)
                {
                    final Block block = center.getRelative(xOffset, yOffset, zOffset);
                    insertHistoryBlock(block.getLocation(), block.getType());
                }
            }
        }
    }

    // Why this was two separate methods instead of one with a flag is beyond me
    private static void generate(Location location, int length, Material material, boolean hollow)
    {
        final Block center = location.getBlock();
        for (int x = -length; x <= length; x++)
        {
            for (int y = -length; y <= length; y++)
            {
                for (int z = -length; z <= length; z++)
                {
                    if (hollow && Math.abs(x) != length && Math.abs(y) != length && Math.abs(z) != length)
                    {
                        continue;
                    }

                    final Block block = center.getRelative(x, y, z);
                    final Material type = hollow ? shellMaterial(material, x, y, z, length) : material;

                    if (block.getType() != type)
                    {
                        block.setType(type);
                    }

                    if (type == Material.PLAYER_HEAD)
                    {
                        final Skull skull = (Skull) block.getState();
                        skull.setProfile(DARTH_PROFILE);
                        skull.update();
                    }
                }
            }
        }
    }

    private static Material shellMaterial(Material material, int x, int y, int z, int length)
    {
        if (material == Material.PLAYER_HEAD)
        {
            return Math.abs(x) == length && Math.abs(y) == length && Math.abs(z) == length ? Material.GLOWSTONE : material;
        }

        return material != Material.GLASS && x == 0 && y == length && z == 0 ? Material.GLOWSTONE : material;
    }

    private static class BlockData
    {

        public Material material;
        public Location location;

        private BlockData(Location location, Material material)
        {
            this.location = location;
            this.material = material;
        }
    }
}

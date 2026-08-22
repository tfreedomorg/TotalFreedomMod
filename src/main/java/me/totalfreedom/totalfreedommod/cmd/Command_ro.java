package me.totalfreedom.totalfreedommod.cmd;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Permission(permission = "tfm.admin.ro")
@Command(name = "ro", description = "Remove all blocks of a certain type in the radius of certain players.", usage = "/<command> <blocks> [radius] [players]")
public class Command_ro extends FCommand
{
    @Callback
    public void allPlayersDefault(CommandSender sender, @Resolve(value = "MaterialQuery", strategy = "mode=blocks,limit=24L,nonEmpty") List<Material> blocks)
    {
        allPlayersWithRadius(sender, blocks, 50);
    }

    @Callback
    public void allPlayersWithRadius(CommandSender sender, @Resolve(value = "MaterialQuery", strategy = "mode=blocks,limit=24L,nonEmpty") List<Material> blocks, int radius)
    {
        removeNear(sender, blocks, radius, server().getOnlinePlayers().stream()
                .filter(player -> !player.getWorld().getName().equalsIgnoreCase(plugin().wm.adminworld.getName()))
                .map(player -> (Player) player)
                .toList());
    }

    @Callback
    public void removeNear(CommandSender sender, @Resolve(value = "MaterialQuery", strategy = "mode=blocks,limit=24L,nonEmpty") List<Material> blocks, int radius, @Resolve("PlayerList") List<Player> players)
    {
        if (blocks.isEmpty())
        {
            msg(sender, "<gray>No blocks could be found matching your query.");
            return;
        }

        final int clampedRadius = Math.clamp(radius, 1, 100);

        adminAction(sender, "<aqua>Removing all blocks of <blocks> within <radius> blocks of <players>",
                Placeholder.unparsed("blocks", blocks.size() > 4
                        ? blocks.size() + " type(s)"
                        : String.join(", ", blocks.stream().map(m -> m.name()).toList())),
                Formatter.number("radius", clampedRadius),
                Placeholder.unparsed("players", players.size() == server().getOnlinePlayers().size()
                        ? "all players... Brace for lag!"
                        : players.size() > 4
                            ? players.size() + " players"
                            : String.join(", ", players.stream().map(p -> p.getName()).toList())));

        final Set<Material> materials = EnumSet.copyOf(blocks);

        int affected = 0;
        for (Player player : players)
        {
            affected += replaceBlocks(player.getLocation(), materials, Material.AIR, clampedRadius);
        }

        msg(sender, "<gray>Remove complete. <count> blocks were removed.", Formatter.number("count", affected));
    }

    public static int replaceBlocks(Location center, Set<Material> fromMaterials, Material toMaterial, int radius)
    {
        int affected = 0;
        final long radiusSquared = (long) radius * radius;

        final Block centerBlock = center.getBlock();
        final int baseX = centerBlock.getX();
        final int baseY = centerBlock.getY();
        final int baseZ = centerBlock.getZ();
        final double centerX = center.getX();
        final double centerY = center.getY();
        final double centerZ = center.getZ();

        for (int xOffset = -radius; xOffset <= radius; xOffset++)
        {
            final double dx = (baseX + xOffset) - centerX;
            for (int yOffset = -radius; yOffset <= radius; yOffset++)
            {
                final double dy = (baseY + yOffset) - centerY;
                for (int zOffset = -radius; zOffset <= radius; zOffset++)
                {
                    final double dz = (baseZ + zOffset) - centerZ;
                    if (dx * dx + dy * dy + dz * dz >= radiusSquared)
                    {
                        continue;
                    }

                    final Block block = centerBlock.getRelative(xOffset, yOffset, zOffset);
                    if (fromMaterials.contains(block.getType()))
                    {
                        block.setType(toMaterial, false);
                        affected++;
                    }
                }
            }
        }

        return affected;
    }
}

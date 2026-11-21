package me.totalfreedom.totalfreedommod.world.stage.feature;

import java.util.List;
import java.util.stream.IntStream;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.generator.LimitedRegion;

import me.totalfreedom.totalfreedommod.world.base.ChunkContext;
import me.totalfreedom.totalfreedommod.world.profile.FeatureDetail;

/**
 * A dip filled with fluid, level with the ground around it. Detail radius is how wide the bowl is.
 * <p>
 * The only feature that removes blocks as well as placing them, so it checks before it digs. The
 * water line is the ground's own top row, and a lake only goes ahead if every column of the bowl,
 * plus a one block ring around it, is solid ground at exactly that row. That is what stops a lake
 * on a slope from hanging in the air on its downhill side, and what keeps it out of oceans and
 * rivers. Anything that fails the check is simply not placed, the same as a tree with no room.
 * <p>
 * The bowl is a squashed ellipsoid, wider than it is deep, because a round hole reads as a crater.
 * Each column is dug to its own depth, deepest in the middle, with solid ground required under it
 * so the fluid has a floor and not a cave to drain into.
 * <p>
 * Ground inside the footprint may stand up to {@link #MAX_CUT} blocks above the water line and gets
 * cut down to it, so a gentle rise is trimmed into a bank. Anything steeper, or any trunk or canopy
 * in the way, and the lake is skipped rather than leaving a floating tree or a cliff.
 * <p>
 * Skips the world's own bedrock block wherever it finds it, floor or roof, rather than replacing it.
 * <p>
 * The check reads the region as it actually is, not the generator's height guess, so it holds even
 * when an earlier feature or a neighbouring chunk's feature has already changed the ground. A bowl
 * that would cross the edge of the region is skipped for the same reason, rather than clipped into a
 * half lake with a raw wall.
 */
public final class LakeFeature implements Feature<FeatureDetail.Lake>
{
    /** How many times wider than deep the bowl is. */
    private static final int SQUASH = 2;

    /** How far above the water line ground may stand inside the footprint and still be cut down to it. */
    private static final int MAX_CUT = 2;

    /** One column of the footprint. Depth is how many fluid blocks it holds, zero for the ring that only has to be solid. */
    private record Column(int x, int z, int depth)
    {
    }

    @Override
    public void place(final ChunkContext context,
                      final LimitedRegion region,
                      final FeatureDetail.Lake detail,
                      final int x,
                      final int y,
                      final int z)
    {
        final int radius = detail.radius();
        final int maxDepth = Math.max(1, radius / SQUASH);
        final int waterLine = y - 1;
        final Material bedrock = context.getProfile().palette().materials().bedrockBlock().getMaterial();
        final List<Column> footprint = footprint(x, z, radius, maxDepth);

        if (!footprint.stream().allMatch(column -> isSound(region, column, waterLine)))
            return;

        footprint.stream()
                 .filter(column -> column.depth() > 0)
                 .forEach(column -> dig(region, detail, bedrock, column, waterLine));
    }

    /** Every column within one block of the bowl's edge, each with the depth its own distance from the middle gives it. */
    private static List<Column> footprint(final int x, final int z, final int radius, final int maxDepth)
    {
        final int reach = radius + 1;

        return IntStream.rangeClosed(-reach, reach)
                        .boxed()
                        .flatMap(offsetX -> IntStream.rangeClosed(-reach, reach)
                                                     .filter(offsetZ -> offsetX * offsetX + offsetZ * offsetZ <= reach * reach)
                                                     .mapToObj(offsetZ -> new Column(x + offsetX,
                                                                                     z + offsetZ,
                                                                                     depthAt(offsetX, offsetZ, radius, maxDepth))))
                        .toList();
    }

    /** The ellipsoid's depth at this offset, zero at and beyond the rim. */
    private static int depthAt(final int offsetX, final int offsetZ, final int radius, final int maxDepth)
    {
        final double reach = (offsetX * offsetX + offsetZ * offsetZ) / (double) (radius * radius);

        if (reach >= 1.0D)
            return 0;

        return (int) Math.round(maxDepth * Math.sqrt(1.0D - reach));
    }

    /**
     * Whether this column can take part. Solid ground at the water line, a firm floor under the
     * deepest fluid block, and, for a column that gets dug, only a small cut and no tree above.
     */
    private static boolean isSound(final LimitedRegion region, final Column column, final int waterLine)
    {
        final int floorY = waterLine - column.depth();
        final int ceilingY = waterLine + MAX_CUT + 1;

        if (!region.isInRegion(column.x(), floorY, column.z()) || !region.isInRegion(column.x(), ceilingY, column.z()))
            return false;

        if (!region.getType(column.x(), waterLine, column.z()).isSolid())
            return false;

        if (column.depth() == 0)
            return true;

        final boolean firmFloor = region.getType(column.x(), floorY, column.z()).isSolid();
        final boolean smallCut = !region.getType(column.x(), ceilingY, column.z()).isSolid();
        final boolean clearAbove = IntStream.rangeClosed(1, MAX_CUT)
                                            .mapToObj(rise -> region.getType(column.x(), waterLine + rise, column.z()))
                                            .noneMatch(type -> Tag.LOGS.isTagged(type) || Tag.LEAVES.isTagged(type));

        return firmFloor && smallCut && clearAbove;
    }

    /** Fills the column with fluid up to the water line and trims whatever solid ground stands above it. */
    private static void dig(final LimitedRegion region,
                            final FeatureDetail.Lake detail,
                            final Material bedrock,
                            final Column column,
                            final int waterLine)
    {
        IntStream.rangeClosed(waterLine - column.depth() + 1, waterLine)
                 .filter(blockY -> region.getType(column.x(), blockY, column.z()) != bedrock)
                 .forEach(blockY -> region.setBlockData(column.x(), blockY, column.z(), detail.fluid()));

        IntStream.rangeClosed(waterLine + 1, waterLine + MAX_CUT)
                 .filter(blockY -> region.getType(column.x(), blockY, column.z()).isSolid())
                 .forEach(blockY -> region.setType(column.x(), blockY, column.z(), Material.AIR));
    }
}

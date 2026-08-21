package me.totalfreedom.totalfreedommod.cmd;

import java.util.Iterator;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.scheduler.BukkitRunnable;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Command(name = "mobpurge", description = "Purge all mobs in all worlds.", usage = "/mobpurge [world] [chunkX chunkZ | batchSize]", aliases = {"mp"})
@Permission(permission = "tfm.server.mobpurge")
public class Command_mobpurge extends FCommand
{
    public static final int DEFAULT_BATCH_SIZE = 200;

    @Callback
    public void mobpurgeAll(CommandSender sender)
    {
        adminAction(sender, "<red>Purging all mobs");
        final int removed = purgeMobs();
        msg(
            sender,
            "<gray><count> <noun> removed.",
            Formatter.number("count", removed),
            Placeholder.unparsed("noun", removed == 1 ? "mob" : "mobs")
        );
    }

    @Callback
    public void mobpurgeWorld(CommandSender sender, World world)
    {
        mobpurgeWorld(sender, world, DEFAULT_BATCH_SIZE);
    }

    @Callback
    public void mobpurgeWorld(CommandSender sender, World world, int batchSize)
    {
        final int clamped = Math.max(10, Math.min(1000, batchSize));
        adminAction(
            sender,
            "<red>Starting mob purge in world <world> (<batch> per tick)",
            Placeholder.unparsed("world", world.getName()),
            Formatter.number("batch", clamped)
        );
        purgeMobsBatched(world, clamped, sender);
        msg(sender, "<gray>Mob purge started. You will be notified when done.");
    }

    @Callback
    public void mobpurgeChunk(CommandSender sender, World world, int chunkX, int chunkZ)
    {
        mobpurgeChunk(sender, world, chunkX, chunkZ, DEFAULT_BATCH_SIZE);
    }

    @Callback
    public void mobpurgeChunk(CommandSender sender, World world, int chunkX, int chunkZ, int batchSize)
    {
        final int clamped = Math.max(10, Math.min(1000, batchSize));
        adminAction(
            sender,
            "<red>Starting mob purge in world <world> chunk (<x>,<z>) (<batch> per tick)",
            Placeholder.unparsed("world", world.getName()),
            Formatter.number("x", chunkX),
            Formatter.number("z", chunkZ),
            Formatter.number("batch", clamped)
        );
        purgeMobsBatchedForChunk(world, chunkX, chunkZ, clamped, sender);
        msg(
            sender,
            "<gray>Batched mob purge started for chunk (<x>,<z>). You will be notified when done.",
            Formatter.number("x", chunkX),
            Formatter.number("z", chunkZ)
        );
    }

    /**
     * Purge mobs in a single world over multiple ticks to avoid server freezes.
     * <p>
     * The snapshot below is one {@code getLivingEntities()} call, taken up front, then walked incrementally via a single
     * {@link Iterator} held across ticks. {@code batchSize} binds
     * entities *examined* per tick, not entities *removed* which is a more predictable
     * per-tick cost than "keep scanning until batchSize matches are found."
     * <p>
     * If no entities are in loaded chunks, force-loads spawn area and re-snapshots once.
     */
    private void purgeMobsBatched(World world, int batchSize, CommandSender notify)
    {
        new BukkitRunnable()
        {
            Iterator<? extends Entity> candidates = world.getLivingEntities().iterator();
            int totalRemoved = 0;
            boolean spawnLoaded = false;

            @Override
            public void run()
            {
                if (!plugin().isEnabled())
                {
                    cancel();
                    return;
                }

                World w = Bukkit.getWorld(world.getName());
                if (w == null)
                {
                    cancel();
                    sendDone(notify, world.getName(), totalRemoved, false);
                    return;
                }

                int examined = 0;
                while (examined < batchSize && candidates.hasNext())
                {
                    Entity ent = candidates.next();
                    examined++;

                    if (ent.isValid() && isPurgeableMob(ent))
                    {
                        ent.remove();
                        totalRemoved++;
                    }
                }

                if (!candidates.hasNext())
                {
                    if (totalRemoved == 0 && !spawnLoaded)
                    {
                        loadSpawnChunks(w);
                        spawnLoaded = true;
                        candidates = w.getLivingEntities().iterator();
                        return;
                    }

                    cancel();
                    sendDone(notify, world.getName(), totalRemoved, totalRemoved == 0 && spawnLoaded);
                }
            }
        }.runTaskTimer(plugin(), 1L, 1L);
    }

    /**
     * Purge mobs only in a specific chunk. Loads that chunk first (may cause brief lag if the
     * chunk has many entities), then walks a single up-front entity snapshot incrementally.
     */
    private void purgeMobsBatchedForChunk(World world, int chunkX, int chunkZ, int batchSize, CommandSender notify)
    {
        world.getChunkAt(chunkX, chunkZ);

        new BukkitRunnable()
        {
            final Iterator<? extends Entity> candidates = world.getLivingEntities().iterator();
            int totalRemoved = 0;

            @Override
            public void run()
            {
                if (!plugin().isEnabled())
                {
                    cancel();
                    return;
                }

                World w = Bukkit.getWorld(world.getName());
                if (w == null)
                {
                    cancel();
                    sendChunkDone(notify, world.getName(), chunkX, chunkZ, totalRemoved);
                    return;
                }

                int examined = 0;
                while (examined < batchSize && candidates.hasNext())
                {
                    Entity ent = candidates.next();
                    examined++;

                    if (!ent.isValid() || !isPurgeableMob(ent))
                    {
                        continue;
                    }

                    int ex = ent.getLocation().getBlockX() >> 4;
                    int ez = ent.getLocation().getBlockZ() >> 4;
                    if (ex == chunkX && ez == chunkZ)
                    {
                        ent.remove();
                        totalRemoved++;
                    }
                }

                if (!candidates.hasNext())
                {
                    cancel();
                    sendChunkDone(notify, world.getName(), chunkX, chunkZ, totalRemoved);
                }
            }
        }.runTaskTimer(plugin(), 1L, 1L);
    }

    private void sendDone(CommandSender notify, String worldName, int total, boolean suggestReload)
    {
        msg(
            notify,
            "<gray>[MobPurge] World \"<world>\": <count> <noun> removed.",
            Placeholder.unparsed("world", worldName),
            Formatter.number("count", total),
            Placeholder.unparsed("noun", total == 1 ? "mob" : "mobs")
        );

        if (suggestReload)
        {
            msg(notify, "<gray>[MobPurge] No entities in loaded chunks. Run again after spawn area loaded, or have a player enter the world so more chunks load, then run again.");
        }
    }

    private void sendChunkDone(CommandSender notify, String worldName, int chunkX, int chunkZ, int total)
    {
        msg(
            notify,
            "<gray>[MobPurge] World \"<world>\" chunk (<x>,<z>): <count> <noun> removed.",
            Placeholder.unparsed("world", worldName),
            Formatter.number("x", chunkX),
            Formatter.number("z", chunkZ),
            Formatter.number("count", total),
            Placeholder.unparsed("noun", total == 1 ? "mob" : "mobs")
        );
    }

    private static void loadSpawnChunks(World w)
    {
        final int cx = w.getSpawnLocation().getBlockX() >> 4;
        final int cz = w.getSpawnLocation().getBlockZ() >> 4;
        for (int dx = -1; dx <= 1; dx++)
        {
            for (int dz = -1; dz <= 1; dz++)
            {
                w.getChunkAt(cx + dx, cz + dz);
            }
        }
    }

    public static boolean isPurgeableMob(Entity ent)
    {
        return ent instanceof Creature || ent instanceof Ghast || ent instanceof Slime
                || ent instanceof EnderDragon || ent instanceof Ambient;
    }

    public static int purgeMobs()
    {
        int removed = 0;
        for (World world : Bukkit.getWorlds())
        {
            for (Entity ent : world.getLivingEntities())
            {
                if (isPurgeableMob(ent))
                {
                    ent.remove();
                    removed++;
                }
            }
        }

        return removed;
    }
}

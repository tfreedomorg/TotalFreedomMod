package me.totalfreedom.totalfreedommod;

import me.totalfreedom.api.FreedomAPI;

import java.util.*;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.scheduler.BukkitTask;

import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FTask;
import me.totalfreedom.totalfreedommod.util.FUtil;

public class EntityWiper extends FreedomService
{

    public static final int AUTO_WIPE_INTERVAL_DEFAULT = 15;
    public static final long ITEM_DESPAWN_RATE = 20L * 20L;
    public static final int CHUNK_ENTITY_MAX = 20;
    //
    private final List<Class<? extends Entity>> wipables = new ArrayList<>();
    //
    private BukkitTask wipeTask;

    public EntityWiper(FreedomAPI plugin)
    {
        super(plugin);
        wipables.add(EnderCrystal.class);
        wipables.add(EnderSignal.class);
        wipables.add(ExperienceOrb.class);
        wipables.add(Projectile.class);
        wipables.add(FallingBlock.class);
        wipables.add(Firework.class);
        wipables.add(Item.class);
        wipables.add(ThrownPotion.class);
        wipables.add(ThrownExpBottle.class);
        wipables.add(AreaEffectCloud.class);
        wipables.add(Minecart.class);
        wipables.add(Boat.class);
        wipables.add(FallingBlock.class);
        wipables.add(ArmorStand.class);
        wipables.add(Mannequin.class);
    }

    @Override
    public void onStart()
    {
        if (!isAutoWipeEnabled())
        {
            return;
        }

        final long rate = wipeRateTicks();
        wipeTask = plugin.getServer().getScheduler().runTaskTimer(plugin,
                FTask.guard("EntityWiper/autoWipe", () -> wipeEntities(false)), rate, rate);

    }

    public static boolean isAutoWipeEnabled()
    {
        return ConfigEntry.AUTO_ENTITY_WIPE.getBoolean(true);
    }

    private static long wipeRateTicks()
    {
        final int seconds = ConfigEntry.AUTO_ENTITY_WIPE_INTERVAL.getInteger(AUTO_WIPE_INTERVAL_DEFAULT);
        return Math.max(seconds, 1) * 20L;
    }

    @Override
    public void onStop()
    {
        FUtil.cancel(wipeTask);
        wipeTask = null;
    }

    public boolean isWipeable(Entity entity)
    {
        for (Class<? extends Entity> c : wipables)
        {
            if (c.isAssignableFrom(entity.getClass()))
            {
                return true;
            }
        }

        return false;
    }

    public int wipeEntities(boolean force)
    {
        int removed = 0;
        Iterator<World> worlds = Bukkit.getWorlds().iterator();
        while (worlds.hasNext())
        {
            removed += wipeEntities(worlds.next(), force);
        }

        return removed;
    }

    public int wipeEntities(World world, boolean force)
    {
        int removed = 0;

        boolean wipeExpl = ConfigEntry.ALLOW_EXPLOSIONS.getBoolean();
        Iterator<Entity> entities = world.getEntities().iterator();

        // Organise the entities in the world
        Map<Chunk, List<Entity>> cem = new HashMap<>();
        while (entities.hasNext())
        {
            final Entity entity = entities.next();

            // Explosives
            if (wipeExpl && Explosive.class.isAssignableFrom(entity.getClass()))
            {
                entity.remove();
                removed++;
                continue;
            }

            // Only wipeable entities can be wiped (duh!)
            if (!isWipeable(entity))
            {
                continue;
            }

            Chunk c = entity.getLocation().getChunk();
            List<Entity> cel = cem.get(c);
            if (cel == null)
            {
                cel = new ArrayList<>();
                cem.put(c, cel);
            }

            cel.add(entity);
        }

        // Now purge the entities if necessary
        for (Chunk c : cem.keySet())
        {
            List<Entity> cel = cem.get(c);

            if (!force && cel.size() < CHUNK_ENTITY_MAX)
            {
                continue;
            }

            // Too many entities in this chunk, wipe them all
            for (Entity e : cel)
            {
                if (e instanceof Item item)
                {
                    removed += item.getItemStack().getAmount();
                }
                else
                {
                    removed++;
                }

                e.remove();
            }
        }

        return removed;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event)
    {
        final Item entity = event.getEntity();

        // One task per spawned item, so a burst queues a batch
        // that all fires on the same tick. Anything that throws here therefore throws in bulk.
        plugin.getServer().getScheduler().runTaskLater(plugin, FTask.guard("EntityWiper/itemDespawn", () ->
        {
            if (entity.isValid())
                entity.remove();
        }), ITEM_DESPAWN_RATE);
    }
}
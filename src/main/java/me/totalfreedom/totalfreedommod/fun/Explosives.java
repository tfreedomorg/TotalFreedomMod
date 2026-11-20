package me.totalfreedom.totalfreedommod.fun;

import me.totalfreedom.api.FreedomAPI;
import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

public class Explosives extends FreedomService
{
    public Explosives(FreedomAPI plugin)
    {
        super(plugin);
    }

    @Override
    public void onStart()
    {
    }

    @Override
    public void onStop()
    {
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void explosiveArrows(ProjectileHitEvent event)
    {
        if (ConfigEntry.ALLOW_EXPLOSIONS.getBoolean())
        {
            final Projectile entity = event.getEntity();
            if (event.getEntity() instanceof AbstractArrow abstractArrow
                    && abstractArrow.getShooter() instanceof Player playerShooter
                    && plugin.ranks().hasPermission(playerShooter, "tfm.fun.explosives.arrows"))
            {
                float multiplier = 1.0F;

                if (abstractArrow instanceof Trident trident
                        && event.getHitBlock() != null)
                {
                    trident.getWorld().strikeLightning(trident.getLocation());
                    multiplier = 1.25F;
                }
                else if (abstractArrow instanceof SpectralArrow)
                {
                    multiplier = 2.0F;
                }

                entity.getWorld().createExplosion(entity.getLocation(), 2F * multiplier);
            }
        }
    }

    @EventHandler
    public void carrots(PlayerInteractEvent event)
    {
        if (event.getAction().isLeftClick()
                || !ConfigEntry.ALLOW_EXPLOSIONS.getBoolean()
                || event.getItem() == null)
        {
            return;
        }

        final Material type = event.getItem().getType();
        final Player player = event.getPlayer();

        boolean success = switch (type)
        {
            case CARROT -> carrot(player, type, 150, 25);
            case GOLDEN_CARROT -> carrot(player, type, 300, 100);
            default -> false;
        };

        if (success)
        {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void rods(PlayerInteractEvent event)
    {
        if (event.getAction() == Action.PHYSICAL
                || !ConfigEntry.ALLOW_EXPLOSIONS.getBoolean()
                || event.getItem() == null)
        {
            return;
        }

        final Material type = event.getItem().getType();
        final Player player = event.getPlayer();

        boolean success = switch (type)
        {
            case BLAZE_ROD -> blazeRod(player,
                    type,
                    event.getAction() == Action.RIGHT_CLICK_BLOCK ?
                            event.getClickedBlock() :
                            player.getTargetBlock(null, 120),
                    4.0F);
            case BREEZE_ROD -> blazeRod(player,
                    type,
                    event.getAction() == Action.RIGHT_CLICK_BLOCK ?
                            event.getClickedBlock() :
                            player.getTargetBlock(null, 240),
                    2.0F);
            default -> false;
        };

        if (success)
        {
            event.setCancelled(true);
        }
    }

    private boolean checkPermission(final Player player, final Material type)
    {
        return plugin.ranks().hasPermission(player, "tfm.fun.explosives." + type.name().toLowerCase());
    }

    private boolean blazeRod(final Player player,
                             final Material source,
                             final Block targetBlock,
                             final float power)
    {
        if (!checkPermission(player, source) || targetBlock == null)
        {
            return false;
        }

        player.getWorld().createExplosion(targetBlock.getLocation(), power, true);
        player.getWorld().strikeLightning(targetBlock.getLocation());

        return true;
    }

    private boolean carrot(final Player player, final Material source, final float maxDistance, double density)
    {
        if (!checkPermission(player, source))
        {
            return false;
        }

        final Location location = player.getLocation().clone();

        Vector playerPostion = location.toVector().add(new Vector(0.0, 1.65, 0.0));
        Vector playerDirection = location.getDirection().normalize();

        double distance = location.distance(player.getTargetBlock(null, Math.round(maxDistance)).getLocation());

        final List<Block> affected = new ArrayList<>();

        Block lastBlock = null;
        for (double offset = 0.0; offset <= distance; offset += (distance / density))
        {
            Block block = playerPostion.clone().add(playerDirection.clone().multiply(offset)).toLocation(player.getWorld()).getBlock();

            if (!block.equals(lastBlock))
            {
                if (block.isEmpty())
                {
                    affected.add(block);
                    block.setType(Material.TNT);
                }
                else
                {
                    break;
                }
            }

            lastBlock = block;
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () ->
        {
            for (Block tntBlock : affected)
            {
                TNTPrimed tnt = tntBlock.getWorld().spawn(tntBlock.getLocation(), TNTPrimed.class);
                tnt.setFuseTicks(5);
                tnt.setSource(player);
                tntBlock.setType(Material.AIR);
            }
        }, 30L);

        return true;
    }
}

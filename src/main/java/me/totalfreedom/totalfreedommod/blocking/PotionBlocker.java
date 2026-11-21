package me.totalfreedom.totalfreedommod.blocking;

import java.util.Collection;

import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.LingeringPotionSplashEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;

public class PotionBlocker extends FreedomService
{

    public static final int POTION_BLOCK_RADIUS_SQUARED = 20 * 20;

    public PotionBlocker(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
    }

    @Override
    protected void onStop()
    {
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onThrowPotion(PotionSplashEvent event)
    {
        if (isLethalPotion(event.getEntity()) || hasTooManyEffects(event.getEntity(), plugin.iv.getMaxPotionEffects()))
        {
            event.setCancelled(true);
            return;
        }

        ProjectileSource source = event.getEntity().getShooter();

        if (!(source instanceof Player))
        {
            event.setCancelled(true);
            return;
        }

        Player thrower = (Player) source;

        if (plugin.al.isAdmin(thrower))
        {
            return;
        }

        for (Player player : thrower.getWorld().getPlayers())
        {
            if (thrower.getLocation().distanceSquared(player.getLocation()) < POTION_BLOCK_RADIUS_SQUARED)
            {
                thrower.sendMessage(Component.text("You cannot use splash potions close to other players.", NamedTextColor.RED));
                event.setCancelled(true);
                return;
            }
        }

    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLingeringPotion(LingeringPotionSplashEvent event)
    {
        if (isLethalPotion(event.getEntity()) || hasTooManyEffects(event.getEntity(), plugin.iv.getMaxPotionEffects()))
        {
            event.setCancelled(true);
        }
    }

    private static boolean isLethalPotion(ThrownPotion potion)
    {
        if (potion == null)
        {
            return false;
        }
        ItemStack item = potion.getItem();
        if (item == null || !(item.getItemMeta() instanceof PotionMeta meta) || !meta.hasCustomEffects())
        {
            return false;
        }
        return hasLethalInstantEffect(meta.getCustomEffects());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event)
    {
        int cap = plugin.iv.getMaxPotionEffects();
        if (cap > 0
                && event.getEntity() instanceof Arrow arrow
                && arrow.hasCustomEffects()
                && arrow.getCustomEffects().size() > cap)
        {
            event.setCancelled(true);
        }
    }

    private static boolean hasTooManyEffects(ThrownPotion potion, int cap)
    {
        if (cap <= 0 || potion == null)
        {
            return false;
        }
        ItemStack item = potion.getItem();
        if (item == null || !(item.getItemMeta() instanceof PotionMeta meta) || !meta.hasCustomEffects())
        {
            return false;
        }
        return meta.getCustomEffects().size() > cap;
    }

    private static boolean hasLethalInstantEffect(Collection<PotionEffect> effects)
    {
        for (PotionEffect effect : effects)
        {
            if (isLethalInstant(effect.getType(), effect.getAmplifier()))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean isLethalInstant(PotionEffectType type, int amplifier)
    {
        if (type != PotionEffectType.INSTANT_HEALTH && type != PotionEffectType.INSTANT_DAMAGE)
        {
            return false;
        }
        return amplifier < 0 || amplifier > 31 || (4 << (amplifier & 31)) <= 0;
    }

}

package me.totalfreedom.totalfreedommod;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.projectiles.ProjectileSource;

import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.util.FUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

public class PotionSpy extends FreedomService
{
    private static final long POTION_SPY_RESET_TIMEOUT = 60000L;

    private Map<Player, Pair<Integer, Long>> offenders = null;

    public PotionSpy(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        this.offenders = new HashMap<>();
    }

    @Override
    protected void onStop()
    {
        this.offenders = null;
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPotionThrown(ProjectileLaunchEvent e)
    {
        // Make sure our list is initialized
        if (this.offenders == null)
            this.offenders = new HashMap<>();

        // Get what was thrown
        final Projectile projectile = e.getEntity();
        // Make sure it's a potion
        if (!(projectile instanceof final ThrownPotion potion))
            return;

        // Find out what threw the potion
        final ProjectileSource source = projectile.getShooter();
        // Make sure it's a player
        if (!(source instanceof final Player thrower))
            return;
        
        // Grab old data about the offender, if it exists
        final Pair<Integer, Long> offenderData = offenders.getOrDefault(thrower, Pair.of(0, System.currentTimeMillis()));
        final int amount = offenderData.getLeft() + 1;
        final long lastTime = offenderData.getRight();

        // Update the offender's information
        offenders.put(thrower, Pair.of(
            System.currentTimeMillis() - lastTime < POTION_SPY_RESET_TIMEOUT ? amount : 0,
            System.currentTimeMillis()));

        plugin.al.getOnlineAdmins().stream()
                .filter(player -> {
                    final PlayerData data = plugin.pl.getData(player);
                    return data != null && data.isPotionSpy();
                })
                .filter(_ -> {
                    final double lg = Math.log(amount) / Math.log(3.0);
                    return Math.abs(lg - Math.floor(lg)) < 0.01;
                })
                .forEach(player ->
                        FUtil.playerMsg(
                                player,
                                Component.text(thrower.getName()).color(NamedTextColor.GRAY)
                                        .append(amount == 1
                                                ? Component.text(" threw a ")
                                                .append(potion.getItem().displayName())
                                                : Component.text(" has thrown ")
                                                .append(Component.text(String.valueOf(amount)))
                                                .append(Component.text(" potions")))
                                        .append(plugin.esb.isEssentialsEnabled()
                                                ? Component.text(" (")
                                                .append(Component.text("click to teleport")
                                                        .clickEvent(ClickEvent.runCommand(String.format(
                                                                "tppos %s %s %s 0 0 %s",
                                                                thrower.getX(),
                                                                thrower.getY(),
                                                                thrower.getZ(),
                                                                thrower.getWorld().getName()
                                                        ))))
                                                .append(Component.text(")"))
                                                : Component.text(String.format(
                                                " (at %d, %d, %d in %s)",
                                                (int) thrower.getX(),
                                                (int) thrower.getY(),
                                                (int) thrower.getZ(),
                                                thrower.getWorld().getName()
                                        )))
                        )
                );
    }
}

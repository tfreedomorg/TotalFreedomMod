package me.totalfreedom.totalfreedommod.fun;

import me.totalfreedom.api.FreedomAPI;
import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.util.FUtil;
import net.kyori.adventure.key.Key;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Random;

public class Clownfish extends FreedomService
{
    private final Random random = new Random();

    public Clownfish(FreedomAPI plugin)
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

    @EventHandler(ignoreCancelled = true)
    public void whee(PlayerInteractEvent event)
    {
        if (event.getAction().isLeftClick())
        {
            return;
        }

        final Player player = event.getPlayer();
        final ItemStack itemStack = event.getItem();

        if (itemStack != null
                && itemStack.getType() == Material.TROPICAL_FISH
                && plugin.ranks().hasPermission(player, "tfm.fun.clownfish"))
        {
            event.setCancelled(true);

            final int radius = 5;
            final int strength = 4;

            boolean didHit = false;

            final Location playerLoc = player.getLocation();
            final Vector playerLocVec = playerLoc.toVector();

            final List<Player> players = player.getWorld().getPlayers();
            for (final Player target : players)
            {
                if (target == player)
                {
                    continue;
                }

                final Location targetPos = target.getLocation();
                final Vector targetPosVec = targetPos.toVector();

                try
                {
                    if (targetPosVec.distanceSquared(playerLocVec) < (radius * radius))
                    {
                        FUtil.setFlying(target, false);
                        target.setVelocity(targetPosVec.subtract(playerLocVec).normalize().multiply(strength));
                        playClownfishSounds(target, targetPos);
                        didHit = true;
                    }
                }
                catch (IllegalArgumentException ex)
                {
                }
            }

            if (didHit)
            {
                playClownfishSounds(player, playerLoc);
            }
        }
    }

    private void playClownfishSounds(Player listener, Location location)
    {
        for (Sound sound : Registry.SOUND_EVENT)
        {
            final Key key = Registry.SOUND_EVENT.getKey(sound);
            if (key != null && key.asString().contains("hit"))
            {
                listener.playSound(randomOffset(location, 2.0), sound, SoundCategory.MASTER, 1.0F, randomDoubleRange(0.5, 2.0).floatValue());
            }
        }
    }

    private Location randomOffset(Location a, double magnitude)
    {
        return a.clone().add(randomDoubleRange(-1.0, 1.0) * magnitude, randomDoubleRange(-1.0, 1.0) * magnitude, randomDoubleRange(-1.0, 1.0) * magnitude);
    }

    private Double randomDoubleRange(double min, double max)
    {
        return min + (random.nextDouble() * (max - min));
    }
}

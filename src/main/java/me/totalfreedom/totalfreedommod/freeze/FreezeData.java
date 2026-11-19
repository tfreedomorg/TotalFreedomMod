package me.totalfreedom.totalfreedommod.freeze;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;

import static me.totalfreedom.totalfreedommod.player.PlayerData.AUTO_PURGE_TICKS;

public class FreezeData
{

    private final PlayerData playerData;
    private Location location = null;
    private BukkitTask unfreeze = null;

    public FreezeData(PlayerData playerData)
    {
        this.playerData = playerData;
    }

    public Location getLocation()
    {
        return location;
    }

    public boolean isFrozen()
    {
        return unfreeze != null;
    }

    public void setFrozen(boolean freeze)
    {
        final Player player = playerData.getPlayer();
        if (player == null)
        {
            FLog.info("Could not freeze {}. Player not online!", playerData.getUsername());
            return;
        }

        if (playerData.isFrozen() != freeze)
        {
            playerData.setFrozen(freeze);
            playerData.getPlugin().savePlayerData(playerData);
        }

        FUtil.cancel(unfreeze);
        unfreeze = null;
        location = null;

        if (!freeze)
        {
            if (playerData.getPlayer().getGameMode() != GameMode.CREATIVE)
            {
                FUtil.setFlying(player, false);
            }

            return;
        }

        location = player.getLocation(); // Blockify location
        FUtil.setFlying(player, true); // Avoid infinite falling

        if (playerData.getPlugin().players().isImpostor(player))
        {
            return; // Don't run unfreeze task for impostors
        }

        unfreeze = playerData.getPlugin().getServer().getScheduler().runTaskLater(playerData.getPlugin(), () ->
        {
            FUtil.adminAction("TotalFreedom", "Unfreezing " + player.getName(), false);
            setFrozen(false);
        }, AUTO_PURGE_TICKS);
    }

}

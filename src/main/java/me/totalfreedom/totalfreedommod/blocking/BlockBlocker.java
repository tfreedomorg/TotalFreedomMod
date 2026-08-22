package me.totalfreedom.totalfreedommod.blocking;

import org.bukkit.Material;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.blocking.sign.SignBlocks;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;

public class BlockBlocker extends FreedomService
{

    public BlockBlocker(TotalFreedomMod plugin)
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

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event)
    {
        final Player player = event.getPlayer();

        if (SignBlocks.isSignBlock(event.getBlockPlaced()))
        {
            if (signPlacementBlocked())
            {
                player.getInventory().setItem(player.getInventory().getHeldItemSlot(), new ItemStack(Material.COOKIE, 1));
                player.sendMessage(Component.text("Sign placement is currently disabled.", NamedTextColor.GRAY));

                event.setCancelled(true);
            }
            return;
        }

        switch (event.getBlockPlaced().getType())
        {
            case LAVA:
            {
                if (ConfigEntry.ALLOW_LAVA_PLACE.getBoolean())
                {
                    FLog.info(String.format("%s placed lava @ %s", player.getName(), FUtil.formatLocation(event.getBlock().getLocation())));

                    player.getInventory().clear(player.getInventory().getHeldItemSlot());
                }
                else
                {
                    player.getInventory().setItem(player.getInventory().getHeldItemSlot(), new ItemStack(Material.COOKIE, 1));
                    player.sendMessage(Component.text("Lava placement is currently disabled.", NamedTextColor.GRAY));

                    event.setCancelled(true);
                }
                break;
            }
            case WATER:
            {
                if (ConfigEntry.ALLOW_WATER_PLACE.getBoolean())
                {
                    FLog.info(String.format("%s placed water @ %s", player.getName(), FUtil.formatLocation(event.getBlock().getLocation())));

                    player.getInventory().clear(player.getInventory().getHeldItemSlot());
                }
                else
                {
                    player.getInventory().setItem(player.getInventory().getHeldItemSlot(), new ItemStack(Material.COOKIE, 1));
                    player.sendMessage(Component.text("Water placement is currently disabled.", NamedTextColor.GRAY));

                    event.setCancelled(true);
                }
                break;
            }
            case FIRE:
            {
                if (ConfigEntry.ALLOW_FIRE_PLACE.getBoolean())
                {
                    FLog.info(String.format("%s placed fire @ %s", player.getName(), FUtil.formatLocation(event.getBlock().getLocation())));

                    player.getInventory().clear(player.getInventory().getHeldItemSlot());
                }
                else
                {
                    player.getInventory().setItem(player.getInventory().getHeldItemSlot(), new ItemStack(Material.COOKIE, 1));
                    player.sendMessage(Component.text("Fire placement is currently disabled.", NamedTextColor.GRAY));

                    event.setCancelled(true);
                }
                break;
            }
            case TNT:
            {
                if (ConfigEntry.ALLOW_EXPLOSIONS.getBoolean())
                {
                    FLog.info(String.format("%s placed TNT @ %s", player.getName(), FUtil.formatLocation(event.getBlock().getLocation())));

                    player.getInventory().clear(player.getInventory().getHeldItemSlot());
                }
                else
                {
                    player.getInventory().setItem(player.getInventory().getHeldItemSlot(), new ItemStack(Material.COOKIE, 1));

                    player.sendMessage(Component.text("TNT is currently disabled.", NamedTextColor.GRAY));
                    event.setCancelled(true);
                }
                break;
            }
            case SPAWNER:
            {
                if (ConfigEntry.DISABLE_SPAWNER_PLACE.getBoolean())
                {
                    player.sendMessage(Component.text("Spawners are currently disabled.", NamedTextColor.GRAY));

                    event.setCancelled(true);
                }
                break;
            }
            case STRUCTURE_BLOCK:
            case STRUCTURE_VOID:
            {
                player.sendMessage(Component.text("Structure blocks are disabled.", NamedTextColor.GRAY));

                event.setCancelled(true);
                break;
            }
            default: break;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event)
    {
        if (!(event.getEntity() instanceof FallingBlock falling))
        {
            return;
        }

        if (fallingBlocksBlocked())
        {
            event.setCancelled(true);
            return;
        }

        if (fallingSignsBlocked() && SignBlocks.isSignMaterial(falling.getBlockData().getMaterial()))
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event)
    {
        if (!(event.getEntity() instanceof FallingBlock))
        {
            return;
        }

        if (fallingBlocksBlocked())
        {
            event.setCancelled(true);
            event.getEntity().remove();
            return;
        }

        if (fallingSignsBlocked() && SignBlocks.isSignBlock(event.getBlock()))
        {
            event.setCancelled(true);
            event.getEntity().remove();
        }
    }

    private boolean signPlacementBlocked()
    {
        return Boolean.FALSE.equals(ConfigEntry.ALLOW_SIGN_PLACE.getBoolean());
    }

    private boolean fallingSignsBlocked()
    {
        return Boolean.FALSE.equals(ConfigEntry.ALLOW_FALLING_SIGNS.getBoolean());
    }

    private boolean fallingBlocksBlocked()
    {
        return Boolean.FALSE.equals(ConfigEntry.ALLOW_FALLING_BLOCKS.getBoolean());
    }

}

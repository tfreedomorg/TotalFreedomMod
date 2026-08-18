package me.totalfreedom.totalfreedommod.bridge;

import java.util.Collections;
import java.util.List;
import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

public class CoreProtectBridge extends FreedomService
{
    private static final int ROLLBACK_TIME = 2592000;
    private CoreProtectAPI coreProtectAPI;
    private boolean integrationEnabledAnnounced;

    public CoreProtectBridge(final TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        if (!activateCoreProtectIntegration())
        {
            if (server.getPluginManager().getPlugin("CoreProtect") == null)
            {
                FLog.warning("CoreProtect is unavailable; block logging and rollback integration are disabled.");
            }
            else
            {
                FLog.info("Waiting for CoreProtect to enable.");
            }
        }
    }

    @Override
    protected void onStop()
    {
        coreProtectAPI = null;
        integrationEnabledAnnounced = false;
    }

    @EventHandler
    public void activateCoreProtectAfterEnable(final PluginEnableEvent event)
    {
        if ("CoreProtect".equals(event.getPlugin().getName()))
            activateCoreProtectIntegration();
    }

    @EventHandler
    public void emulateLogStick(PlayerInteractEvent event)
    {
        final Player player = event.getPlayer();

        if (!event.hasItem()
                || !isEnabled()
                || event.getItem().getType() != Material.STICK
                || !plugin.al.isAdmin(player))
        {
            return;
        }

        final CoreProtectAPI api = getCoreProtectAPI();
        final Block block = player.getTargetBlock(null, 5);

        // Cancel the event
        event.setCancelled(true);

        // Query the CoreProtect API asynchronously. Not sure how much of a big deal this is, but it couldn't hurt
        server.getScheduler().runTaskAsynchronously(plugin, () ->
        {
            final Location location = block.getLocation();
            final List<String[]> results = api.blockLookup(block, -1);

            if (results.isEmpty())
            {
                FUtil.playerMsg(player, "No block edits at that location.");
                return;
            }

            FUtil.playerMsg(player, Component.text("Block edits at (", NamedTextColor.BLUE)
                    .append(Component.text("x" + location.getBlockX() + ", y" + location.getBlockY() + ", z" + location.getBlockZ(), NamedTextColor.WHITE))
                    .append(Component.text("):", NamedTextColor.BLUE)));
            results.stream().map(api::parseResult).filter(parsed -> parsed.getActionId() < 2).forEach(result ->
            {
                final String capitalized = result.getType().toString().charAt(0)
                        + result.getType().toString().toLowerCase().substring(1);

                FUtil.playerMsg(player, String.format(" - %s %s %s",
                        result.getPlayer(),
                        result.getActionId() == 0 ? "broke" : "placed",
                        capitalized),
                        NamedTextColor.BLUE);
            });
        });
    }

    public boolean isEnabled()
    {
        return getCoreProtectAPI() != null;
    }

    public boolean rollback(String username)
    {
        CoreProtectAPI api = getCoreProtectAPI();

        if (api == null)
        {
            return false;
        }

        runHistoryMutation(() -> api.performRollback(
                ROLLBACK_TIME,
                Collections.singletonList(username),
                null,
                null,
                null,
                null,
                0,
                null));

        return true;
    }

    public boolean restore(String username)
    {
        CoreProtectAPI api = getCoreProtectAPI();

        if (api == null)
        {
            return false;
        }

        runHistoryMutation(() -> api.performRestore(
                ROLLBACK_TIME,
                Collections.singletonList(username),
                null,
                null,
                null,
                null,
                0,
                null));

        return true;
    }

    private CoreProtectAPI getCoreProtectAPI()
    {
        if (coreProtectAPI != null && coreProtectAPI.isEnabled())
        {
            return coreProtectAPI;
        }

        activateCoreProtectIntegration();
        return coreProtectAPI;
    }

    private boolean activateCoreProtectIntegration()
    {
        final CoreProtectAPI discoveredApi = findCoreProtectAPI();
        if (discoveredApi == null)
        {
            coreProtectAPI = null;
            return false;
        }

        coreProtectAPI = discoveredApi;
        if (!integrationEnabledAnnounced)
        {
            integrationEnabledAnnounced = true;
            FLog.info("CoreProtect integration enabled.");
        }
        return true;
    }

    private void runHistoryMutation(final Runnable mutation)
    {
        server.getScheduler().runTaskAsynchronously(plugin, () ->
        {
            try
            {
                mutation.run();
            }
            catch (Exception ex)
            {
                FLog.severe(ex);
            }
        });
    }

    private CoreProtectAPI findCoreProtectAPI()
    {
        try
        {
            Plugin coreProtectPlugin = server.getPluginManager().getPlugin("CoreProtect");

            if (!(coreProtectPlugin instanceof CoreProtect coreProtect) || !coreProtect.isEnabled())
            {
                return null;
            }

            CoreProtectAPI api = coreProtect.getAPI();

            if (api == null || !api.isEnabled())
            {
                return null;
            }

            return api;
        }
        catch (Exception ex)
        {
            FLog.severe(ex);
            return null;
        }
    }
}

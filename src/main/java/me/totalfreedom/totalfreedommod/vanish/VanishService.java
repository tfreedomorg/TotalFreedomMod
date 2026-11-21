package me.totalfreedom.totalfreedommod.vanish;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerListPingEvent;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;

/**
 * Single source of truth for vanish state.
 * <p>
 * Vanish here is session-only: a player is either in {@link #vanished} or not, nothing is
 * persisted across relogs. Everything else, including entity/tab-list visibility, join/quit
 * messages, ping sample/count, and read-only enforcement, is derived from that set through
 * {@link #isVanished} and {@link #canSee}, so callers should never track their own vanish flag.
 */
public class VanishService extends FreedomService
{
    public static final String SEE_PERMISSION = "tfm.vanish.see";
    public static final String BYPASS_PERMISSION = "tfm.vanish.bypass";

    private final Set<UUID> vanished = new HashSet<>();

    public VanishService(TotalFreedomMod plugin)
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
        for (UUID uuid : new HashSet<>(vanished))
        {
            final Player player = server.getPlayer(uuid);
            if (player != null)
            {
                unvanish(player);
            }
        }
        vanished.clear();
    }

    public boolean isVanished(Player player)
    {
        return isVanished(player.getUniqueId());
    }

    public boolean isVanished(UUID uuid)
    {
        return vanished.contains(uuid);
    }

    /**
     * Whether {@code viewer} is allowed to know {@code target} is online. Console and other
     * non-player senders always can, as can a vanished player looking at themselves.
     * <p>
     * Every regular player on this server is vanilla-op'd (see LoginProcess/RankManager#ensureOp),
     * so this deliberately goes through RankManager rather than Player#hasPermission - a raw
     * Bukkit permission check on an unregistered tfm.* node resolves true for any op, which is
     * everyone.
     */
    public boolean canSee(CommandSender viewer, Player target)
    {
        if (!isVanished(target))
        {
            return true;
        }

        if (!(viewer instanceof Player viewingPlayer))
        {
            return true;
        }

        return viewingPlayer.getUniqueId().equals(target.getUniqueId()) || plugin.rm.hasPermission(viewer, SEE_PERMISSION);
    }

    public List<Player> visiblePlayersFor(CommandSender viewer)
    {
        return server.getOnlinePlayers()
                      .stream()
                      .filter(target -> canSee(viewer, target))
                      .map(target -> (Player) target)
                      .toList();
    }

    public void vanish(Player player)
    {
        if (!vanished.add(player.getUniqueId()))
        {
            return;
        }
        applyVisibility(player);
    }

    public void unvanish(Player player)
    {
        if (!vanished.remove(player.getUniqueId()))
        {
            return;
        }
        applyVisibility(player);
    }

    public boolean toggle(Player player)
    {
        if (isVanished(player))
        {
            unvanish(player);
            return false;
        }

        vanish(player);
        return true;
    }

    /**
     * Pushes {@code subject}'s current vanish state to every online viewer's client.
     */
    private void applyVisibility(Player subject)
    {
        for (Player viewer : server.getOnlinePlayers())
        {
            if (viewer.getUniqueId().equals(subject.getUniqueId()))
            {
                continue;
            }

            if (canSee(viewer, subject))
            {
                viewer.showPlayer(plugin, subject);
            }
            else
            {
                viewer.hidePlayer(plugin, subject);
            }
        }
    }

    /**
     * Removes vanished players from the server list ping sample and count, so a vanished player
     * never nudges the online number or shows up in the hover list for a client that hasn't
     * joined yet. Paper always fires PaperServerListPingEvent under the plain ServerListPingEvent
     * type callers register for; that subtype is required here since the base event's player-count
     * field has no setter and its Iterable<Player> view was deprecated without replacement.
     */
    public void filterServerPing(ServerListPingEvent event)
    {
        if (vanished.isEmpty() || !(event instanceof PaperServerListPingEvent pingEvent))
        {
            return;
        }

        pingEvent.getListedPlayers().removeIf(listed -> isVanished(listed.id()));

        final long onlineVanished = vanished.stream()
                                             .filter(uuid -> server.getPlayer(uuid) != null)
                                             .count();
        pingEvent.setNumPlayers((int) (pingEvent.getNumPlayers() - onlineVanished));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        if (vanished.isEmpty())
        {
            return;
        }

        final Player joined = event.getPlayer();
        for (UUID uuid : vanished)
        {
            final Player vanishedPlayer = server.getPlayer(uuid);
            if (vanishedPlayer != null && !canSee(joined, vanishedPlayer))
            {
                joined.hidePlayer(plugin, vanishedPlayer);
            }
        }
    }

    /**
     * MONITOR here, and this service is registered after JoinLeaveMessages, so its quit-message
     * broadcast (also MONITOR) still sees the vanished state before this cleans it up.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        vanished.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event)
    {
        if (isReadOnly(event.getPlayer()))
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event)
    {
        if (isReadOnly(event.getPlayer()))
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event)
    {
        if (isReadOnly(event.getPlayer()))
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event)
    {
        final Entity entity = event.getEntity();
        if (entity instanceof Player player && isReadOnly(player))
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event)
    {
        if (event.getDamager() instanceof Player player && isReadOnly(player))
        {
            event.setCancelled(true);
        }
    }

    /**
     * Vanished staff are read-only by default so investigations don't leave a trail and region
     * protection/anticheat/economy plugins don't develop gaps around them. tfm.vanish.bypass
     * opts a sender back into normal interaction while vanished. Routed through RankManager for
     * the same reason as canSee - raw Player#hasPermission would pass for every op'd regular
     * player.
     */
    private boolean isReadOnly(Player player)
    {
        return isVanished(player) && !plugin.rm.hasPermission(player, BYPASS_PERMISSION);
    }
}

package me.totalfreedom.totalfreedommod.vanish;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.server.TabCompleteEvent;

public class VanishManager extends FreedomService
{
    /** UUIDs of currently vanished players. */
    private final Set<UUID> vanishedPlayers = ConcurrentHashMap.newKeySet();

    public VanishManager(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        // Nothing to initialise — state is purely runtime.
    }

    @Override
    protected void onStop()
    {
        // Un-vanish everyone so they appear normally after reload.
        for (UUID uuid : vanishedPlayers)
        {
            final Player player = server.getPlayer(uuid);
            if (player != null)
            {
                setVanishedState(player, false);
            }
        }
        vanishedPlayers.clear();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public boolean isVanished(Player player)
    {
        return vanishedPlayers.contains(player.getUniqueId());
    }

    public boolean isVanished(UUID uuid)
    {
        return vanishedPlayers.contains(uuid);
    }

    /**
     * Toggle vanish for a player and return the new state.
     */
    public boolean toggle(Player player)
    {
        final boolean newState = !isVanished(player);
        setVanished(player, newState);
        return newState;
    }

    /**
     * Set vanish state for a player explicitly.
     */
    public void setVanished(Player player, boolean vanish)
    {
        if (vanish)
        {
            vanishedPlayers.add(player.getUniqueId());
        }
        else
        {
            vanishedPlayers.remove(player.getUniqueId());
        }

        setVanishedState(player, vanish);
    }

    /** Returns an unmodifiable view of currently vanished UUIDs. */
    public Set<UUID> getVanishedUUIDs()
    {
        return Collections.unmodifiableSet(vanishedPlayers);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Apply or remove all vanish effects for a player.
     * This handles visibility (hide/show from all other players) and
     * removes the player from the TAB list by clearing their list name.
     */
    private void setVanishedState(Player player, boolean vanish)
    {
        if (vanish)
        {
            // Hide the vanished player from every non-admin online player.
            for (Player other : server.getOnlinePlayers())
            {
                if (other.equals(player))
                {
                    continue;
                }
                if (!plugin.al.isAdmin(other))
                {
                    other.hidePlayer(plugin, player);
                }
            }

            // Remove from TAB list by setting an empty list name.
            player.playerListName(Component.empty());
        }
        else
        {
            // Show the player to everyone again.
            for (Player other : server.getOnlinePlayers())
            {
                if (other.equals(player))
                {
                    continue;
                }
                other.showPlayer(plugin, player);
            }

            // Restore TAB list name — the TabList service will re-populate it on its
            // next update cycle, so we just reset to the default (player's real name).
            player.playerListName(null);
        }
    }

    // -------------------------------------------------------------------------
    // Event handlers
    // -------------------------------------------------------------------------

    /**
     * When a new player joins, ensure vanished admins are properly hidden
     * from them (and that they can see vanished admins if they are an admin).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        final Player joiningPlayer = event.getPlayer();

        // Hide all currently vanished players from this new player (if they're not an admin).
        if (!plugin.al.isAdmin(joiningPlayer))
        {
            for (UUID uuid : vanishedPlayers)
            {
                final Player vanished = server.getPlayer(uuid);
                if (vanished != null && !vanished.equals(joiningPlayer))
                {
                    joiningPlayer.hidePlayer(plugin, vanished);
                }
            }
        }

        // If the joining player is themselves vanished (e.g., after a reload),
        // hide them from every non-admin.
        if (isVanished(joiningPlayer))
        {
            for (Player other : server.getOnlinePlayers())
            {
                if (other.equals(joiningPlayer))
                {
                    continue;
                }
                if (!plugin.al.isAdmin(other))
                {
                    other.hidePlayer(plugin, joiningPlayer);
                }
            }
            joiningPlayer.playerListName(Component.empty());
        }
    }

    /**
     * When a vanished player disconnects, remove them from the vanished set.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        vanishedPlayers.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Block non-admin players from teleporting to vanished players.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event)
    {
        final Player player = event.getPlayer();

        // Admins are permitted to teleport to vanished players.
        if (plugin.al.isAdmin(player))
        {
            return;
        }

        // Check if the destination coincides with a vanished player's location.
        for (UUID uuid : vanishedPlayers)
        {
            final Player vanished = server.getPlayer(uuid);
            if (vanished == null)
            {
                continue;
            }

            if (vanished.getLocation().equals(event.getTo())
                || isNearVanishedTP(event, vanished))
            {
                event.setCancelled(true);
                player.sendMessage(net.kyori.adventure.text.Component.text(
                    "You cannot teleport to that player.", net.kyori.adventure.text.format.NamedTextColor.RED));
                return;
            }
        }
    }

    /**
     * Block commands that target vanished players by name (e.g. /tp, /tpa).
     * Non-admin players attempting to target a vanished player by name will be denied.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event)
    {
        final Player sender = event.getPlayer();

        // Admins can target vanished players.
        if (plugin.al.isAdmin(sender))
        {
            return;
        }

        final String message = event.getMessage().toLowerCase();

        // Only intercept teleport-related commands.
        if (!message.startsWith("/tp")
            && !message.startsWith("/tpa")
            && !message.startsWith("/tphere")
            && !message.startsWith("/teleport"))
        {
            return;
        }

        // Check if any argument token matches a vanished player's name.
        final String[] parts = message.split("\\s+");
        for (int i = 1; i < parts.length; i++)
        {
            final String arg = parts[i];
            for (UUID uuid : vanishedPlayers)
            {
                final Player vanished = server.getPlayer(uuid);
                if (vanished != null && vanished.getName().equalsIgnoreCase(arg))
                {
                    event.setCancelled(true);
                    sender.sendMessage(net.kyori.adventure.text.Component.text(
                        "Player not found.", net.kyori.adventure.text.format.NamedTextColor.RED));
                    return;
                }
            }
        }
    }

    /**
     * Strip vanished player names from TAB completions for non-admin players.
     * This prevents players from discovering vanished admins via the TAB key.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTabComplete(TabCompleteEvent event)
    {
        // Only filter for player senders who are not admins.
        if (!(event.getSender() instanceof Player sender))
        {
            return;
        }
        if (plugin.al.isAdmin(sender))
        {
            return;
        }

        // Build a set of vanished names for fast lookup.
        final java.util.Set<String> vanishedNames = new java.util.HashSet<>();
        for (UUID uuid : vanishedPlayers)
        {
            final Player vanished = server.getPlayer(uuid);
            if (vanished != null)
            {
                vanishedNames.add(vanished.getName().toLowerCase());
            }
        }

        if (vanishedNames.isEmpty())
        {
            return;
        }

        // Remove any completion that exactly matches a vanished player's name.
        event.getCompletions().removeIf(completion ->
            vanishedNames.contains(completion.toLowerCase()));
    }

    /**
     * Rough proximity check: was the teleport destination suspiciously close
     * to a vanished player (within 1 block, accounting for potential rounding)?
     */
    private boolean isNearVanishedTP(PlayerTeleportEvent event, Player vanished)
    {
        if (event.getTo() == null)
        {
            return false;
        }
        if (!event.getTo().getWorld().equals(vanished.getWorld()))
        {
            return false;
        }
        return event.getTo().distanceSquared(vanished.getLocation()) < 2.0;
    }
}

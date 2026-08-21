package me.totalfreedom.totalfreedommod;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.admin.Admin;
import me.totalfreedom.totalfreedommod.cmd.MessageUtils;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FSync;
import me.totalfreedom.totalfreedommod.util.FUtil;

public class LoginProcess extends FreedomService 
{

    public static final int DEFAULT_PORT = 25565;
    public static final int MIN_USERNAME_LENGTH = 2;
    public static final int MAX_USERNAME_LENGTH = 20;
    public static final Pattern USERNAME_REGEX = Pattern.compile("^[\\w\\d_]{3,20}$");
    public List<UUID> TELEPORT_ON_JOIN = new ArrayList<>();
    public List<UUID> CLEAR_ON_JOIN = new ArrayList<>();

    public LoginProcess(TotalFreedomMod plugin) 
    {
        super(plugin);
    }

    @Override
    protected void onStart() {}

    @Override
    protected void onStop() {}

    /*
     * Banning and Permban checks are their respective services
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerPreLogin(AsyncPlayerPreLoginEvent event) 
    {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) 
        {
            return;
        }

        final String ip = event.getAddress().getHostAddress().trim();
        final boolean isAdmin;
        if (ConfigEntry.ADMINLIST_USE_UUID_ONLY.getBoolean()) 
        {
            final Admin uuidAdmin = plugin.al.getAdminByUuid(event.getUniqueId());
            isAdmin = uuidAdmin != null && uuidAdmin.isActive();
        } 
        else 
        {
            isAdmin = plugin.al.getEntryByIp(ip) != null;
        }

        server.getOnlinePlayers()
              .stream()
              .filter(p -> p.getName().equalsIgnoreCase(event.getName()))
              .findAny()
              .ifPresent(p -> 
                    {
                        if (isAdmin)
                        {
                            event.allow();
                            FSync.playerKick(p, "An admin just logged in with the username you are using.");
                            return;
                        }

                        event.disallow(
                            AsyncPlayerPreLoginEvent.Result.KICK_OTHER, 
                            MessageUtils.parse("Your username is already logged into this server.")
                        );
                    });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerLogin(AsyncPlayerPreLoginEvent event) 
    {
        final PlayerProfile player = event.getPlayerProfile();
        final String username = player.getName();
        final String ip = event.getConnection().getClientAddress().getAddress().getHostAddress().trim();

        // Check username length
        if (username.length() < MIN_USERNAME_LENGTH || username.length() > MAX_USERNAME_LENGTH) 
        {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, MessageUtils.parse("<red>Your username is an invalid length (must be between 3 and 20 characters long)."));
            return;
        }

        // Check username characters
        if (!USERNAME_REGEX.matcher(username).find()) 
        {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, MessageUtils.parse("Your username contains invalid characters."));
            return;
        }

        // Check force-IP match
        if (ConfigEntry.FORCE_IP_ENABLED.getBoolean()) 
        {
            final String hostname = event.getHostname().replace("\u0000FML\u0000", ""); // Forge fix - https://github.com/TotalFreedom/TotalFreedomMod/issues/493
            final String connectAddress = ConfigEntry.SERVER_ADDRESS.getString();
            final int connectPort = server.getPort();

            if (!hostname.equalsIgnoreCase(connectAddress + ":" + connectPort) && !hostname.equalsIgnoreCase(connectAddress + ".:" + connectPort)) 
            {
                final int forceIpPort = ConfigEntry.FORCE_IP_PORT.getInteger();
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        MessageUtils.parse(
                            ConfigEntry.FORCE_IP_KICKMSG.getString().replace(
                                "%address%", 
                                ConfigEntry.SERVER_ADDRESS.getString() + (forceIpPort == DEFAULT_PORT ? "" : ":" + forceIpPort)))
                            );
                return;
            }
        }

        // Check if player is admin
        // Not safe to use TFM_Util.isSuperAdmin(player) because player.getAddress() will return a null until after player login.
        final boolean isAdmin;
        if (ConfigEntry.ADMINLIST_USE_UUID_ONLY.getBoolean()) 
        {
            final Admin uuidAdmin = plugin.al.getAdminByUuid(player.getId());
            isAdmin = uuidAdmin != null && uuidAdmin.isActive();
        } 
        else 
        {
            isAdmin = plugin.al.getEntryByIp(ip) != null;
        }

        // Validation below this point
        if (isAdmin) // Player is superadmin
        {
            // Force-allow log in
            event.allow();

            int count = server.getOnlinePlayers().size();
            if (count >= server.getMaxPlayers()) 
            {
                for (Player onlinePlayer : server.getOnlinePlayers()) 
                {
                    if (!plugin.al.isAdmin(onlinePlayer)) 
                    {
                        onlinePlayer.kick(net.kyori.adventure.text.Component.text("You have been kicked to free up room for an admin."));
                        count--;
                    }

                    if (count < server.getMaxPlayers()) 
                    {
                        break;
                    }
                }
            }

            if (count >= server.getMaxPlayers()) 
            {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, MessageUtils.parse("The server is full and a player could not be kicked, sorry!"));
                return;
            }

            return;
        }

        // Player is not an admin
        // Server full check
        if (server.getOnlinePlayers().size() >= server.getMaxPlayers()) 
        {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, MessageUtils.parse("Sorry, but this server is full."));
            return;
        }

        // Admin-only mode
        if (ConfigEntry.ADMIN_ONLY_MODE.getBoolean()) 
        {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, MessageUtils.parse("Server is temporarily open to admins only."));
            return;
        }

        // Lockdown mode
        if (ConfigEntry.LOCKDOWN_MODE.getBoolean()) 
        {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, MessageUtils.parse("Server is currently in lockdown mode."));
            return;
        }
    }   

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) 
    {
        final Player player = event.getPlayer();
        final String ip = player.getAddress().getAddress().getHostAddress().trim();

        final boolean isAdmin;
        if (ConfigEntry.ADMINLIST_USE_UUID_ONLY.getBoolean()) 
        {
            final Admin uuidAdmin = plugin.al.getAdminByUuid(player.getUniqueId());
            isAdmin = uuidAdmin != null && uuidAdmin.isActive();
        } 
        else 
        {
            isAdmin = plugin.al.getEntryByIp(ip) != null;
        }
        
        if (ConfigEntry.AUTO_OP_ENABLED.getBoolean() && !isAdmin) 
        {
            player.setOp(true);
        }

        if (TELEPORT_ON_JOIN.contains(player.getUniqueId()) || ConfigEntry.AUTO_TP.getBoolean()) 
        {
            final int x = ThreadLocalRandom.current().nextInt(-10_000, 10_001);
            final int z = ThreadLocalRandom.current().nextInt(-10_000, 10_001);
            final World world = player.getWorld();

            world.getChunkAtAsync(x >> 4, z >> 4, true).thenAccept(chunk ->
            {
                final int y = world.getHighestBlockYAt(x, z) + 1;
                final Location location = new Location(world, x + 0.5, y, z + 0.5);

                player.teleportAsync(location).thenAccept(success ->
                {
                    if (success) {
                        player.sendMessage(MessageUtils.parse("<aqua>You have been teleported to a random location automatically."));
                    }
                });
            });
        }

        if (CLEAR_ON_JOIN.stream().anyMatch(name -> name.equals(player.getUniqueId()))
                || ConfigEntry.AUTO_CLEAR.getBoolean())
        {
            player.getInventory().clear();
            player.updateInventory();

            player.sendMessage(MessageUtils.parse("<aqua>Your inventory has been cleared automatically."));
        }


        if (ConfigEntry.ADMIN_ONLY_MODE.getBoolean()) 
        {
            player.sendMessage(Component.text(
                    "Server is currently closed to non-admins.",
                    NamedTextColor.RED));
        }

        if (ConfigEntry.LOCKDOWN_MODE.getBoolean()) 
        {
            FUtil.playerMsg(
                    player,
                    "Warning: Server is currently in lockdown mode; new players will not be able to join!",
                    NamedTextColor.RED);
        }
    }
}


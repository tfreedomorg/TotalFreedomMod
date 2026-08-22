package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import me.totalfreedom.totalfreedommod.admin.Admin;
import me.totalfreedom.totalfreedommod.banning.Ban;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

/**
 * Every other disciplinary or destructive command refuses an admin target through
 * {@link FCommand#isProtectedAdmin} or {@link FCommand#isProtectedAdminByName}. 
 * This one is the way an admin is removed, so it carries no such guard and is fenced by reach instead at console only.
 */
@Permission(source = SourceType.ONLY_CONSOLE, permission = "tfm.admin.senior.doom")
@Command(name = "doom", description = "For the bad admins", usage = "/doom <player>")
public class Command_doom extends FCommand
{

    @Callback
    public void doomPlayer(final CommandSender sender, Player player)
    {
        final TagResolver name = Placeholder.unparsed("player", player.getName());

        adminAction(sender, "<red>Casting oblivion over <player>", name);
        MessageUtils.broadcast("<red><player> will be completely obliviated!", name);

        final String ip = player.getAddress().getAddress().getHostAddress().trim();

        // Remove from superadmin
        final Admin admin = getAdmin(player);
        if (admin != null)
        {
            adminAction(sender, "<red>Removing <player> from the superadmin list", name);
            plugin().al.removeAdmin(admin);
        }

        // Remove from whitelist
        player.setWhitelisted(false);

        // Deop
        player.setOp(false);

        // Ban player
        final Ban ban = Ban.forPlayer(player, sender);
        ban.setReason("&cFUCKOFF");
        for (String playerIp : plugin().pl.getData(player).getIps())
        {
            ban.addIp(playerIp);
        }
        plugin().bm.addBan(ban);

        // Set gamemode to survival
        player.setGameMode(GameMode.SURVIVAL);

        // Clear inventory
        player.closeInventory();
        player.getInventory().clear();

        // Ignite player
        player.setFireTicks(10000);

        // Generate explosion
        player.getWorld().createExplosion(player.getLocation(), 0F, false);

        // Shoot the player in the sky
        player.setVelocity(player.getVelocity().clone().multiply(new Vector(0, 64, 0))); // use multiply scalar to send into stratosphere

        sync(() ->
        {
            smitePlayer(player);
            player.setHealth(0.0);
        }, 2L * 20L);

        sync(() ->
        {
            adminAction(sender, "<red>Banning <player>, IP: <ip>", name, Placeholder.unparsed("ip", ip));
            player.getWorld().createExplosion(player.getLocation(), 0F, false);
            kickPlayer(player, "<red>FUCKOFF, and get your shit together!");
        }, 3L * 20L);
    }
}

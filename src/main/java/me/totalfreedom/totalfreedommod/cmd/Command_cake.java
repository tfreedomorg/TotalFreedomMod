package me.totalfreedom.totalfreedommod.cmd;

import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Command(name = "cake", description = "For the people that are still alive.", usage = "/cake")
@Permission(source = SourceType.BOTH, permission = "tfm.fun.cake")
public class Command_cake extends FCommand 
{
    public static final String CAKE_LYRICS = "But there's no sense crying over every mistake. You just keep on trying till you run out of cake.";
    private final Random random = new Random();

    @Callback
    public void cake(CommandSender sender) 
    {
        final String randomCakeLyrics = Stream.of(CAKE_LYRICS.split(" "))
                                        .map(word -> "<#" + String.format("%06x", random.nextInt(0xFFFFFF + 1)) + ">" + word)
                                        .collect(Collectors.joining(" "));
        

        final ItemStack heldItem = new ItemStack(Material.CAKE);
        final ItemMeta heldItemMeta = heldItem.getItemMeta();
        heldItemMeta.displayName(MessageUtils.parse("<white>The <dark_gray>Lie"));

        server().getOnlinePlayers().forEach(player -> player.getInventory().addItem(heldItem));

        MessageUtils.broadcast(randomCakeLyrics);
    }
}

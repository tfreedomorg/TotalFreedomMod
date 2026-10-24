package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.util.FUtil;

@Command(name = "cookie", description = "For those who have no friends.", usage = "/cookie")
@Permission(permission = "tfm.fun.cookie")
public class Command_cookie extends FCommand
{
    public static final String COOKIE_LYRICS = "Imagine that you have zero cookies and you split them evenly among zero friends. How many cookies does each person get? See? It doesn't make sense. And Cookie Monster is sad that there are no cookies, and you are sad that you have no friends.";
    public static final String LORE = "But, you can have a cookie anyways,\nsince you are sad you are have no friends.";

    private final Random random = new Random();

    @Callback
    public void cookie(CommandSender sender)
    {
        final String randomLyrics = randomColorize(COOKIE_LYRICS);

        final ItemStack heldItem = new ItemStack(Material.COOKIE);
        final ItemMeta heldItemMeta = heldItem.getItemMeta();

        final Component name = MessageUtils.parse(randomColorizeChars("Cookie"));

        final List<Component> lore = Stream.of(LORE.split("\n"))
                .map(line -> MessageUtils.parse(randomColorize(line)))
                .collect(Collectors.toList());

        heldItemMeta.displayName(name);
        heldItemMeta.lore(lore);
        heldItem.setItemMeta(heldItemMeta);

        server().getOnlinePlayers()
              .forEach(player -> {
                int firstEmpty = player.getInventory().firstEmpty();
                if (firstEmpty >= 0)
                    player.getInventory().setItem(firstEmpty, heldItem.clone());
              });

        FUtil.bcastMsg(MessageUtils.parse(randomLyrics));
    }

    private String randomColorize(Stream<String> pieces, String delimiter)
    {
        return pieces.map(piece -> "<#" + String.format("%06x", random.nextInt(0xFFFFFF + 1)) + ">" + piece)
                     .collect(Collectors.joining(delimiter));
    }

    private String randomColorize(String text)
    {
        return randomColorize(Stream.of(text.split(" ")), " ");
    }

    private String randomColorizeChars(String text)
    {
        return randomColorize(text.chars().mapToObj(c -> String.valueOf((char) c)), "");
    }
}

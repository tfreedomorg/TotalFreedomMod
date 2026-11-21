package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Permission(source = SourceType.ONLY_IN_GAME, permission = "tfm.player.enchant")
@Command(name = "enchant", description = "Enchant items.", usage = "/<command> <list | addall | reset | add <enchantment> [level] | remove <enchantment>>")
public class Command_enchant extends FCommand
{
    @Callback
    @Subcommand("list")
    public void listPossibleEnchantments(final Player player)
    {
        final ItemStack item = heldItem(player);

        final List<Component> entries = RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.ENCHANTMENT)
                .stream()
                .filter(enchantment -> enchantment.canEnchantItem(item))
                .map(enchantment -> MessageUtils.parse(
                        "<white><click:suggest_command:'/enchant add <key>'><hover:show_text:'<desc><newline><dark_gray><key>'><desc></hover></click>",
                        MessageUtils.component("desc", enchantment.description()),
                        Placeholder.unparsed("key", enchantment.key().toString())))
                .toList();

        if (entries.isEmpty())
        {
            msg(player, "<red>This item can't be enchanted.");
            return;
        }

        msg(player, "<gray>Possible enchantments for held item: <enchantments>",
            MessageUtils.joinedComponents("enchantments", entries));
    }

    @Callback
    @Subcommand("addall")
    public void addAllPossibleEnchantments(final Player player)
    {
        final ItemStack item = heldItem(player);

        RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.ENCHANTMENT)
                .stream()
                .filter(enchantment -> enchantment.canEnchantItem(item))
                .forEach(enchantment -> item.addEnchantment(enchantment, enchantment.getMaxLevel()));

        msg(player, "<gray>Added all possible enchantments for this item.");
    }

    @Callback
    @Subcommand("reset")
    public void removeAllEnchantments(final Player player)
    {
        heldItem(player).removeEnchantments();

        msg(player, "<gray>Removed all enchantments from this item.");
    }

    @Callback
    @Subcommand("add")
    public void addEnchantment(final Player player, final @Resolve("Enchantment") Enchantment enchantment)
    {
        addEnchantment(player, enchantment, enchantment.getMaxLevel());
    }

    @Callback
    @Subcommand("add")
    public void addEnchantment(final Player player, final @Resolve("Enchantment") Enchantment enchantment, final @Resolve("Integer") int level)
    {
        final ItemStack item = heldItem(player);

        if (!enchantment.canEnchantItem(item))
        {
            msg(player, "<red>This enchantment can't be applied to the item you're holding.");
            return;
        }

        item.addEnchantment(enchantment, Math.clamp(level, 1, enchantment.getMaxLevel()));

        msg(player, "<gray>Added enchantment <desc> to this item.",
            MessageUtils.component("desc", enchantment.description()));
    }

    @Callback
    @Subcommand("remove")
    public void removeEnchantment(final Player player, final @Resolve("Enchantment") Enchantment enchantment)
    {
        final ItemStack item = heldItem(player);

        if (item.removeEnchantment(enchantment) != 0)
        {
            msg(player, "<gray>Removed enchantment <desc> from this item.",
                MessageUtils.component("desc", enchantment.description()));
        }
        else
        {
            msg(player, "<gray>That item didn't have <desc> to begin with.",
                MessageUtils.component("desc", enchantment.description()));
        }
    }

    private ItemStack heldItem(final Player player)
    {
        final ItemStack item = player.getEquipment().getItemInMainHand();

        if (item.getType() == Material.AIR)
        {
            throw new CommandFailException("You have to hold an item to enchant it.");
        }

        return item;
    }
}

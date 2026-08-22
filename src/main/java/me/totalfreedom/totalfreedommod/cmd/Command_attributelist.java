package me.totalfreedom.totalfreedommod.cmd;

import java.util.Comparator;
import java.util.List;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.CommandSender;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Command(name = "attributelist", description = "List all possible attributes", usage = "/attributelist")
@Permission(permission = "tfm.player.attributelist")
public class Command_attributelist extends FCommand
{
    @Callback
    public void listAttributes(CommandSender sender)
    {
        final List<TextComponent> attributes = Registry.ATTRIBUTE
                .keyStream()
                .sorted(Comparator.comparing(NamespacedKey::toString))
                .map(key -> Component.text(key.toString(), NamedTextColor.YELLOW))
                .toList();

        msg(sender, "<gray>All possible attributes:");
        msg(sender, "<gold><attributes>",
                Placeholder.component("attributes", Component.text().colorIfAbsent(NamedTextColor.GOLD)
                        .append(Component.join(JoinConfiguration.commas(true), attributes))
                        .build()));
    }
}

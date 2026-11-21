package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.World;
import org.bukkit.command.CommandSender;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Command(name = "entitywipe", description = "Remove various server entities that may cause lag, such as dropped items, minecarts, and boats.", usage = "/entitywipe [world]", aliases = {"ew", "rd"})
@Permission(permission = "tfm.server.entitywipe")
public class Command_entitywipe extends FCommand
{
    @Callback
    public void entitywipeAll(CommandSender sender)
    {
        adminAction(sender, "<red>Removing all server entities");
        final int removed = plugin().ew.wipeEntities(true);
        msg(
            sender,
            "<gray><count> <noun> removed.",
            Formatter.number("count", removed),
            Placeholder.unparsed("noun", removed == 1 ? "entity" : "entities")
        );
    }

    @Callback
    public void entitywipeWorld(CommandSender sender, World world)
    {
        adminAction(sender, "<red>Removing entities in world <world>", Placeholder.unparsed("world", world.getName()));
        final int removed = plugin().ew.wipeEntities(world, true);
        msg(
            sender,
            "<gray><count> <noun> removed from \"<world>\".",
            Formatter.number("count", removed),
            Placeholder.unparsed("noun", removed == 1 ? "entity" : "entities"),
            Placeholder.unparsed("world", world.getName())
        );
    }
}

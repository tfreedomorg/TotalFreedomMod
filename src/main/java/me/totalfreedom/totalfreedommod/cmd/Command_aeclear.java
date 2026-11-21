package me.totalfreedom.totalfreedommod.cmd;


import org.bukkit.command.CommandSender;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Command(name = "aeclear", description = "Removes all area-of-effect clouds on the server.", usage = "/aeclear", aliases = {"aec"})
@Permission(permission = "tfm.admin.aeclear")
public class Command_aeclear extends FCommand
{
    @Callback
    public void clear(CommandSender sender)
    {
        adminAction(sender, "<red>Removing all area-of-effect clouds");

        final long removed = server().getWorlds()
                .stream()
                .flatMap(world -> world.getEntities().stream())
                .filter(entity -> entity instanceof AreaEffectCloud)
                .reduce(0L, (total, entity) ->
                    {
                        entity.remove();
                        return total + 1;
                    }, Long::sum);

        msg(sender, "<gray><count> area-of-effect cloud<plural> removed.",
                Formatter.number("count", removed),
                Placeholder.unparsed("plural", removed == 1 ? "" : "s"));
    }
}

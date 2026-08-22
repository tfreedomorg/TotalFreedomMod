package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Callback;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;

@Command(name = "spawnmob", description = "Spawns any mob.", usage = "/spawnmob <type> [amount]")
@Permission(permission = "tfm.fun.spawnmob", source = SourceType.ONLY_IN_GAME)
public class Command_spawnmob extends FCommand
{
    @Callback
    public void spawnSingle(Player sender, EntityType type)
    {
       spawnAmount(sender, type, 1);
    }

    @Callback
    public void spawnAmount(Player sender, EntityType type, Integer amount)
    {
        amount = Math.clamp(amount, 1, 10);

        msg(sender, "<gray>Spawning <amount> of type <type>",
                Formatter.number("amount", amount),
                Placeholder.unparsed("type", type.name())
        );

        final Location playerLoc = sender.getLocation().clone();

        for (int i = 0; i < amount; i++)
        {
            playerLoc.getWorld().spawnEntity(playerLoc, type, CreatureSpawnEvent.SpawnReason.COMMAND);
        }
    }
}
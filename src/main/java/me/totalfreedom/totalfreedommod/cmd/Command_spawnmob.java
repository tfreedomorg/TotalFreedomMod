package me.totalfreedom.totalfreedommod.cmd;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Callback;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;
import me.totalfreedom.totalfreedommod.rank.Rank;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;

@Command(name = "spawnmob", description = "Spawns any mob.", usage = "/spawnmob <type> [amount]")
@Permission(permission = "tfm.fun.spawnmob", level = Rank.OP, source = SourceType.ONLY_IN_GAME)
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
        if (sender.getWorld().getDifficulty().equals(Difficulty.PEACEFUL)
                && type.getEntityClass() != null
                && Enemy.class.isAssignableFrom(type.getEntityClass()))
        {
            msg(sender, "<red>The difficulty is currently set to peaceful.");
            return;
        }

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
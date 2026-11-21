package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Command(name = "spawn", description = "Teleport to the server spawn.", usage = "/spawn [player]")
@Permission(permission = "tfm.player.spawn", source = SourceType.ONLY_IN_GAME)
public class Command_spawn extends FCommand
{
    @Callback
    public void spawnSelf(Player player)
    {
        if (plugin().sm.sendToSpawn(player))
        {
            msg(player, "<gray>Teleported to spawn.");
        }
    }

    @Callback
    public void spawnOther(Player sender, Player target)
    {
        if (!isAdmin(sender))
        {
            msg(sender, "<red>No permission to send other players to spawn.");
            return;
        }

        if (plugin().sm.sendToSpawn(target))
        {
            msg(sender, "<gray>Sent <player> to spawn.", Placeholder.unparsed("player", target.getName()));
        }
    }
}

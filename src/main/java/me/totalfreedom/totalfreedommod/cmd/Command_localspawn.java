package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Command(name = "localspawn", description = "Teleport to the spawn point for the current world.", usage = "/localspawn", aliases = {"worldspawn", "gotospawn"})
@Permission(permission = "tfm.player.localspawn", source = SourceType.ONLY_IN_GAME)
public class Command_localspawn extends FCommand
{
    @Callback
    public void localspawn(Player player)
    {
        player.teleport(player.getWorld().getSpawnLocation());
        msg(
            player,
            "<gray>Teleported to spawnpoint for world \"<world>\".",
            Placeholder.unparsed("world", player.getWorld().getName())
        );
    }
}

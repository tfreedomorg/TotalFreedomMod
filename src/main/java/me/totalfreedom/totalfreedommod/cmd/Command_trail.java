package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.entity.Player;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Command(name = "trail", description = "Pretty rainbow trails.", usage = "/trail [on | off]")
@Permission(permission = "tfm.fun.trail", source = SourceType.ONLY_IN_GAME)
public class Command_trail extends FCommand
{
    @Callback
    public void toggle(Player player)
    {
        setTrail(player, plugin().tr.has(player));
    }

    @Callback
    public void setTrail(Player player, @Resolve("Boolean") boolean value) 
    // This forces the custom boolean argument resolver 
    // instead of native brigadier boolean argument resolver which only resolves true / false
    {
        if (value)
        {
            plugin().tr.remove(player);
            msg(player, "<gray>Trail disabled.");
        }
        else
        {
            plugin().tr.add(player);
            msg(player, "<gray>Trail enabled.");
        }
    }
}

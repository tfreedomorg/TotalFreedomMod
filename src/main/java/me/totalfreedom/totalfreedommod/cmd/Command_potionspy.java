package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.entity.Player;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.rank.Rank;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;

@Command(name = "potionspy", description = "Spy on potion usage", usage = "/potionspy", aliases = {"potspy"})
@Permission(permission = "tfm.admin.potspy", level = Rank.SUPER_ADMIN, source = SourceType.ONLY_IN_GAME)
public class Command_potionspy extends FCommand
{
    @Callback
    public void toggle(Player player)
    {
        final PlayerData data = plugin().pl.getData(player);
        data.setPotionSpy(!data.isPotionSpy());
        plugin().pl.saveAsync();
        msg(
            player, 
            "<gray>PotionSpy <status:enabled:disabled>.",
            Formatter.booleanChoice("status", data.isPotionSpy())
        );
    }
}

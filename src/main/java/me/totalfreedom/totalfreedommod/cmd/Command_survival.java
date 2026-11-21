package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Command(
        name = "survival",
        description = "Quickly change your own gamemode to survival, or define someone's username to change theirs.",
        usage = "/survival <-a | [player]>",
        aliases = {"gms"}
)
@Permission(permission = "tfm.player.survival")
public class Command_survival extends FCommand
{
    @Callback
	@Permission(permission = "tfm.player.survival", source = SourceType.ONLY_IN_GAME)
	public void changeGamemodeSelf(Player player)
	{
		player.setGameMode(GameMode.SURVIVAL);
		msg(player, "<gray>Your gamemode has been set to Survival.");
	}

	@Callback
	@Subcommand("-a")
	@Permission(permission = "tfm.admin.gamemode")
	public void changeGamemodeAll(CommandSender sender)
	{
		Bukkit.getOnlinePlayers().forEach(player -> player.setGameMode(GameMode.SURVIVAL));
		adminAction(sender, "<red>Changing everyone's gamemode to Survival.");
	}

	@Callback
	@Permission(permission = "tfm.admin.gamemode")
	public void changeGamemodeOther(CommandSender sender, Player target)
	{
		target.setGameMode(GameMode.SURVIVAL);
		msg(sender, "<gray>Setting <player>'s gamemode to Survival.", Placeholder.unparsed("player", target.getName()));
		msg(target, "<gray><sender> set your gamemode to Survival.", Placeholder.unparsed("sender", sender.getName()));
	}
}

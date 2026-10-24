package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Command(name = "adventure", description = "Quickly change your own gamemode to adventure, or define someone's username to change theirs.", usage = "/adventure [-a | name]", aliases = {"gma"})
@Permission(permission = "tfm.admin.gamemode")
public class Command_adventure extends FCommand 
{
	@Callback
	@Permission(permission = "tfm.admin.gamemode", source = SourceType.ONLY_IN_GAME)
	public void changeGamemodeSelf(Player player)
	{
		player.setGameMode(GameMode.ADVENTURE);
		msg(player, "<gray>Your gamemode has been set to Adventure.");
	}

	@Callback
	@Subcommand("-a") // a switch wouldn't be really appropriate here due to the nature of the @Permission annotation
	@Permission(permission = "tfm.admin.gamemode")
	public void changeGamemodeAll(CommandSender sender)
	{
		adminAction(sender, "<red>Changing everyone's gamemode to Adventure");
		Bukkit.getOnlinePlayers().forEach(player -> player.setGameMode(GameMode.ADVENTURE));
	}

	@Callback
	@Permission(permission = "tfm.admin.gamemode")
	public void changeGamemodeOther(CommandSender sender, Player target)
	{
		target.setGameMode(GameMode.ADVENTURE);
		msg(sender, "<gray>Setting <player>'s gamemode to Adventure.",
				Placeholder.unparsed("player", target.getName()));
		msg(target, "<gray><sender> set your gamemode to Adventure.",
				Placeholder.unparsed("sender", sender.getName()));
	}
}

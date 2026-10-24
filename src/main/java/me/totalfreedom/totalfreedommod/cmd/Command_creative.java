package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Command(
        name = "creative",
        description = "Quickly change your own gamemode to creative, or define someone's username to change theirs.",
        usage = "/creative <-a | [partialname]>",
        aliases = {"gmc"}
)
@Permission(permission = "tfm.player.creative")
public class Command_creative extends FCommand
{
    @Callback
	@Permission(permission = "tfm.player.creative", source = SourceType.ONLY_IN_GAME)
	public void changeGamemodeSelf(Player player)
	{
		player.setGameMode(GameMode.CREATIVE);
		msg(player, "<gray>Your gamemode has been set to Creative.");
	}

	@Callback
	@Subcommand("-a")
	@Permission(permission = "tfm.admin.gamemode")
	public void changeGamemodeAll(CommandSender sender)
	{
		Bukkit.getOnlinePlayers().forEach(player -> player.setGameMode(GameMode.CREATIVE));
		adminAction(sender, "<red>Changing everyone's gamemode to Creative.");
	}

	@Callback
	@Permission(permission = "tfm.admin.gamemode")
	public void changeGamemodeOther(CommandSender sender, Player target)
	{
		target.setGameMode(GameMode.CREATIVE);
		msg(sender, "<gray>Setting <player>'s gamemode to Creative.", Placeholder.unparsed("player", target.getName()));
		msg(target, "<gray><sender> set your gamemode to Creative.", Placeholder.unparsed("sender", sender.getName()));
	}
}

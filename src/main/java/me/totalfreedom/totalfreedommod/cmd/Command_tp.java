package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.entity.Player;

import me.totalfreedom.api.cmd.FCommand;
import me.totalfreedom.api.cmd.SourceType;
import me.totalfreedom.api.cmd.annotation.*;

@Command(name = "tp", description = "Teleport to a player.", usage = "/tp <player> [target]", aliases = {"teleport", "tp2p"})
@Permission(permission = "tfm.player.tp", source = SourceType.ONLY_IN_GAME)
public class Command_tp extends FCommand
{
	@Callback
	public void teleportSelf(Player sender, Player target)
	{
		msg(sender, "<gray>Not implemented yet!");
	}

	@Callback
	@Permission(permission = "tfm.admin.tp.others", source = SourceType.ONLY_IN_GAME)
	public void teleportOther(Player sender, Player subject, Player target)
	{
		msg(sender, "<gray>Not implemented yet!");
	}
}
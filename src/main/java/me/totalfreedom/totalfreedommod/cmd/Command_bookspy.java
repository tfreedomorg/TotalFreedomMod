package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.bukkit.entity.Player;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.player.SpyMode;

@Command(name = "bookspy", description = "Spy on book edits", usage = "/bookspy [ops | admins | all | off]", aliases = {"bspy"})
@Permission(permission = "tfm.admin.bookspy", source = SourceType.ONLY_IN_GAME)
public class Command_bookspy extends FCommand
{
	@Callback
	public void toggle(final Player player)
	{
		final PlayerData data = plugin().pl.getData(player);
		bookSpy(player, data.isBookSpy() ? SpyMode.OFF : SpyMode.OPS);
	}

	@Callback
	public void bookSpy(final Player player, final SpyMode mode)
	{
		final PlayerData data = plugin().pl.getData(player);

		data.setBookSpyMode(mode);
		plugin().pl.saveAsync();

		switch (mode)
		{
		case OFF -> msg(player, "<gray>BookSpy disabled.");
		case OPS -> msg(player, "<gray>BookSpy set to <green>OPS</green> mode. You will only see non-admins' book edits.");
		case ADMINS -> msg(player, "<gray>BookSpy set to <green>ADMINS</green> mode. You will only see admins' book edits.");
		case ALL -> msg(player, "<gray>BookSpy set to <green>ALL</green> mode. You will see both non-admins' and admins' book edits.");	
		}
	}

	@Completer(value = "", position = 0)
	public List<String> completeMode(final Player player, final String partial)
	{
		final String lower = partial.toLowerCase(Locale.ROOT);

		return Stream.of("ops", "admins", "all", "off")
					 .filter(mode -> mode.startsWith(lower))
					 .toList();
	}
}
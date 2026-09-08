package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.bukkit.*;
import org.bukkit.entity.Player;

import me.totalfreedom.api.cmd.FCommand;
import me.totalfreedom.api.cmd.SourceType;
import me.totalfreedom.api.cmd.annotation.*;
import me.totalfreedom.api.player.PlayerData;
import me.totalfreedom.totalfreedommod.player.FPlayer;
import me.totalfreedom.totalfreedommod.player.SpyMode;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

@Command(name = "cmdspy", description = "Spy on commands", usage = "/cmdspy [ops | admins | all | off | sound | regex]", aliases = {"commandspy", "cspy"})
@Permission(permission = "tfm.admin.cmdspy", source = SourceType.ONLY_IN_GAME)
public class Command_cmdspy extends FCommand
{
    // doing this to show ajax why i don't like using var keyword :)
    @Callback
    public void toggle(final Player player)
    {
        final var pd = plugin().players().getPlayer(player);
        commandSpy(player, pd.cmdspyEnabled() ? SpyMode.OFF : SpyMode.ALL);
    }

    @Callback
    public void commandSpy(final Player player, final SpyMode mode) // should auto resolve enums
    {
        final var fp = plugin().players().getPlayer(player);
        final var pd = plugin().players().getData(player);

        fp.setCommandSpyMode(mode);
        pd.setCommandSpyMode(mode); // Why is this in two separate locations? This is unnecessary caching.
        plugin().players().saveAsync();

        switch (mode)
        {
            case OFF -> msg(player, "<gray>CommandSpy disabled.");
            case ADMINS -> msg(player, "<gray>CommandSpy set to <green>ADMINS</green> mode. You will only see admins' commands.");
            case OPS -> msg(player, "<gray>CommandSpy set to <green>OPS</green> mode. You will only see OPs' commands.");
            case ALL -> msg(player, "<gray>CommandSpy set to <green>ALL</green> mode. You will see both OPs' and admins' commands.");
        }
    }

    @Callback
    @Subcommand("sound")
    public void sound(final Player player, @Greedy final String soundName)
    {
        if (soundName == null || soundName.isBlank())
        {
            msg(player, "<red>You must specify a sound.");
            return;
        }

        final NamespacedKey key = NamespacedKey.fromString(soundName.toLowerCase(Locale.ROOT));
        final Sound sound = key == null ? null : Registry.SOUNDS.get(key);
        if (sound == null)
        {
            msg(player, "<red>Unknown sound: <sound>", Placeholder.unparsed("sound", soundName));
            return;
        }

        final PlayerData data = plugin().players().getData(player);
        data.setCommandSpyAlertSound(sound);
        plugin().players().saveData(data);
        player.playSound(player.getLocation(), sound, SoundCategory.MASTER, 1.0F, 1.0F);

        msg(player, "<gray>CommandSpy alert sound set to <green><sound></green>.",
            Placeholder.unparsed("sound", Registry.SOUNDS.getKeyOrThrow(sound).asString()));
    }

    @Callback
    @Subcommand("regex")
    public void regex(final Player player, @Greedy final String pattern)
    {
        if (pattern == null || pattern.isBlank())
        {
            msg(player, "<red>Invalid regex: <pattern>", Placeholder.unparsed("pattern", ""));
            return;
        }

        try
        {
            Pattern.compile(pattern);
        }
        catch (IllegalArgumentException ex)
        {
            msg(player, "<red>Invalid regex: <pattern>", Placeholder.unparsed("pattern", pattern));
            return;
        }

        final PlayerData data = plugin().players().getData(player);
        data.setCommandSpyAlertRegex(pattern);
        plugin().players().saveData(data);

        msg(player, "<gray>CommandSpy alert regex set to <green><pattern></green>.",
                Placeholder.unparsed("pattern", pattern));
    }

    @Callback
    @Subcommand("regex clear")
    public void clearRegex(final Player player)
    {
        final PlayerData data = plugin().players().getData(player);
        data.setCommandSpyAlertRegex(null);
        plugin().players().saveData(data);
        msg(player, "<gray>CommandSpy alert regex cleared.");
    }

    @Completer(value = "", position = 0)
    public List<String> completeMode(final Player player, final String partial)
    {
        final String lower = partial.toLowerCase(Locale.ROOT);

        return Stream.of("ops", "admins", "all", "off")
                     .filter(mode -> mode.startsWith(lower))
                     .toList();
    }

    @Completer(value = "sound", position = 0)
    public List<String> completeSound(final Player player, final String partial)
    {
        final String lower = partial.toLowerCase(Locale.ROOT);

        return Registry.SOUNDS.stream()
                              .map(sound -> Registry.SOUNDS.getKeyOrThrow(sound).value())
                              .filter(sound -> sound.startsWith(lower))
                              .toList();
    }
}

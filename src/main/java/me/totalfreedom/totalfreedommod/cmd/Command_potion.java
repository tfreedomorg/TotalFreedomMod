package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import org.bukkit.Registry;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Command(
        name = "potion",
        description = "Manipulate potion effects. Duration is measured in server ticks (~20 ticks per second).",
        usage = "/potion <list | clear [player] | clearall | add <type> <duration> <amplifier> [player] | remove <type> [player]>"
)
@Permission(permission = "tfm.player.potion")
public class Command_potion extends FCommand
{
    @Callback
    @Subcommand("list")
    public void listEffects(CommandSender sender)
    {
        final List<Component> effects = Registry.POTION_EFFECT_TYPE
                                          .stream()
                                          .map(type -> buildEffectEntry(sender, type))
                                          .toList();

        msg(sender, "<gray>Potion effect types:");
        msg(sender, "<gray><effects>", MessageUtils.joinedComponents("effects", effects));
    }

    private Component buildEffectEntry(CommandSender sender, PotionEffectType type)
    {
        if (!(sender instanceof Player))
        {
            return Component.text(type.key().asString(), NamedTextColor.WHITE);
        }

        return Component.translatable(type.translationKey(), NamedTextColor.WHITE)
            .clickEvent(ClickEvent.suggestCommand("/potion add " + type.key().value() + " "))
            .hoverEvent(HoverEvent.showText(Component.translatable(type.translationKey())
                .appendNewline()
                .append(Component.text(type.key().asString(), NamedTextColor.DARK_GRAY))));
    }

    @Callback
    @Subcommand("clearall")
    public void clearAll(CommandSender sender)
    {
        if (!isAdmin(sender) && sender instanceof Player player)
        {
            clearSelf(player);
            return;
        }

        adminAction(sender, "<red>Clearing all potion effects from all players");
        server().getOnlinePlayers().forEach(p -> p.clearActivePotionEffects()); // Again, another null warning from my IDE.
    }

    @Callback
    @Subcommand("clear")
    public void clearSelf(CommandSender sender)
    {
        if (!(sender instanceof Player player))
        {
            msg(sender, "<gray>You must specify a player when using this command from the console.");
            return;
        }

        clearPlayer(sender, player);
    }

    @Callback
    @Subcommand("clear")
    public void clearPlayer(CommandSender sender, Player player)
    {
        if (!isAdmin(sender) && (sender instanceof Player p && !p.equals(player)))
        {
            msg(sender, "<red>You don't have permission to clear effects from other players.");
            return;
        }

        player.clearActivePotionEffects();
        msg(sender, "<aqua>Cleared all active potion effects from player <player>.", Placeholder.unparsed("player", player.getName()));
    }

    @Callback
    @Subcommand("add")
    public void addEffectToSelf(CommandSender sender, PotionEffectType type, int duration, int amplifier)
    {
        if (!(sender instanceof Player player))
        {
            msg(sender, "<gray>You must specify a player when using this command from the console.");
            return;
        }

        addEffectToPlayer(sender, type, duration, amplifier, player);
    }

    @Callback
    @Subcommand("add")
    public void addEffectToPlayer(CommandSender sender, PotionEffectType type, int duration, int amplifier, Player player)
    {
        // If they're trying to give it to another person, and they don't have permission, let's give it to them instead
        if (!isAdmin(sender) && (sender instanceof Player p && !p.equals(player)))
        {
            addEffectToPlayer(sender, type, duration, amplifier, p);
            return;
        }

        final int clampedDuration = Math.min(duration, 100_000);
        final int clampedAmplifier = Math.min(amplifier, 100_000);

        player.addPotionEffect(type.createEffect(clampedDuration, clampedAmplifier));

        msg(
            sender,
            "<aqua>Added potion effect <effect>, Duration: <duration>, Amplifier: <amplifier> to player <player>.",
            MessageUtils.component("effect", Component.translatable(type.translationKey())),
            Formatter.number("duration", clampedDuration),
            Formatter.number("amplifier", clampedAmplifier),
            Placeholder.unparsed("player", player.getName())
        );
    }

    @Callback
    @Subcommand("remove")
    public void removeEffectFromSelf(CommandSender sender, PotionEffectType type)
    {
        if (!(sender instanceof Player player))
        {
            msg(sender, "<gray>You must specify a player when using this command from the console.");
            return;
        }

        removeEffectFromPlayer(sender, type, player);
    }

    @Callback
    @Subcommand("remove")
    public void removeEffectFromPlayer(CommandSender sender, PotionEffectType type, Player player)
    {
        if (!isAdmin(sender) && (sender instanceof Player p && !p.equals(player)))
        {
            removeEffectFromPlayer(sender, type, p);
            return;
        }

        player.removePotionEffect(type);

        msg(
            sender,
            "<aqua>Removed potion effect <effect> from player <player>.",
            MessageUtils.component("effect", Component.translatable(type.translationKey())),
            Placeholder.unparsed("player", player.getName())
        );
    }
}

package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Permission(source = SourceType.ONLY_IN_GAME, permission = "tfm.fun.tossmob")
@Command(
        name = "tossmob",
        description = "Throw a mob in the direction you are facing when you left click with a stick.",
        usage = "/<command> <mobtype [speed] | off | list>")
public class Command_tossmob extends FCommand
{

    @Callback
    @Subcommand("off")
    public void disableTossmob(final Player player)
    {
        if (!getConfigEntry("TOSSMOB_ENABLED").getBoolean() || !fplayer(player).mobThrowerEnabled())
        {
            msg(player, "<gray>Tossmob is already disabled.");
            return;
        }

        fplayer(player).disableMobThrower();
        msg(player, "<green>Mobthrower has been disabled.");
    }

    @Callback
    @Subcommand("list")
    public void listMobs(final Player player)
    {
        final List<String> values = Stream.of(EntityType.values())
                                    .filter(e -> e.isAlive() && e.isSpawnable())
                                    .map(v -> v.name().toLowerCase(Locale.ROOT))
                                    .toList();

        msg(player, "<green>Supported mobs: <list>", MessageUtils.joinedList("list", values, NamedTextColor.GREEN));
    }

    @Callback
    public void tossDefault(final Player player)
    {
        tossmob(player, EntityType.PIG, 1.0);
    }

    @Callback
    public void toss(final Player player, final @Resolve(value = "EntityType", strategy = "mobs") EntityType type, final @Resolve("Double") double speed)
    {
        tossmob(player, type, speed);
    }

    @Callback
    public void tossDefaultSpeed(final Player player, final @Resolve(value = "EntityType", strategy = "mobs") EntityType type)
    {
        tossmob(player, type, 1.0);
    }

    private void tossmob(final Player player, final EntityType type, final double speed)
    {
        if (!getConfigEntry("TOSSMOB_ENABLED").getBoolean())
        {
            msg(player, "<gray>Tossmob is currently disabled.");
            return;
        }

        final double clamped = Math.clamp(speed, 1.0, 5.0);

        fplayer(player).enableMobThrower(type, clamped);

        msg(player, 
            """
                <green>MobThrower is enabled. 
                 <gray>- <yellow>Creature: <green><entity>
                 <gray>- <yellow>Speed: <green><speed>
                 <green>Left click while holding a bone to throw mobs!
                 <green>Type \"/tossmob off\" to disable. -By Madgeek1450
            """,
            Placeholder.unparsed("entity", type.name().toLowerCase(Locale.ROOT)),
            Formatter.number("speed", clamped)
        );

        player.getEquipment().setItemInMainHand(new ItemStack(Material.BONE, 1));
    }
}

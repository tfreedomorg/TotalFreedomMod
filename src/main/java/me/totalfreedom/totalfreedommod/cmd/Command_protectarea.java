package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.ProtectArea.ProtectedRegion;
import me.totalfreedom.totalfreedommod.bridge.WorldEditBridge;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Callback;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Subcommand;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;

@Command(name = "protectarea",
        description = "Manage protected regions so that only superadmins can directly modify blocks within them. WorldEdit and other such plugins might bypass this.",
        usage = "/<command> <list | clear | create <name> | info <region> | update <region> | delete <region>>",
        aliases = {"protectregion", "protect"})
@Permission(permission = "tfm.admin.protectregion")
public class Command_protectarea extends FCommand
{
    @Callback
    @Subcommand("list")
    public void list(final CommandSender sender)
    {
        checkEnabled();

        final List<String> names = plugin().pa.getProtectedAreaNames();
        if (names.isEmpty())
        {
            msg(sender, "<gray>There are no protected regions.");
            return;
        }

        msg(sender, "<gray>Protected regions (<count>): <regions>",
            Formatter.number("count", names.size()),
            Placeholder.unparsed("regions", String.join(", ", names)));
    }

    @Callback
    @Subcommand("clear")
    public void clear(final CommandSender sender)
    {
        checkEnabled();

        adminAction(sender, "<red>Clearing all protected regions");
        plugin().pa.clearProtectedAreas();
        msg(sender, "<gray>All protected regions have been cleared.");
    }

    @Callback
    @Subcommand("create")
    @Permission(source = SourceType.ONLY_IN_GAME, permission = "tfm.admin.protectregion")
    public void create(final Player sender, final String name)
    {
        checkEnabled();

        final WorldEditBridge.Region selection = getWorldEditSelection(sender);
        final ProtectedRegion region = plugin().pa.addProtectedArea(name, selection.min(), selection.max(), selection.world());

        if (region == null)
        {
            msg(sender, "<red>A protected region named '<name>' already exists.",
                Placeholder.unparsed("name", name));
            return;
        }

        adminAction(sender, "<red>Creating protected region '<name>'",
            Placeholder.unparsed("name", name));
        msg(sender, region.toString());
    }

    @Callback
    @Subcommand("update")
    @Permission(source = SourceType.ONLY_IN_GAME, permission = "tfm.admin.protectregion")
    public void update(final Player sender, final ProtectedRegion region)
    {
        checkEnabled();

        final WorldEditBridge.Region selection = getWorldEditSelection(sender);

        adminAction(sender, "<red>Updating protected region '<name>'",
            Placeholder.unparsed("name", region.getName()));
        msg(sender, plugin().pa.updateProtectedRegion(region, selection.min(), selection.max(), selection.world()).toString());
    }

    @Callback
    @Subcommand("info")
    public void info(final CommandSender sender, final ProtectedRegion region)
    {
        checkEnabled();

        msg(sender, region.toString());
    }

    @Callback
    @Subcommand("delete")
    public void delete(final CommandSender sender, final ProtectedRegion region)
    {
        checkEnabled();

        plugin().pa.removeProtectedArea(region);

        adminAction(sender, "<red>Deleting protected region '<name>'",
            Placeholder.unparsed("name", region.getName()));
        msg(sender, "<gray>Protected region '<name>' has been deleted.",
            Placeholder.unparsed("name", region.getName()));
    }

    private void checkEnabled()
    {
        if (!ConfigEntry.PROTECTAREA_ENABLED.getBoolean())
            throw new CommandFailException("<gray>Protected areas are currently disabled in the TotalFreedomMod configuration.");
    }

    private WorldEditBridge.Region getWorldEditSelection(final Player player)
    {
        if (!plugin().web.isWorldEditEnabled())
            throw new CommandFailException("<red>WorldEdit must be available in order to manage protected regions.");

        final WorldEditBridge.Region selection = plugin().web.getSelection(player);
        if (selection == null)
            throw new CommandFailException("<red>Please select a region to protect using WorldEdit.");

        return selection;
    }
}

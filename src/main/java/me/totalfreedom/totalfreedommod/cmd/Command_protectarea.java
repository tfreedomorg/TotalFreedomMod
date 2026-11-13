package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.ProtectArea;
import me.totalfreedom.totalfreedommod.ProtectArea.ProtectedRegion;
import me.totalfreedom.totalfreedommod.bridge.WorldEditBridge;
import me.totalfreedom.api.cmd.FCommand;
import me.totalfreedom.api.cmd.CommandFailException;
import me.totalfreedom.api.cmd.SourceType;
import me.totalfreedom.api.cmd.annotation.Callback;
import me.totalfreedom.api.cmd.annotation.Command;
import me.totalfreedom.api.cmd.annotation.Permission;
import me.totalfreedom.api.cmd.annotation.Subcommand;
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

        final List<String> names = plugin().services().require(ProtectArea.class).getProtectedAreaNames();
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
        plugin().services().require(ProtectArea.class).clearProtectedAreas();
        msg(sender, "<gray>All protected regions have been cleared.");
    }

    @Callback
    @Subcommand("create")
    @Permission(source = SourceType.ONLY_IN_GAME, permission = "tfm.admin.protectregion")
    public void create(final Player sender, final String name)
    {
        checkEnabled();

        final WorldEditBridge.Region selection = getWorldEditSelection(sender);
        final ProtectedRegion region = plugin().services().require(ProtectArea.class).addProtectedArea(name, selection.min(), selection.max(), selection.world());

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
        msg(sender, plugin().services().require(ProtectArea.class).updateProtectedRegion(region, selection.min(), selection.max(), selection.world()).toString());
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

        plugin().services().require(ProtectArea.class).removeProtectedArea(region);

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
        if (!plugin().bridges().require(WorldEditBridge.class).isWorldEditEnabled())
            throw new CommandFailException("<red>WorldEdit must be available in order to manage protected regions.");

        final WorldEditBridge.Region selection = plugin().bridges().require(WorldEditBridge.class).getSelection(player);
        if (selection == null)
            throw new CommandFailException("<red>Please select a region to protect using WorldEdit.");

        return selection;
    }
}

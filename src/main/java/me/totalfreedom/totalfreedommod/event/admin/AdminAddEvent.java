package me.totalfreedom.totalfreedommod.event.admin;

import lombok.Getter;
import me.totalfreedom.totalfreedommod.admin.Admin;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;

/**
 * A more specific event for when an Admin is added to the admin list.
 */
@Getter
public class AdminAddEvent extends AdminManagementEvent
{
    /**
     * Whether the admin entry was new.
     */
    private final boolean newEntry;

    public AdminAddEvent(@Nonnull Admin affectedAdmin, @Nonnull CommandSender by, boolean newEntry)
    {
        super(affectedAdmin, by, Action.ADD);
        this.newEntry = newEntry;
    }

    public static HandlerList getHandlerList()
    {
        return AdminManagementEvent.getHandlerList();
    }
}

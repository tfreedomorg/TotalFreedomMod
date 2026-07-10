package me.totalfreedom.totalfreedommod.event.admin;

import com.google.common.base.Preconditions;
import lombok.Getter;
import me.totalfreedom.totalfreedommod.admin.Admin;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.Nullable;
import javax.annotation.Nonnull;

/**
 * A somewhat generic event called when a management action is performed on a single admin entry. This does not get
 *  called if the admin list is cleaned.
 */
@Getter
public class AdminManagementEvent extends Event
{
    @Getter
    private static final HandlerList handlerList = new HandlerList();

    /**
     * The {@link Admin} entry relevant to this event.
     */
    @Nonnull
    private final Admin admin;
    /**
     * The {@link CommandSender source} of the event.
     */
    @Nonnull
    private final CommandSender source;
    /**
     * The {@link Action action} performed in the event.
     */
    @Nonnull
    private final Action action;

    public AdminManagementEvent(@Nonnull Admin admin, @Nonnull CommandSender source, @Nonnull Action action)
    {
        Preconditions.checkNotNull(admin, "Admin can not be null");
        Preconditions.checkNotNull(source, "Source can not be null");
        Preconditions.checkNotNull(action, "Action can not be null");

        this.admin = admin;
        this.source = source;
        this.action = action;
    }

    @Override
    public @Nonnull HandlerList getHandlers()
    {
        return handlerList;
    }

    /**
     * Helper method for getting the affected admin as a standard Player.
     * @return  Player matching the admin entry in the event, if they are on the server.
     */
    public @Nullable Player getAffectedAdminAsPlayer()
    {
        if (admin.getUuid() != null)
        {
            return Bukkit.getPlayer(admin.getUuid());
        }

        return Bukkit.getPlayer(admin.getName());
    }

    /**
     * Enum for the different actions that can happen in this event.
     */
    public enum Action
    {
        ADD,
        REMOVE,
        SET_RANK
    }
}

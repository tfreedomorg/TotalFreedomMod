package me.totalfreedom.totalfreedommod.event.admin;

import com.google.common.base.Preconditions;
import lombok.Getter;
import me.totalfreedom.totalfreedommod.admin.Admin;
import me.totalfreedom.totalfreedommod.rank.CustomRank;
import me.totalfreedom.totalfreedommod.rank.Rank;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A more specific event for when an Admin's rank is updated.
 */
@Getter
public class AdminRankUpdateEvent extends AdminManagementEvent
{
    /**
     * The internal {@link Rank} that was set during the operation.
     */
    @Nonnull
    private final Rank rank;
    /**
     * The internal {@link CustomRank} that may have been set during the operation.
     */
    @Nullable
    private final CustomRank customRank;

    public AdminRankUpdateEvent(@Nonnull Admin affectedAdmin, @Nonnull CommandSender by, @Nonnull Rank rank, @Nullable CustomRank customRank)
    {
        super(affectedAdmin, by, Action.SET_RANK);
        Preconditions.checkNotNull(rank, "Rank can not be null");

        this.rank = rank;
        this.customRank = customRank;
    }

    public static HandlerList getHandlerList()
    {
        return AdminManagementEvent.getHandlerList();
    }
}

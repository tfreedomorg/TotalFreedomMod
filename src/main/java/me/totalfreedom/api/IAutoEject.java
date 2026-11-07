package me.totalfreedom.api;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

public interface IAutoEject
{
    /**
     * Ejects a player from the server.
     * @deprecated          This does not account for a particular cause of ejection. Use
     *                      {@link #eject(Player, String, String, String)} instead.
     * @param player        The target player
     * @param kickMessage   The message to kick the player with
     */
    @Deprecated
    void autoEject(Player player, String kickMessage);

    /**
     * Ejects the provided player (if they are eligible) from the server.
     * @param player            The target player
     * @param cause             The internal identifier for the cause
     * @param causeDescription  The description to use in the "ejection" broadcast
     * @param kickMessage       The message to kick the player with
     * @return                  True if the player can be ejected for the given cause
     */
    boolean eject(@NotNull Player player,
                @NotNull String cause,
                @NotNull String causeDescription,
                @NotNull String kickMessage);

    /**
     * Ejects the provided player (if they are eligible) from the server if provided condition is true.
     * @param player            The target player
     * @param cause             The internal identifier for the cause
     * @param causeDescription  The description to use in the "ejection" broadcast
     * @param kickMessage       The message to kick the player with
     * @param condition         The condition that must be true for the player to be ejected
     */
    boolean ejectIf(@NotNull Player player,
                    @NotNull String cause,
                    @NotNull String causeDescription,
                    @NotNull String kickMessage,
                    @NotNull Supplier<Boolean> condition);

    /**
     * Checks whether the player can be ejected for this cause.
     * @param player    The target player
     * @param cause     The internal identifier for the cause
     * @return          True if the player is
     */
    boolean canEject(@NotNull Player player,
                     @NotNull String cause);
}

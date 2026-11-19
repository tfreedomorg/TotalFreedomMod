package me.totalfreedom.api.rank;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;

import me.totalfreedom.api.display.Displayable;
import me.totalfreedom.totalfreedommod.rank.CustomRank;

public interface IRankManager
{
    /**
     * The rank registry, which is the only supported way to ask what rank something holds or what
     * tier a permission node requires.
     */
    IRankRegistry getRegistry();

    /**
     * Load custom ranks from SQL, falling back to ranks.json. Never blocks: the SQL read runs
     * off-thread and its result is applied back on the main thread, so this is safe from a
     * command handler as well as from startup.
     */
    void loadRanks();

    /**
     * Whether the ranks in memory came from SQL rather than the ranks.json snapshot.
     */
    boolean isUsingSql();

    /**
     * Queue a write of every custom rank to SQL, followed by a refresh of the ranks.json
     * snapshot. Falls back to a JSON-only write when SQL is unavailable. Safe from a command
     * handler: the SQL round trips run off the main thread.
     */
    void saveRanks();

    /**
     * Wait for queued rank writes to land, up to {@code timeoutMs}.
     */
    void awaitPendingWrites(long timeoutMs);

    CustomRank getCustomRank(String id);

    void updatePlayerTeam(Player player);

    void updateAllPlayerTeams();

    /**
     * Get all custom ranks.
     */
    Map<String, CustomRank> getCustomRanks();

    /**
     * Get custom ranks sorted by level.
     */
    List<CustomRank> getCustomRanksSorted();

    void setCustomRank(CustomRank rank);

    /**
     * Remove a custom rank.
     */
    boolean removeCustomRank(String id);

    /**
     * Check if a custom rank exists.
     */
    boolean hasCustomRank(String id);

    /**
     * Whether {@code sender} may exercise an internal TFM permission node.
     *
     * @param sender     the command sender
     * @param permission the internal node, for example {@code tfm.admin.ban}
     */
    boolean hasPermission(CommandSender sender, String permission);

    /**
     * Whether {@code sender} holds a rank granted admin standing.
     */
    boolean isAdmin(CommandSender sender);

    /**
     * Whether {@code sender} holds a rank granted senior standing.
     */
    boolean isSeniorAdmin(CommandSender sender);

    /**
     * Whether the rank {@code rankId} grants senior standing, for staff who aren't online to ask.
     */
    boolean grantsSeniorStatus(String rankId);

    /**
     * Check if sender has permission to manage ranks.
     */
    boolean canManageRanks(CommandSender sender);

    /**
     * Build the main rank configuration menu.
     */
    Component buildMainMenu();

    /**
     * Build the rank edit menu.
     */
    Component buildEditMenu(CustomRank rank);

    /**
     * The rank whose name, colour and tag should be shown for {@code sender}.
     */
    Displayable getDisplay(CommandSender sender);

    /**
     * Resolves the rank a sender actually acts at.
     *
     * @return the sender's effective rank, or {@code null} when none could be resolved
     */
    CustomRank getEffectiveRank(CommandSender sender);

    /**
     * Orders players for listings: title displayers first by weight, then rank displayers by level.
     */
    Comparator<Player> byDisplayOrder();

    Component formatLoginMessage(Player player);
}

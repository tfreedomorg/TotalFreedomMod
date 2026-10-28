package me.totalfreedom.api.title;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public interface ITitleManager
{
    /**
     * Load titles from SQL, falling back to {@code titles.json}. Never blocks: the SQL read runs
     * off-thread and its result is applied back on the main thread.
     */
    void loadTitles();

    ITitle getTitle(String id);

    Map<String, ? extends ITitle> getTitles();

    /**
     * All titles ordered for display, heaviest first.
     */
    List<? extends ITitle> getTitlesSorted();

    boolean hasTitle(String id);

    /**
     * The titles {@code player} actually holds, skipping any id that no longer resolves so that a
     * deleted title simply stops applying rather than breaking every lookup for its holders.
     */
    List<? extends ITitle> getHeldTitles(Player player);

    /**
     * Resolves a set of stored ids into titles, ordered for display.
     */
    List<? extends ITitle> resolve(Collection<String> ids);

    /**
     * The title that should represent {@code player} on screen, or {@code null} when they hold
     * none. The heaviest held title wins.
     */
    ITitle getDisplayTitle(Player player);

    /**
     * Whether any title {@code sender} holds grants {@code permission}.
     */
    boolean grants(CommandSender sender, String permission);

    boolean grants(Player player, String permission);

    /**
     * Grants a title to a player. Returns false when the title does not exist or is already held.
     */
    boolean grantTitle(Player player, String titleId);

    /**
     * Revokes a title from a player. Returns false when they did not hold it.
     */
    boolean revokeTitle(Player player, String titleId);

    /**
     * Queue a write of every title to SQL, followed by a refresh of the JSON snapshot. Falls back
     * to a JSON-only write when SQL is unavailable. Safe from a command handler: the SQL round
     * trips run off the main thread.
     */
    void saveTitles();

    void awaitPendingWrites(long timeoutMs);

    /**
     * Every title id currently registered, for tab completion.
     */
    Set<String> getTitleIds();

    /**
     * The ids a player holds that still resolve to a registered title.
     */
    Set<String> getHeldTitleIds(Player player);

    /**
     * The title filling a given id, wrapped for callers that would rather not null-check.
     */
    Optional<? extends ITitle> find(String id);
}

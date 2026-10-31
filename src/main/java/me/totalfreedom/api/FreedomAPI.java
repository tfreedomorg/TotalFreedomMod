package me.totalfreedom.api;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import me.totalfreedom.api.banning.IBanManager;
import me.totalfreedom.api.blocking.item.IItemValidator;
import me.totalfreedom.api.blocking.sweep.ISweepScheduler;
import me.totalfreedom.api.cmd.CommandLoader;
import me.totalfreedom.api.config.IMainConfig;
import me.totalfreedom.api.economy.IBank;
import me.totalfreedom.api.economy.IEcoManager;
import me.totalfreedom.api.framework.IServiceManager;
import me.totalfreedom.api.player.IPlayerData;
import me.totalfreedom.api.player.IPlayerList;
import me.totalfreedom.api.rank.IConsoleSenderRegistry;
import me.totalfreedom.api.rank.IRankManager;
import me.totalfreedom.api.sql.IFreedomDatabase;
import me.totalfreedom.api.title.ITitleManager;
import me.totalfreedom.api.vanish.IVanishService;
import me.totalfreedom.api.world.IGenerationService;
import me.totalfreedom.api.world.IWorldManager;


/**
 * Curated capability surface exposed to services and commands. Backed by
 * {@code TotalFreedomMod} and its {@link IServiceManager}, this interface stands in for the
 * plugin class itself so consumers depend on capabilities rather than the god object that
 * provides them.
 */
public interface FreedomAPI extends Plugin
{

    /**
     * @return The service registry backing this API. Prefer the named accessors below for
     * frequently used services; fall back to {@code services().require(Class)} for the rest.
     */
    IServiceManager services();

    /**
     * @return The registry for optional third-party plugin integrations (CoreProtect,
     * Essentials, WorldEdit, LibsDisguises).
     */
    IServiceManager bridges();

    /**
     * @return The loader plugins depending on TFM can use to register their own commands. See
     * {@link CommandLoader#load(Class)}.
     */
    CommandLoader commands();

    IBank bank();

    /**
     * @return The service owning every online player's {@link me.totalfreedom.api.economy.IEcoPlayer}.
     */
    IEcoManager economy();

    IMainConfig config();

    BuildInfo buildInfo();

    IFreedomDatabase database();


    IPlayerList players();

    IRankManager ranks();

    ITitleManager titles();

    IWorldManager worlds();

    IGenerationService generation();

    IVanishService vanish();

    IItemValidator itemValidator();

    IConsoleSenderRegistry consoleSenders();

    IBanManager bans();

    IAutoEject autoEject();

    ISweepScheduler sweepScheduler();

    IGameRuleHandler gameRules();

    /**
     * @param target The target player
     * @return {@code target}'s stored player data, loading it if necessary.
     */
    IPlayerData getPlayerData(Player target);

    /**
     * Persists {@code data} back to storage.
     * @param data The playerdata object to save on this player
     */
    void savePlayerData(IPlayerData data);

    /**
     * @param sender The command sender, could be either a player or console sender.
     * @return Whether {@code sender} is a currently active admin.
     */
    boolean isAdmin(CommandSender sender);
}

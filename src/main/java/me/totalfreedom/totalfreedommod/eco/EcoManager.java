package me.totalfreedom.totalfreedommod.eco;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import reactor.core.publisher.Mono;

import me.totalfreedom.api.FreedomAPI;
import me.totalfreedom.api.economy.IBank;
import me.totalfreedom.api.economy.IEcoManager;
import me.totalfreedom.api.economy.IEcoPlayer;
import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.sql.PersistenceQueue;
import me.totalfreedom.totalfreedommod.util.FLog;

/**
 * Owns the {@link Bank} and every online player's {@link EcoPlayer}, backed by SQL when the
 * database is available. There is no JSON fallback here: with no database, balances live for
 * the current session only.
 */
public class EcoManager extends FreedomService implements IEcoManager
{

    private static final long SHUTDOWN_FLUSH_TIMEOUT_MS = 10L * 1000L;

    private final Map<String, EcoPlayer> ecoPlayers = new HashMap<>(); // key: lowercase username
    private final PersistenceQueue writes = new PersistenceQueue("economy");
    private final Bank bank;

    public EcoManager(final FreedomAPI plugin)
    {
        super(plugin);

        this.bank = new Bank(this);
    }

    @Override
    public void onStart()
    {
        ecoPlayers.clear();

        for (Player player : server.getOnlinePlayers())
            getEcoPlayer(player); // this will load all players into memory and put them in the map. 

        plugin.database().whenReady(this::reconcileLoadedPlayers); // this will update the players in case sql finishes after this gets initialized.
        plugin.database().whenReady(this::reconcileBank); // this will update the bank info for the same reason
    }

    @Override
    public void onStop()
    {
        save();
        writes.await(SHUTDOWN_FLUSH_TIMEOUT_MS);
    }

    public IBank bank()
    {
        return this.bank;
    }

    /**
     * @return {@code player}'s {@link IEcoPlayer}, loading it from storage if necessary. May not return null.
     */
    public IEcoPlayer getEcoPlayer(final Player player)
    {
        final String key = player.getName().toLowerCase();

        final EcoPlayer cached = ecoPlayers.get(key);
        if (cached != null)
            return cached;

        final Optional<EcoPlayerData> data = loadData(player.getName());
        final EcoPlayer ecoPlayer = data.map(d -> new EcoPlayer(d.username(), 
                                                                d.uuid(), 
                                                                d.walletBalance(), 
                                                                d.checkingBalance(), 
                                                                d.savingsBalance()))
                                        .orElseGet(() -> new EcoPlayer(player.getName(), 
                                                                       player.getUniqueId()));

        ecoPlayers.put(key, ecoPlayer);

        if (data.isEmpty())
            saveOne(ecoPlayer);

        return ecoPlayer;
    }

    public Optional<IEcoPlayer> getLoadedEcoPlayer(final String username)
    {
        return Optional.ofNullable(ecoPlayers.get(username.toLowerCase()));
    }

    /**
     * @return Every currently loaded (online) player's economy data.
     */
    public Collection<IEcoPlayer> getLoadedPlayers()
    {
        return List.copyOf(ecoPlayers.values());
    }

    /**
     * Queue a write of every loaded player's balances, plus the bank's own.
     */
    public void save()
    {
        List.copyOf(ecoPlayers.values())
            .forEach(this::saveOne);
        saveBank();
    }

    private void saveOne(final EcoPlayer player)
    {
        if (!usingSql())
            return;

        final EcoPlayerData snapshot = new EcoPlayerData(player.name(), 
                                                         player.uuid(),
                                                         player.getWallet().balance(), 
                                                         player.getBankAccount().checkingBalance(), 
                                                         player.getBankAccount().savingsBalance());

        writes.enqueue(plugin.database()
                             .getEconomyRepository()
                             .save(snapshot)
                             .onErrorResume(ex ->
                             {
                                 FLog.error("Could not save economy data for {} to SQL", player.name(), ex);
                                 return Mono.empty();
                             }));
    }

    private Optional<EcoPlayerData> loadData(final String username)
    {
        if (!usingSql())
            return Optional.empty();

        try
        {
            return plugin.database().getEconomyRepository().findByUsername(username);
        }
        catch (Exception ex)
        {
            FLog.warn("Failed to load economy data for " + username + " from SQL: " + ex.getMessage());
            return Optional.empty();
        }
    }

    private void reconcileLoadedPlayers()
    {
        if (!usingSql())
            return;

        List.copyOf(ecoPlayers.values())
            .forEach(ecoPlayer -> loadData(ecoPlayer.name()).ifPresent(ecoPlayer::applySnapshot));
    }

    private void saveBank()
    {
        if (!usingSql())
            return;

        final EcoBankData snapshot = new EcoBankData(bank.bankNative().checkingBalance(), bank.bankNative().savingsBalance());

        writes.enqueue(plugin.database()
                             .getEconomyRepository()
                             .saveBankAsync(snapshot)
                             .onErrorResume(ex ->
                             {
                                 FLog.error("Could not save bank data to SQL", ex);
                                 return Mono.empty();
                             }));
    }

    /**
     * Loads the bank's persisted balances, applying them if found or persisting the bank's
     * current (default) balances if this is the first time it has ever started.
     */
    private void reconcileBank()
    {
        if (!usingSql())
            return;

        try
        {
            final Optional<EcoBankData> data = plugin.database().getEconomyRepository().loadBank();

            if (data.isPresent())
                bank.applySnapshot(data.get());
            else
                saveBank();
        }
        catch (Exception ex)
        {
            FLog.warn("Failed to load bank data from SQL: " + ex.getMessage());
        }
    }

    private boolean usingSql()
    {
        return plugin.database() != null && plugin.database().isInitialized();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(final PlayerJoinEvent event)
    {
        getEcoPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuit(final PlayerQuitEvent event)
    {
        final EcoPlayer ecoPlayer = ecoPlayers.remove(event.getPlayer().getName().toLowerCase());

        if (ecoPlayer != null && plugin.isEnabled())
            saveOne(ecoPlayer);
    }
}

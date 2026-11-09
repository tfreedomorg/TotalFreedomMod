package me.totalfreedom.totalfreedommod.sql.adapter.generic;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.eco.EcoBankData;
import me.totalfreedom.totalfreedommod.eco.EcoPlayerData;
import me.totalfreedom.totalfreedommod.sql.StatementHandler;
import me.totalfreedom.totalfreedommod.sql.adapter.DatabaseAdapter;
import me.totalfreedom.totalfreedommod.sql.adapter.EconomyRepository;

/**
 * All dialect differences are resolved through the {@link DatabaseAdapter} passed in.
 */
public class GenericEconomyRepository implements EconomyRepository
{
    /**
     * The bank's native account is a single row, always upserted under this fixed key.
     */
    private static final String BANK_ROW_ID = "bank";

    private final StatementHandler statementHandler;
    private final DatabaseAdapter adapter;

    private final String tblEconomy;
    private final String colUsername;
    private final String colUuid;
    private final String colWalletBalance;
    private final String colCheckingBalance;
    private final String colSavingsBalance;
    private final String colUpdatedAt;
    private final String selectColumns;

    private final String tblBank;
    private final String colBankId;
    private final String colBankChecking;
    private final String colBankSavings;
    private final String bankSelectSql;
    private final String bankUpsertSql;

    public GenericEconomyRepository(StatementHandler statementHandler, DatabaseAdapter adapter)
    {
        this.statementHandler = statementHandler;
        this.adapter = adapter;

        this.tblEconomy = adapter.quoteIdentifier("economy_players");
        this.colUsername = adapter.quoteIdentifier("username");
        this.colUuid = adapter.quoteIdentifier("uuid");
        this.colWalletBalance = adapter.quoteIdentifier("wallet_balance");
        this.colCheckingBalance = adapter.quoteIdentifier("checking_balance");
        this.colSavingsBalance = adapter.quoteIdentifier("savings_balance");
        this.colUpdatedAt = adapter.quoteIdentifier("updated_at");
        this.selectColumns = String.format("%s, %s, %s, %s, %s",
                colUsername, colUuid, colWalletBalance, colCheckingBalance, colSavingsBalance);

        this.tblBank = adapter.quoteIdentifier("economy_bank");
        this.colBankId = adapter.quoteIdentifier("id");
        this.colBankChecking = adapter.quoteIdentifier("checking_balance");
        this.colBankSavings = adapter.quoteIdentifier("savings_balance");
        final String colBankUpdatedAt = adapter.quoteIdentifier("updated_at");

        this.bankSelectSql = String.format("SELECT %s, %s FROM %s WHERE %s = ?",
                colBankChecking, colBankSavings, tblBank, colBankId);
        this.bankUpsertSql = String.format("INSERT INTO %s (%s, %s, %s, %s) VALUES (?, ?, ?, %s) %s",
                tblBank, colBankId, colBankChecking, colBankSavings, colBankUpdatedAt, adapter.currentTimestamp(),
                adapter.upsertClause(colBankId, colBankChecking, colBankSavings, colBankUpdatedAt));
    }

    @Override
    public void insert(final EcoPlayerData data) throws SQLException
    {
        String sql = String.format("INSERT INTO %s (%s, %s) VALUES (?, ?, ?, ?, ?, %s)",
                tblEconomy, selectColumns, colUpdatedAt, adapter.currentTimestamp());

        statementHandler.executeUpdate(sql,
                data.username(),
                data.uuid().toString(),
                data.walletBalance(),
                data.checkingBalance(),
                data.savingsBalance());
    }

    @Override
    public Optional<EcoPlayerData> findByUsername(final String username) throws SQLException
    {
        String sql = String.format("SELECT %s FROM %s WHERE %s", selectColumns, tblEconomy,
                                   adapter.caseInsensitiveEquals(colUsername, "?"));
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, username);
             ResultSet rs = stmt.executeQuery())
        {
            return rs.next() ? Optional.of(loadFromRow(rs)) : Optional.empty();
        }
    }

    @Override
    public boolean exists(final String username) throws SQLException
    {
        String sql = String.format("SELECT COUNT(*) FROM %s WHERE %s", tblEconomy,
                                   adapter.caseInsensitiveEquals(colUsername, "?"));
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, username);
             ResultSet rs = stmt.executeQuery())
        {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    @Override
    public boolean update(final EcoPlayerData data) throws SQLException
    {
        String sql = String.format("UPDATE %s SET %s = ?, %s = ?, %s = ?, %s = %s WHERE %s",
                tblEconomy, colWalletBalance, colCheckingBalance, colSavingsBalance,
                colUpdatedAt, adapter.currentTimestamp(), adapter.caseInsensitiveEquals(colUsername, "?"));

        return statementHandler.executeUpdate(sql,
                data.walletBalance(),
                data.checkingBalance(),
                data.savingsBalance(),
                data.username()) > 0;
    }

    @Override
    public void saveOrUpdate(final EcoPlayerData data) throws SQLException
    {
        if (exists(data.username()))
        {
            update(data);
        }
        else
        {
            insert(data);
        }
    }

    @Override
    public boolean delete(final String username) throws SQLException
    {
        String sql = String.format("DELETE FROM %s WHERE %s", tblEconomy,
                                   adapter.caseInsensitiveEquals(colUsername, "?"));
        return statementHandler.executeUpdate(sql, username) > 0;
    }

    @Override
    public void deleteAllSync() throws SQLException
    {
        statementHandler.executeUpdate(String.format("DELETE FROM %s", tblEconomy));
    }

    @Override
    public Map<String, EcoPlayerData> loadAll() throws SQLException
    {
        Map<String, EcoPlayerData> players = new HashMap<>();

        String sql = String.format("SELECT %s FROM %s", selectColumns, tblEconomy);
        try (ResultSet rs = statementHandler.executeQuery(sql))
        {
            while (rs.next())
            {
                EcoPlayerData data = loadFromRow(rs);
                players.put(data.username(), data);
            }
        }

        return players;
    }

    @Override
    public Mono<Map<String, EcoPlayerData>> loadAllAsync()
    {
        return statementHandler.supplyMono(this::loadAll);
    }

    @Override
    public Mono<Void> save(final EcoPlayerData data)
    {
        return statementHandler.runMono(() -> saveOrUpdate(data));
    }

    @Override
    public Mono<Boolean> deleteAsync(final String username)
    {
        return statementHandler.supplyMono(() -> delete(username));
    }

    @Override
    public Mono<Void> deleteAll()
    {
        return statementHandler.runMono(this::deleteAllSync);
    }

    @Override
    public Optional<EcoBankData> loadBank() throws SQLException
    {
        try (PreparedStatement stmt = statementHandler.prepareStatement(bankSelectSql, BANK_ROW_ID);
             ResultSet rs = stmt.executeQuery())
        {
            return rs.next()
                    ? Optional.of(new EcoBankData(rs.getInt("checking_balance"), rs.getInt("savings_balance")))
                    : Optional.empty();
        }
    }

    @Override
    public void saveBank(final EcoBankData data) throws SQLException
    {
        statementHandler.executeUpdate(bankUpsertSql, BANK_ROW_ID, data.checkingBalance(), data.savingsBalance());
    }

    @Override
    public Mono<Optional<EcoBankData>> loadBankAsync()
    {
        return statementHandler.supplyMono(this::loadBank);
    }

    @Override
    public Mono<Void> saveBankAsync(final EcoBankData data)
    {
        return statementHandler.runMono(() -> saveBank(data));
    }

    private EcoPlayerData loadFromRow(final ResultSet rs) throws SQLException
    {
        return new EcoPlayerData(
                rs.getString("username"),
                UUID.fromString(rs.getString("uuid")),
                rs.getInt("wallet_balance"),
                rs.getInt("checking_balance"),
                rs.getInt("savings_balance"));
    }
}

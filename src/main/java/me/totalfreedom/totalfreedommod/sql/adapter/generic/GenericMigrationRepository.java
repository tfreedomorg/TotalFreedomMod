package me.totalfreedom.totalfreedommod.sql.adapter.generic;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;

import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.sql.StatementHandler;
import me.totalfreedom.totalfreedommod.sql.adapter.DatabaseAdapter;
import me.totalfreedom.totalfreedommod.sql.adapter.MigrationRepository;

/**
 * All dialect differences are resolved through the {@link DatabaseAdapter} passed in.
 */
public class GenericMigrationRepository implements MigrationRepository
{
    private final StatementHandler statementHandler;

    private final String selectAllSql;
    private final String insertSql;
    private final String deleteAllSql;

    public GenericMigrationRepository(StatementHandler statementHandler, DatabaseAdapter adapter)
    {
        this.statementHandler = statementHandler;

        final String tblMigrations = adapter.quoteIdentifier("migrations");
        final String colVersion = adapter.quoteIdentifier("version");
        final String colAppliedAt = adapter.quoteIdentifier("applied_at");

        this.selectAllSql = String.format("SELECT %s FROM %s", colVersion, tblMigrations);
        this.insertSql = String.format("%s INTO %s (%s, %s) VALUES (?, %s)%s",
                adapter.insertIgnoreSyntax(), tblMigrations, colVersion, colAppliedAt,
                adapter.currentTimestamp(), adapter.insertIgnoreSuffix());
        this.deleteAllSql = String.format("DELETE FROM %s", tblMigrations);
    }

    @Override
    public Set<String> findApplied() throws SQLException
    {
        final Set<String> versions = new LinkedHashSet<>();
        try (ResultSet rs = statementHandler.executeQuery(selectAllSql))
        {
            while (rs.next())
            {
                versions.add(rs.getString(1));
            }
        }
        return versions;
    }

    @Override
    public void markApplied(String version) throws SQLException
    {
        statementHandler.executeUpdate(insertSql, version);
    }

    @Override
    public void clear() throws SQLException
    {
        statementHandler.executeUpdate(deleteAllSql);
    }

    @Override
    public Mono<Set<String>> findAppliedAsync()
    {
        return statementHandler.supplyMono(this::findApplied);
    }

    @Override
    public Mono<Void> markAppliedAsync(String version)
    {
        return statementHandler.runMono(() -> markApplied(version));
    }
}

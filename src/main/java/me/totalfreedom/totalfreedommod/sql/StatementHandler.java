package me.totalfreedom.totalfreedommod.sql;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.concurrent.Callable;

import reactor.core.publisher.Mono;

/**
 * Executes SQL against connections borrowed from {@link ConnectionHandler}'s pool.
 *
 * The {@link PreparedStatement}/{@link ResultSet} returned by {@link #prepareStatement}
 * and {@link #executeQuery} are wrapped so that closing them via try-with-resources
 * also returns the borrowed pooled {@link Connection}. Callers do not need to change how they consume
 * these methods, but the underlying connection is no longer held open indefinitely.
 */
public class StatementHandler
{
    private final ConnectionHandler connectionHandler;

    public StatementHandler(ConnectionHandler connectionHandler)
    {
        this.connectionHandler = connectionHandler;
    }

    /**
     * Prepares a statement, guarded by the same {@link AccessController} permit used by the
     * reactive path so synchronous and async callers draw from one fairly-queued pool of
     * concurrent queries. The permit is released when the returned statement is closed OR when a ResultSet is obtained.
     */
    public PreparedStatement prepareStatement(String sql, Object... params) throws SQLException
    {
        final AccessController accessController = connectionHandler.getAccessController();
        accessController.acquireSync();

        Connection connection;
        try
        {
            connection = connectionHandler.borrowConnection();
        }
        catch (SQLException e)
        {
            accessController.releaseSync();
            throw e;
        }

        PreparedStatement statement;
        try
        {
            statement = connection.prepareStatement(sql);
            setParameters(statement, params);
        }
        catch (SQLException e)
        {
            closeQuietly(connection);
            accessController.releaseSync();
            throw e;
        }
        return closingStatementProxy(statement, connection, accessController);
    }

    public ResultSet executeQuery(String sql, Object... params) throws SQLException
    {
        PreparedStatement statement = prepareStatement(sql, params);
        try
        {
            return closingResultSetProxy(statement.executeQuery(), statement);
        }
        catch (SQLException e)
        {
            closeQuietly(statement);
            throw e;
        }
    }

    public int executeUpdate(String sql, Object... params) throws SQLException
    {
        try (PreparedStatement statement = prepareStatement(sql, params))
        {
            return statement.executeUpdate();
        }
    }

    public long executeUpdateReturnKey(String sql, Object... params) throws SQLException
    {
        final AccessController accessController = connectionHandler.getAccessController();
        accessController.acquireSync();
        try
        {
            Connection connection = connectionHandler.borrowConnection();
            try (PreparedStatement statement = connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS))
            {
                setParameters(statement, params);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys())
                {
                    return keys.next() ? keys.getLong(1) : -1L;
                }
            }
            finally
            {
                closeQuietly(connection);
            }
        }
        finally
        {
            accessController.releaseSync();
        }
    }

    /**
     * Run {@code work} on the SQL scheduler under a single {@link AccessController} permit.
     * The statements {@code work} issues re-enter that same permit rather than queueing for
     * one of their own, so a repository operation costs exactly one permit no matter how many
     * round trips it makes.
     */
    public <T> Mono<T> supplyMono(Callable<T> work)
    {
        return connectionHandler.getAccessController().guard(work);
    }

    public Mono<Void> runMono(SqlRunnable work)
    {
        return connectionHandler.getAccessController().<Void>guard(() ->
        {
            work.run();
            return null;
        }).then();
    }

    @FunctionalInterface
    public interface SqlRunnable
    {
        void run() throws SQLException;
    }

    public void close()
    {
        connectionHandler.shutdown();
    }

    private void setParameters(PreparedStatement statement, Object... params) throws SQLException
    {
        int index = 1;
        for (Object param : params)
        {
            index = setParameter(statement, index, param);
        }
    }

    private int setParameter(PreparedStatement statement, int index, Object param) throws SQLException
    {
        if (param == null)
        {
            statement.setObject(index, null);
            return index + 1;
        }

        if (param instanceof String[] ss)
        {
            for (String s : (String[]) ss)
            {
                statement.setString(index++, s);
            }
            return index;
        }

        if (param instanceof Collection<?> coll)
        {
            for (Object item : coll)
            {
                if (item instanceof String)
                {
                    statement.setString(index++, (String) item);
                }
                else
                {
                    statement.setObject(index++, item);
                }
            }
            return index;
        }

        if (param instanceof String s)
        {
            statement.setString(index, s);
        }
        else if (param instanceof Integer || param.getClass() == int.class) // no instanceof capture available here
        {
            statement.setInt(index, (Integer) param);
        }
        else if (param instanceof Long || param.getClass() == long.class)
        {
            statement.setLong(index, (Long) param);
        }
        else if (param instanceof Double || param.getClass() == double.class)
        {
            statement.setDouble(index, (Double) param);
        }
        else if (param instanceof Float || param.getClass() == float.class)
        {
            statement.setFloat(index, (Float) param);
        }
        else if (param instanceof Boolean || param.getClass() == boolean.class)
        {
            statement.setBoolean(index, (Boolean) param);
        }
        else if (param instanceof Byte || param.getClass() == byte.class)
        {
            statement.setByte(index, (Byte) param);
        }
        else if (param instanceof Short || param.getClass() == short.class)
        {
            statement.setShort(index, (Short) param);
        }
        else
        {
            statement.setObject(index, param);
        }

        return index + 1;
    }

    private static void closeQuietly(AutoCloseable closeable)
    {
        try
        {
            closeable.close();
        }
        catch (Exception ignored) {}
    }

    /**
     * Wraps a PreparedStatement so that closing it also returns the pooled Connection
     * that produced it and releases the {@link AccessController} permit acquired in
     * {@link #prepareStatement}, without changing anything about how callers use the statement.
     */
    private static PreparedStatement closingStatementProxy(PreparedStatement target, Connection connection,
            AccessController accessController)
    {
        return (PreparedStatement) Proxy.newProxyInstance(
                StatementHandler.class.getClassLoader(),
                new Class<?>[] { PreparedStatement.class },
                (proxy, method, args) ->
                {
                    if ("close".equals(method.getName()))
                    {
                        try
                        {
                            target.close();
                        }
                        finally
                        {
                            try
                            {
                                connection.close();
                            }
                            finally
                            {
                                accessController.releaseSync();
                            }
                        }
                        return null;
                    }
                    try
                    {
                        return method.invoke(target, args);
                    }
                    catch (InvocationTargetException e)
                    {
                        throw e.getCause();
                    }
                });
    }

    /**
     * Wraps a ResultSet so that closing it also closes the proxied PreparedStatement
     * that produced it, cascading through to the pooled Connection.
     */
    private static ResultSet closingResultSetProxy(ResultSet target, PreparedStatement statement)
    {
        return (ResultSet) Proxy.newProxyInstance(
                StatementHandler.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, args) ->
                {
                    if ("close".equals(method.getName()))
                    {
                        try
                        {
                            target.close();
                        }
                        finally
                        {
                            statement.close();
                        }
                        return null;
                    }
                    try
                    {
                        return method.invoke(target, args);
                    }
                    catch (InvocationTargetException e)
                    {
                        throw e.getCause();
                    }
                });
    }
}

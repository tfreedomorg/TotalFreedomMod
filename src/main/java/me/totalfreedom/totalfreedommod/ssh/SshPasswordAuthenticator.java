package me.totalfreedom.totalfreedommod.ssh;

import java.util.HashMap;
import java.util.Map;

import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;

import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.session.ServerSession;

/**
 * Authenticates SSH users against the configured password in config.yml.
 */
public class SshPasswordAuthenticator implements PasswordAuthenticator
{

    private final Map<ServerSession, Integer> failCounts = new HashMap<>();

    @Override
    public boolean authenticate(String username, String password, ServerSession session)
    {
        String configPassword = ConfigEntry.SSH_PASSWORD.getString();

        // Reject if no password is configured
        if (configPassword == null || configPassword.isEmpty())
        {
            FLog.warning("SSH login attempt by '" + username + "' rejected: no password configured in config.yml (ssh.password is empty).");
            return false;
        }

        if (configPassword.equals(password))
        {
            failCounts.remove(session);
            session.setAttribute(SshDaemon.AUTH_METHOD_KEY, SshAuthMethod.PASSWORD);
            FLog.info("SSH login successful for user: " + username);
            return true;
        }

        // Failed attempt
        FLog.info("Failed SSH login for '" + username + "' using password authentication.");

        try
        {
            // Anti-brute-force delay
            Thread.sleep(3000);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }

        int fails = failCounts.getOrDefault(session, 0) + 1;
        failCounts.put(session, fails);

        int maxAttempts = ConfigEntry.SSH_MAX_FAILED_ATTEMPTS.getInteger();
        if (fails >= maxAttempts)
        {
            FLog.info("SSH session for '" + username + "' closed after " + maxAttempts + " failed attempts.");
            failCounts.remove(session);
            session.close(true);
        }

        return false;
    }
}

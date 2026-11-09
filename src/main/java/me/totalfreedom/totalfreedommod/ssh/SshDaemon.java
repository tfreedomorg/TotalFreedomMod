package me.totalfreedom.totalfreedommod.ssh;

import me.totalfreedom.api.FreedomAPI;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;

import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;

public class SshDaemon extends FreedomService
{
    public static final AttributeRepository.AttributeKey<SshAuthMethod> AUTH_METHOD_KEY =
            new AttributeRepository.AttributeKey<>();
    public static final AttributeRepository.AttributeKey<String> FINGERPRINT_KEY =
            new AttributeRepository.AttributeKey<>();
    public static final AttributeRepository.AttributeKey<String> IDENTITY_KEY =
            new AttributeRepository.AttributeKey<>();

    private SshServer sshd;
    private SshIdentityStore identityStore;

    public SshDaemon(FreedomAPI plugin)
    {
        super(plugin);
    }

    @Override
    public void onStart()
    {
        if (!ConfigEntry.SSH_ENABLED.getBoolean())
        {
            return;
        }

        int port = ConfigEntry.SSH_PORT.getInteger();

        File identitiesDir = new File(plugin.getDataFolder(), "ssh_auth_keys");
        identityStore = new SshIdentityStore(identitiesDir);

        sshd = SshServer.setUpDefaultServer();
        sshd.setPort(port);

        Path hostKeyPath = plugin.getDataFolder().toPath().resolve("ssh_host_key");
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKeyPath));

        boolean usePassword = ConfigEntry.SSH_AUTH_PASSWORD.getBoolean();
        boolean usePublicKey = ConfigEntry.SSH_AUTH_KEY.getBoolean();
        boolean useTotp = ConfigEntry.SSH_AUTH_TOTP.getBoolean();

        if (!usePassword && !usePublicKey)
        {
            FLog.warn("SSH: Both authentication modes are disabled! Defaulting to password.");
            usePassword = true;
        }

        if (useTotp && !usePublicKey)
        {
            FLog.warn("SSH: Public key authentication is not enabled so two-factor will be ignored.");
            useTotp = false;
        }

        if (usePassword)
        {
            sshd.setPasswordAuthenticator(new SshPasswordAuthenticator());
        }

        if (usePublicKey)
        {
            sshd.setPublickeyAuthenticator(new SshPublicKeyAuthenticator(identityStore));

            if (useTotp)
            {
                sshd.setKeyboardInteractiveAuthenticator(new SshHandshakeAuthenticator(identityStore));

                if (usePassword)
                {
                    // Password-based auth doesn't bind an identity key, so nothing to require.
                    FLog.warn("SSH: Two-factor auth is enabled alongside password mode! Please note that password-based login will NOT prompt for a code.");
                }
                else
                {
                    sshd.getProperties().put("auth.methods", "publickey,keyboard-interactive");
                }
            }
        }

        sshd.setShellFactory(new SshConsoleShellFactory(plugin));
        sshd.setCommandFactory(new SshConsoleCommandFactory(plugin));

        try
        {
            sshd.start();
            FLog.info("SSH daemon started on port {}", port);
        }
        catch (IOException e)
        {
            FLog.error("Failed to start SSH daemon on port {}!", port, e);
        }
    }

    public void reloadIdentities()
    {
        if (identityStore != null)
        {
            identityStore.reload();
        }
    }

    public SshIdentityStore getIdentityStore()
    {
        return identityStore;
    }

    @Override
    public void onStop()
    {
        if (sshd != null)
        {
            try
            {
                sshd.stop(true);
                FLog.info("SSH daemon stopped.");
            }
            catch (Exception e)
            {
                FLog.error("Error stopping SSH daemon", e);
            }
        }
    }
}

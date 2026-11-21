package me.totalfreedom.totalfreedommod.ssh;

import java.security.PublicKey;

import me.totalfreedom.totalfreedommod.util.FLog;

import org.apache.sshd.common.config.keys.AuthorizedKeyEntry;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator;
import org.apache.sshd.server.session.ServerSession;

public class SshPublicKeyAuthenticator implements PublickeyAuthenticator
{
    private final SshIdentityStore identityStore;

    public SshPublicKeyAuthenticator(SshIdentityStore identityStore)
    {
        this.identityStore = identityStore;
    }

    @Override
    public boolean authenticate(String username, PublicKey key, ServerSession session)
    {
        String fingerprint;
        try
        {
            fingerprint = KeyUtils.getFingerPrint(key);
        }
        catch (Exception e)
        {
            FLog.warning("[SSH] Could not compute key fingerprint: " + e.getMessage());
            return false;
        }

        for (SshIdentity identity : identityStore.getAll())
        {
            for (String keyStr : identity.keys().values())
            {
                try
                {
                    AuthorizedKeyEntry entry = AuthorizedKeyEntry.parseAuthorizedKeyEntry(keyStr.trim());
                    PublicKey registered = entry.resolvePublicKey(null, PublicKeyEntryResolver.FAILING);
                    if (KeyUtils.compareKeys(key, registered))
                    {
                        session.setAttribute(SshDaemon.FINGERPRINT_KEY, fingerprint);
                        session.setAttribute(SshDaemon.IDENTITY_KEY, identity.identifier());
                        FLog.info("[SSH] Key matched identity '" + identity.identifier()
                                + "' for SSH user: " + username);
                        return true;
                    }
                }
                catch (Exception e)
                {
                    FLog.warning("[SSH] Bad key in identity '" + identity.identifier() + "': " + e.getMessage());
                }
            }
        }

        FLog.warning("[SSH] No matching key found for SSH user: " + username);
        return false;
    }
}

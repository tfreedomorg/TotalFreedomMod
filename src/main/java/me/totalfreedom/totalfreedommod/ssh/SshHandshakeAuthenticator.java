package me.totalfreedom.totalfreedommod.ssh;

import java.util.List;

import me.totalfreedom.totalfreedommod.util.FLog;

import org.apache.sshd.server.auth.keyboard.InteractiveChallenge;
import org.apache.sshd.server.auth.keyboard.KeyboardInteractiveAuthenticator;
import org.apache.sshd.server.session.ServerSession;

public class SshHandshakeAuthenticator implements KeyboardInteractiveAuthenticator
{
    private final SshIdentityStore identityStore;

    public SshHandshakeAuthenticator(SshIdentityStore identityStore)
    {
        this.identityStore = identityStore;
    }

    @Override
    public InteractiveChallenge generateChallenge(ServerSession session, String username, String lang, String subMethods)
    {
        if (session.getAttribute(SshDaemon.FINGERPRINT_KEY) == null)
        {
            return null;
        }

        String identityId = session.getAttribute(SshDaemon.IDENTITY_KEY);
        SshIdentity identity = identityId != null ? identityStore.get(identityId) : null;

        if (identity == null || identity.totpSecret() == null)
        {
            FLog.warning("[SSH] Access denied — no TOTP secret configured for identity: " + identityId);
            return null;
        }

        InteractiveChallenge challenge = new InteractiveChallenge();
        challenge.setInteractionName("Two-Factor Authentication");
        challenge.setInteractionInstruction("Enter the code from your authenticator app.");
        challenge.addPrompt("TOTP code: ", false);
        return challenge;
    }

    @Override
    public boolean authenticate(ServerSession session, String username, List<String> responses)
    {
        if (responses == null || responses.isEmpty())
        {
            return false;
        }

        String identityId = session.getAttribute(SshDaemon.IDENTITY_KEY);
        SshIdentity identity = identityId != null ? identityStore.get(identityId) : null;

        if (identity == null || identity.totpSecret() == null)
        {
            FLog.warning("[SSH] No TOTP secret configured for identity: " + identityId);
            return false;
        }

        if (!TotpUtil.verify(identity.totpSecret(), responses.get(0)))
        {
            FLog.info("[SSH] TOTP verification failed for: " + identity.identifier());
            return false;
        }

        session.setAttribute(SshDaemon.AUTH_METHOD_KEY, SshAuthMethod.KEY_TOKEN);
        identityStore.updateLastLogin(identityId);

        FLog.info("[SSH] TOTP verified — session user: " + identity.identifier());
        return true;
    }
}

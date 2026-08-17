package org.telegram.messenger;

import android.content.SharedPreferences;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPCMls;

/**
 * The six words that get an account back when the phone is gone.
 *
 * The counterpart of MlsRecoveryPhrase.swift, and the same shape for the same
 * reason: what the server holds has to be identical on both clients, or a phone
 * set up on one of them cannot get an account back that was made on the other.
 *
 * The words are made here, on the device. The server is told a one-way
 * derivation of them - enough to recognise somebody typing them, useless for
 * signing in as them and useless for reading anything. That is the difference
 * from what this used to be: the server made the phrase and sent it as a
 * message, which meant it had held the words in the clear.
 *
 * Shown to their owner once, in the chat with themselves, because a phrase
 * nobody has written down is a way back that does not exist.
 */
public class MlsRecoveryPhrase {

    /**
     * Which derivation the stored secret was made with.
     *
     * Bumped when the derivation changes, so a device that registered under the
     * old one registers again from the words it still has rather than leaving
     * the owner with a phrase the server no longer recognises. It changed once
     * already, when the strings the keys are derived from stopped saying 2bytes.
     */
    private static final int CURRENT_DERIVATION = 2;

    private static final MlsRecoveryPhrase[] instances =
            new MlsRecoveryPhrase[UserConfig.MAX_ACCOUNT_COUNT];

    private final int currentAccount;
    private boolean working;

    public static MlsRecoveryPhrase getInstance(int account) {
        synchronized (MlsRecoveryPhrase.class) {
            if (instances[account] == null) {
                instances[account] = new MlsRecoveryPhrase(account);
            }
            return instances[account];
        }
    }

    private MlsRecoveryPhrase(int currentAccount) {
        this.currentAccount = currentAccount;
    }

    private SharedPreferences storage() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences("mls" + currentAccount, android.content.Context.MODE_PRIVATE);
    }

    /** The words this account was given, or null if it has none yet. */
    public String phrase() {
        return storage().getString("recovery_phrase", null);
    }

    /**
     * Makes a phrase if this account has none, and registers its derivation.
     *
     * Called when the account starts. Registering again for a phrase that
     * already exists is not a waste: the derivation may have changed under it,
     * and the owner still has the same words written down.
     */
    public void ensure() {
        synchronized (this) {
            if (working) {
                return;
            }
            working = true;
        }

        Utilities.globalQueue.postRunnable(() -> {
            try {
                String existing = phrase();
                if (existing != null) {
                    if (storage().getInt("recovery_derivation", 0) != CURRENT_DERIVATION) {
                        // The words are still the owner's; only what the server
                        // was told has gone stale.
                        register(existing, false);
                    } else {
                        synchronized (this) {
                            working = false;
                        }
                    }
                    return;
                }
                register(MlsCore.recoveryPhrase(), true);
            } catch (MlsCore.MlsException e) {
                synchronized (this) {
                    working = false;
                }
                FileLog.e("mls: cannot make a recovery phrase: " + e.getMessage());
            }
        });
    }

    private void register(String phrase, boolean isNew) throws MlsCore.MlsException {
        TLRPCMls.TL_mls_setRecoverySecret request = new TLRPCMls.TL_mls_setRecoverySecret();
        request.secret = MlsCore.recoveryAuthSecret(phrase);

        ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) -> {
            synchronized (MlsRecoveryPhrase.this) {
                working = false;
            }
            if (error != null) {
                // Not stored on failure. A phrase the owner has been shown and
                // the server does not know is worse than none: it is a way back
                // that looks like it exists.
                FileLog.e("mls: the recovery phrase was not registered: " + error.text);
                return;
            }
            storage().edit()
                    .putString("recovery_phrase", phrase)
                    .putInt("recovery_derivation", CURRENT_DERIVATION)
                    .apply();
            FileLog.d("mls: a recovery phrase is registered for this account");
            if (isNew) {
                show(phrase);
            }
        });
    }

    /**
     * Puts the words where their owner will find them: the chat with
     * themselves, which is the one place a person can always get back to.
     */
    private void show(String phrase) {
        AndroidUtilities.runOnUIThread(() -> {
            long self = UserConfig.getInstance(currentAccount).getClientUserId();
            // Word for word what iOS writes. The scenarios read this back with
            // a pattern, and a phrase worded differently on one client is a
            // phrase the tests stop finding on that one.
            String text = "Recovery phrase:\n\n" + phrase + "\n\nWrite it down on paper"
                    + " and keep it. It is the only way back into this account if the"
                    + " phone is lost, it works once, and nobody - including this"
                    + " service - can give it to you again.";
            SendMessagesHelper.getInstance(currentAccount).sendMessage(
                    SendMessagesHelper.SendMessageParams.of(text, self));
        });
    }
}

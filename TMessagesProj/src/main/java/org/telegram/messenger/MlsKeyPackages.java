package org.telegram.messenger;

import android.content.SharedPreferences;
import android.util.Base64;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPCMls;

import java.util.Random;

/**
 * Keeps this device reachable for encrypted conversations.
 *
 * A key package is what somebody else needs in order to start an encrypted
 * conversation with this device while it is asleep. They are used once, so the
 * supply runs down and has to be refilled; the server counts what is left and
 * says when, because it is the only one that can count and this device is the
 * only one that can make them.
 *
 * Nothing here is visible to a person. It is all preparation: without it,
 * somebody starting an encrypted conversation with this device would find
 * nothing to encrypt to.
 */
public class MlsKeyPackages {

    private static final int PACKAGES_PER_REFILL = 30;

    /** One per account, because the identity is the account's. */
    private static final MlsKeyPackages[] instances = new MlsKeyPackages[UserConfig.MAX_ACCOUNT_COUNT];

    private final int currentAccount;
    private boolean publishing;

    public static MlsKeyPackages getInstance(int account) {
        synchronized (MlsKeyPackages.class) {
            if (instances[account] == null) {
                instances[account] = new MlsKeyPackages(account);
            }
            return instances[account];
        }
    }

    private MlsKeyPackages(int currentAccount) {
        this.currentAccount = currentAccount;
    }

    /**
     * Where this device's encryption state lives: its key, the conversations it
     * is in, and where each ratchet had got to.
     *
     * In the account's own preferences, which is where the client already keeps
     * what it cannot afford to lose. Losing this makes every conversation on
     * this device unreadable for good, so it is written back after anything
     * that moves a ratchet rather than at some convenient later moment.
     */
    private SharedPreferences storage() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences("mls" + currentAccount, android.content.Context.MODE_PRIVATE);
    }

    /** This device's identity, read from storage or made if there is none yet. */
    public MlsCore.Identity identity() throws MlsCore.MlsException {
        String saved = storage().getString("state", null);
        if (saved != null) {
            try {
                return MlsCore.Identity.open(Base64.decode(saved, Base64.NO_WRAP));
            } catch (MlsCore.MlsException e) {
                // A state that cannot be opened is not a state. Starting over
                // loses the conversations it held, which is bad - and carrying
                // on with a half-read one is worse, because it looks like it
                // works.
                FileLog.e("mls: the stored state does not open, starting over: " + e.getMessage());
            }
        }

        // A name that says which device this is. The account alone would name
        // the person, and then two phones would look like one member.
        String name = UserConfig.getInstance(currentAccount).getClientUserId()
                + "/" + new Random().nextInt(Integer.MAX_VALUE);
        MlsCore.Identity identity = new MlsCore.Identity(name.getBytes());
        save(identity);
        FileLog.d("mls: a device identity was made for this account");
        return identity;
    }

    public void save(MlsCore.Identity identity) throws MlsCore.MlsException {
        storage().edit()
                .putString("state", Base64.encodeToString(identity.export(), Base64.NO_WRAP))
                .apply();
    }

    /**
     * Leaves a supply of key packages with the server. Called at start and
     * whenever the server says the supply is low.
     */
    public void publish() {
        synchronized (this) {
            if (publishing) {
                return;
            }
            publishing = true;
        }

        // It asks first. Publishing thirty every few minutes whatever the
        // answer said filled the hundred a device may hold within the hour,
        // and every publish after that was refused with a FLOOD_WAIT that
        // burned in the server's log for days. An empty publish is the
        // question "how many are left"; packages are made only when the
        // answer says to - the shape the other client already took, and the
        // server holds it with a test of its own.
        TLRPCMls.TL_mls_publishKeyPackages ask = new TLRPCMls.TL_mls_publishKeyPackages();
        ConnectionsManager.getInstance(currentAccount).sendRequest(ask, (response, error) -> {
            if (error != null || !(response instanceof TLRPCMls.TL_mls_publishResult)) {
                synchronized (MlsKeyPackages.this) {
                    publishing = false;
                }
                if (error != null) {
                    FileLog.e("mls: cannot ask how many key packages are left: " + error.text);
                }
                return;
            }
            int available = ((TLRPCMls.TL_mls_publishResult) response).available;
            if (available >= PACKAGES_PER_REFILL) {
                synchronized (MlsKeyPackages.this) {
                    publishing = false;
                }
                FileLog.d("mls: " + available + " key packages left, none made");
                return;
            }
            refill();
        });
    }

    /** Makes a fresh supply and hands it over. Only reached when the server
     *  said the shelf is running low. */
    private void refill() {
        Utilities.globalQueue.postRunnable(() -> {
            try (MlsCore.Identity identity = identity()) {
                TLRPCMls.TL_mls_publishKeyPackages request = new TLRPCMls.TL_mls_publishKeyPackages();
                for (int i = 0; i < PACKAGES_PER_REFILL; i++) {
                    request.key_packages.add(identity.keyPackage());
                }
                // One handed out repeatedly once the others run out, so a
                // conversation can still start with a device that has been quiet.
                request.last_resort = identity.keyPackage();

                // Saved before publishing, not after: a package published but
                // not saved is one this device cannot answer for, and the
                // conversation would fail to start with no explanation on
                // either side.
                save(identity);

                ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) -> {
                    synchronized (MlsKeyPackages.this) {
                        publishing = false;
                    }
                    if (error != null) {
                        FileLog.e("mls: the server did not take the key packages: " + error.text);
                        return;
                    }
                    if (response instanceof TLRPCMls.TL_mls_publishResult) {
                        TLRPCMls.TL_mls_publishResult result = (TLRPCMls.TL_mls_publishResult) response;
                        FileLog.d("mls: published " + result.added + ", " + result.available + " available");
                    }
                });
            } catch (MlsCore.MlsException e) {
                synchronized (this) {
                    publishing = false;
                }
                FileLog.e("mls: cannot make key packages: " + e.getMessage());
            }
        });
    }
}

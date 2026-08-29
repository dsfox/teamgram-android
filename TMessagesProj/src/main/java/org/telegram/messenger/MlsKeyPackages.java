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
    /**
     * What every read-modify-write of this device's state must hold.
     *
     * The state is one blob. Every operation opens the whole of it, changes a
     * ratchet somewhere inside, and writes the whole of it back - so two at
     * once means the second write silently throws away what the first did.
     *
     * It is not theoretical. Membership work runs on the shared queue and is
     * serialised with itself; encrypting and decrypting run on the threads of
     * the message pipeline and are not serialised with anything. An incoming
     * message being opened while commits are being applied is two threads in
     * the same blob (#112).
     *
     * What is lost surfaces nowhere near where it happened: a sender does not
     * read their own message, so a dropped ratchet advance turns up later as a
     * message that will not open on the other side.
     *
     * The lock is never held across a network call - every path closes the
     * state before it asks the server anything - so this costs only the
     * milliseconds the cryptography itself takes.
     */
    public Object stateLock() {
        return this.stateLock;
    }

    private final Object stateLock = new Object();

    public MlsCore.Identity identity() throws MlsCore.MlsException {
        long self = UserConfig.getInstance(currentAccount).getClientUserId();
        String mine = self + "/";
        String saved = storage().getString("state", null);
        if (saved != null) {
            try {
                MlsCore.Identity identity = MlsCore.Identity.open(Base64.decode(saved, Base64.NO_WRAP));
                byte[] name = identity.name();
                if (self != 0 && name != null && !new String(name).startsWith(mine)) {
                    // Built before this account had signed in, so its leaf is
                    // named after nobody: the id was still zero and the name
                    // came out `0/1234`. Nothing recognises such a leaf as
                    // belonging to its owner - not the pass that lets this
                    // account's other phones in, not the one that takes a lost
                    // phone out, not the comparison with the chat - so the
                    // device sits in every conversation as a member no person
                    // owns.
                    //
                    // Seen on the stand: a second phone signed in, was let into
                    // the group as `0/955551846`, and its owner's other phone
                    // then reported one leaf of its own where there were two.
                    // Started over rather than carried: a state named for the
                    // wrong person can never be repaired into the right one.
                    FileLog.e("mls: the stored state is named " + new String(name)
                            + " and this account is " + self + ", starting over");
                    identity.close();
                } else {
                    return identity;
                }
            } catch (MlsCore.MlsException e) {
                // A state that cannot be opened is not a state. Starting over
                // loses the conversations it held, which is bad - and carrying
                // on with a half-read one is worse, because it looks like it
                // works.
                FileLog.e("mls: the stored state does not open, starting over: " + e.getMessage());
            }
        }

        if (self == 0) {
            // No account yet. Making the identity now would name it after
            // nobody and keep that name for the life of the install.
            throw new MlsCore.MlsException("there is no account on this device yet");
        }

        // A name that says which device this is. The account alone would name
        // the person, and then two phones would look like one member.
        String name = mine + new Random().nextInt(Integer.MAX_VALUE);
        MlsCore.Identity identity = new MlsCore.Identity(name.getBytes());
        save(identity);
        FileLog.d("mls: a device identity was made for " + name);
        return identity;
    }

    /**
     * How many devices of this account have published anything, as the server
     * last said.
     *
     * The one thing that tells this phone another phone of the same person has
     * signed in. Comparing a conversation with its chat is about people, so a
     * second device of somebody already in it is invisible there - it was
     * exactly this that left a phone signed in beside another one reading
     * nothing but padlocks (#41).
     *
     * Zero means nobody has asked yet, and nothing is concluded from it.
     */
    private volatile int devices = 0;

    public int devices() {
        return devices;
    }

    private void noteDevices(TLRPCMls.TL_mls_publishResult result) {
        boolean grew = result.devices > devices;
        boolean shrank = devices > 0 && result.devices < devices;
        FileLog.d("mls: the server says this account has " + result.devices + " device(s)");
        devices = result.devices;
        if (grew) {
            FileLog.d("mls: this account now has " + result.devices + " device(s)");
            // At once, rather than at whatever happens next. The count going up
            // is a phone that has just signed in, and the person holding the
            // old one is watching the new one show padlocks.
            MlsRuntime.getInstance(currentAccount).letInMyOtherDevicesEverywhere();
        }
        if (shrank) {
            FileLog.d("mls: a device of this account is gone");
        }
        // Every time the count is known, and not only when it has just fallen.
        //
        // The trend lives in memory: a phone restarted after the other one was
        // signed out starts from nothing, reads "one device" as a rise, and
        // never looks. It was measured that way - the leaf stayed and the epoch
        // did not move. The pass itself needs no trend, because it compares
        // leaves against the count, so it is asked every time and costs nothing
        // when there is nothing to do (#41).
        MlsRuntime.getInstance(currentAccount).takeOutMyLostDevicesEverywhere();
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
            noteDevices((TLRPCMls.TL_mls_publishResult) response);
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
                        noteDevices(result);
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

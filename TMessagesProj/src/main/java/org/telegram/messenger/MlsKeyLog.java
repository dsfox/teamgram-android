package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.ServerAddress;
import org.telegram.tgnet.TLRPCMls;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * A claimed key package is added only when the key log holds its key (#44).
 *
 * A key package is signed by its device's own key, but which key belongs to
 * which person a phone could only take on the server's word: a server could
 * hand out a package built on a key of its own under a real device's name and
 * join the conversation. The server writes every key it hands out into an
 * append-only log (RFC 9162's tree) and signs the log's head; this asks for the
 * person's entries before their packages are added, and adds only a package
 * whose (name, key) is an entry with a valid inclusion proof under a head
 * signed by the log key this phone pinned, and consistent with the newest head
 * it kept. A key the server logged to listen in stays in the log for good,
 * where the owner's own phones see it.
 *
 * Required only from a server that has shown its log once: a server of one's
 * own on an older image, and ours before the log, answer as they always did.
 * The pin and the newest head are kept per server, not per account.
 *
 * The twin of MlsKeyLog.swift.
 */
public final class MlsKeyLog {

    private static final MlsKeyLog[] instances = new MlsKeyLog[UserConfig.MAX_ACCOUNT_COUNT];

    private final int currentAccount;

    public static MlsKeyLog getInstance(int account) {
        synchronized (MlsKeyLog.class) {
            if (instances[account] == null) {
                instances[account] = new MlsKeyLog(account);
            }
            return instances[account];
        }
    }

    private MlsKeyLog(int account) {
        this.currentAccount = account;
    }

    private static SharedPreferences storage() {
        return ApplicationLoader.applicationContext.getSharedPreferences("mls_key_log", Context.MODE_PRIVATE);
    }

    private static String server() {
        return ServerAddress.host() + ":" + ServerAddress.port();
    }

    /**
     * mls.claimKeyPackages, with the answer cut down to the packages the key
     * log vouches for. Called where the request was sent, with the same
     * delegate.
     */
    public void claim(TLRPCMls.TL_mls_claimKeyPackages request, RequestDelegate delegate) {
        final long userId = request.user_id;
        ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) -> {
            if (error != null || !(response instanceof TLRPCMls.TL_mls_keyPackages)
                    || ((TLRPCMls.TL_mls_keyPackages) response).packages.isEmpty()) {
                delegate.run(response, error);
                return;
            }
            TLRPCMls.TL_mls_keyPackages claimed = (TLRPCMls.TL_mls_keyPackages) response;
            vet(userId, claimed.packages, vetted -> {
                TLRPCMls.TL_mls_keyPackages answer = new TLRPCMls.TL_mls_keyPackages();
                answer.packages = vetted;
                delegate.run(answer, null);
            });
        });
    }

    private void vet(long userId, ArrayList<byte[]> packages, Utilities.Callback<ArrayList<byte[]>> then) {
        final String server = server();
        final SharedPreferences kept = storage();
        final byte[] pinned = read(kept, server + ".key");
        final long keptSize = kept.getLong(server + ".size", 0);
        final byte[] keptRoot = read(kept, server + ".root");

        TLRPCMls.TL_mls_getKeyLog request = new TLRPCMls.TL_mls_getKeyLog();
        request.user_id = userId;
        request.known_size = pinned != null ? keptSize : 0;
        ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) -> {
            if (error != null || !(response instanceof TLRPCMls.TL_mls_keyLog)) {
                if (pinned == null) {
                    // A server that has never shown a log: as before the log.
                    then.run(packages);
                } else {
                    refuse(userId, packages, "the key log did not answer" + (error != null ? ": " + error.text : ""));
                    then.run(new ArrayList<>());
                }
                return;
            }
            TLRPCMls.TL_mls_keyLog log = (TLRPCMls.TL_mls_keyLog) response;
            TLRPCMls.TL_mls_keyLogHead head = log.head;
            String wrong = untrusted(log, pinned, keptSize, keptRoot);
            if (wrong != null) {
                refuse(userId, packages, wrong);
                then.run(new ArrayList<>());
                return;
            }
            keep(kept, server, head, pinned, keptSize);

            ArrayList<byte[]> vouched = new ArrayList<>();
            for (byte[] keyPackage : packages) {
                byte[] name = MlsCore.keyPackageName(keyPackage);
                byte[] key = MlsCore.keyPackageSignatureKey(keyPackage);
                boolean proved = false;
                if (name != null && key != null) {
                    for (TLRPCMls.TL_mls_keyLogEntry entry : log.entries) {
                        if (Arrays.equals(entry.name, name) && Arrays.equals(entry.signature_key, key)
                                && MlsCore.keyLogProvesInclusion(userId, name, key, entry.index, head.size,
                                joined(entry.proof), head.root)) {
                            proved = true;
                            break;
                        }
                    }
                }
                if (proved) {
                    vouched.add(keyPackage);
                } else {
                    FileLog.e("mls: a package of " + userId + " for "
                            + (name != null ? new String(name, java.nio.charset.StandardCharsets.UTF_8) : "?")
                            + " is not in the key log, so it is not added");
                }
            }
            then.run(vouched);
        });
    }

    /** What is wrong with the log's head against what this phone kept, or null. */
    private static String untrusted(TLRPCMls.TL_mls_keyLog log, byte[] pinned, long keptSize, byte[] keptRoot) {
        TLRPCMls.TL_mls_keyLogHead head = log.head;
        if (head == null || !MlsCore.keyLogHeadIsSigned(head.size, head.root, head.date, head.log_key, head.signature)) {
            return "the key log's head is not signed by its own key";
        } else if (pinned != null && !Arrays.equals(pinned, head.log_key)) {
            return "the key log is signed by another key than the one this phone pinned";
        } else if (pinned != null && head.size < keptSize) {
            return "the key log holds " + head.size + " keys, and this phone saw " + keptSize;
        } else if (pinned != null && head.size == keptSize && keptRoot != null && !Arrays.equals(keptRoot, head.root)) {
            return "the key log of " + head.size + " keys is not the one this phone saw";
        } else if (pinned != null && head.size > keptSize && keptSize > 0 && keptRoot != null
                && !MlsCore.keyLogProvesConsistency(keptSize, head.size, keptRoot, head.root, joined(log.consistency))) {
            return "the key log of " + head.size + " keys does not hold the one of " + keptSize + " this phone saw";
        }
        return null;
    }

    /** The pin on first sight, and the newest head after. */
    private static void keep(SharedPreferences kept, String server, TLRPCMls.TL_mls_keyLogHead head, byte[] pinned, long keptSize) {
        if (pinned == null) {
            FileLog.d("mls: the key log of " + server + " is pinned now, at " + head.size + " keys");
        }
        if (pinned == null || head.size > keptSize) {
            kept.edit()
                    .putString(server + ".key", Base64.encodeToString(head.log_key, Base64.NO_WRAP))
                    .putLong(server + ".size", head.size)
                    .putString(server + ".root", Base64.encodeToString(head.root, Base64.NO_WRAP))
                    .apply();
        }
    }

    /**
     * Part 4: this account's own entries, read now and then. A key in the log
     * that none of the account's phones made is how a device the server put
     * in shows itself, so a key this phone has not seen before is told to the
     * person. On the first look the keys already there are taken as the
     * account's own (a phone just signed in cannot tell its siblings from
     * anything else) - except a key under this very device's name that is not
     * this device's, which is never anybody's but the server's.
     */
    public void watchOwn() {
        final long self = UserConfig.getInstance(currentAccount).getClientUserId();
        if (self == 0) {
            return;
        }
        final byte[] myName;
        final byte[] myKey;
        synchronized (MlsKeyPackages.getInstance(currentAccount).stateLock()) {
            try (MlsCore.Identity identity = MlsKeyPackages.getInstance(currentAccount).identity()) {
                myName = identity.name();
                myKey = identity.signatureKey();
            } catch (MlsCore.MlsException e) {
                return;
            }
        }
        if (myName == null || myKey == null) {
            return;
        }
        final String server = server();
        final SharedPreferences kept = storage();
        final byte[] pinned = read(kept, server + ".key");
        final long keptSize = kept.getLong(server + ".size", 0);
        final byte[] keptRoot = read(kept, server + ".root");
        final String seenKey = server + ".own." + self;

        TLRPCMls.TL_mls_getKeyLog request = new TLRPCMls.TL_mls_getKeyLog();
        request.user_id = self;
        request.known_size = pinned != null ? keptSize : 0;
        ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) -> {
            if (error != null || !(response instanceof TLRPCMls.TL_mls_keyLog)) {
                return;
            }
            TLRPCMls.TL_mls_keyLog log = (TLRPCMls.TL_mls_keyLog) response;
            String wrong = untrusted(log, pinned, keptSize, keptRoot);
            if (wrong != null) {
                FileLog.e("mls: " + wrong + "; this account's keys are not read from it");
                return;
            }
            TLRPCMls.TL_mls_keyLogHead head = log.head;
            keep(kept, server, head, pinned, keptSize);

            java.util.Set<String> before = kept.getStringSet(seenKey, null);
            boolean first = before == null;
            java.util.HashSet<String> seen = new java.util.HashSet<>(first ? java.util.Collections.emptySet() : before);
            ArrayList<String> appeared = new ArrayList<>();
            for (TLRPCMls.TL_mls_keyLogEntry entry : log.entries) {
                if (!MlsCore.keyLogProvesInclusion(self, entry.name, entry.signature_key, entry.index, head.size,
                        joined(entry.proof), head.root)) {
                    FileLog.e("mls: entry " + entry.index + " of this account does not prove itself in the key log");
                    continue;
                }
                String id = Base64.encodeToString(entry.name, Base64.NO_WRAP) + ":"
                        + Base64.encodeToString(entry.signature_key, Base64.NO_WRAP);
                boolean named = Arrays.equals(entry.name, myName);
                if (named && Arrays.equals(entry.signature_key, myKey)) {
                    seen.add(id);
                    continue;
                }
                if (!seen.add(id)) {
                    continue;
                }
                if (first && !named) {
                    continue;
                }
                appeared.add(new String(entry.name, java.nio.charset.StandardCharsets.UTF_8));
            }
            kept.edit().putStringSet(seenKey, seen).apply();
            FileLog.d("mls: watching " + seen.size() + " key(s) of this account in the key log of " + head.size);
            if (!appeared.isEmpty()) {
                FileLog.e("mls: a key of a new device appeared in this account: " + TextUtils.join(", ", appeared));
                AndroidUtilities.runOnUIThread(org.telegram.ui.Ice9NewDeviceKey::tell);
            }
        });
    }

    private static void refuse(long userId, ArrayList<byte[]> packages, String why) {
        FileLog.e("mls: " + why + "; none of the " + packages.size() + " package(s) of " + userId + " is added");
    }

    private static byte[] read(SharedPreferences kept, String key) {
        String value = kept.getString(key, null);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Base64.decode(value, Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] joined(ArrayList<byte[]> hashes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (hashes != null) {
            for (byte[] h : hashes) {
                out.write(h, 0, h.length);
            }
        }
        return out.toByteArray();
    }
}

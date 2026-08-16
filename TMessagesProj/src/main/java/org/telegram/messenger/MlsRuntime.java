package org.telegram.messenger;

import android.content.SharedPreferences;
import android.util.Base64;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.InputSerializedData;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.TLRPCMls;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * End-to-end encryption as this client uses it: which conversation is which,
 * what to encrypt with, and what to do with what arrives.
 *
 * The counterpart of MlsRuntime.swift on iOS, and deliberately the same shape,
 * because the two have to agree about things no test on one side can check
 * alone - which group a message belongs to, what the plaintext looks like
 * inside, and what happens when a phone is set up again.
 *
 * The cryptography is not here. It is in the Rust core both clients link, and
 * this only decides when to call it.
 */
public class MlsRuntime {

    /** What marks a message as ours. Plain text on purpose: somebody looking at
     *  one of these in a database, or in an older client, should be able to tell
     *  what it is instead of seeing mojibake. */
    private static final String CIPHERTEXT_PREFIX = "mls1:";

    private static final MlsRuntime[] instances = new MlsRuntime[UserConfig.MAX_ACCOUNT_COUNT];

    private final int currentAccount;

    /** Which MLS group belongs to which peer. Not a secret - a note about which
     *  conversation is which - so it sits beside the account rather than inside
     *  the crypto state. */
    private final Map<Long, byte[]> groupIdByPeer = new HashMap<>();

    private boolean loaded;
    private boolean collectingWelcomes;

    public static MlsRuntime getInstance(int account) {
        synchronized (MlsRuntime.class) {
            if (instances[account] == null) {
                instances[account] = new MlsRuntime(account);
            }
            return instances[account];
        }
    }

    private MlsRuntime(int currentAccount) {
        this.currentAccount = currentAccount;
    }

    private SharedPreferences storage() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences("mls" + currentAccount, android.content.Context.MODE_PRIVATE);
    }

    // ----------------------------------------------------------------------
    // Which conversation is which
    // ----------------------------------------------------------------------

    /**
     * Two parallel lists of the simplest types there are.
     *
     * A map written straight out would be neater and is not worth it: this has
     * to be read by a client that may be older than the writer, and two lists of
     * strings are a shape nothing can misread.
     */
    private synchronized void loadConversations() {
        if (loaded) {
            return;
        }
        loaded = true;
        String peers = storage().getString("peers", "");
        String groups = storage().getString("groups", "");
        if (peers.isEmpty() || groups.isEmpty()) {
            return;
        }
        String[] peerList = peers.split(",");
        String[] groupList = groups.split(",");
        for (int i = 0; i < peerList.length && i < groupList.length; i++) {
            try {
                groupIdByPeer.put(Long.parseLong(peerList[i]),
                        Base64.decode(groupList[i], Base64.NO_WRAP));
            } catch (Exception ignored) {
                // One unreadable row is not a reason to lose the others.
            }
        }
    }

    private synchronized void saveConversations() {
        StringBuilder peers = new StringBuilder();
        StringBuilder groups = new StringBuilder();
        for (Map.Entry<Long, byte[]> each : groupIdByPeer.entrySet()) {
            if (peers.length() > 0) {
                peers.append(',');
                groups.append(',');
            }
            peers.append(each.getKey());
            groups.append(Base64.encodeToString(each.getValue(), Base64.NO_WRAP));
        }
        storage().edit()
                .putString("peers", peers.toString())
                .putString("groups", groups.toString())
                .apply();
    }

    private synchronized void remember(long peerId, byte[] groupId) {
        loadConversations();
        groupIdByPeer.put(peerId, groupId);
        saveConversations();
        FileLog.d("mls: conversation " + shortId(groupId) + " belongs to " + peerId);
    }

    /** Short enough to read in a log and long enough to tell two apart. Every
     *  question worth asking about encryption is which conversation something
     *  happened in, and none of it is in the log without this. */
    public static String shortId(byte[] groupId) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(6, groupId.length); i++) {
            out.append(String.format("%02x", groupId[i]));
        }
        return out.toString();
    }

    // ----------------------------------------------------------------------
    // Reading what arrives
    // ----------------------------------------------------------------------

    public static boolean isCiphertext(String text) {
        return text != null && text.startsWith(CIPHERTEXT_PREFIX);
    }

    /** What came of trying to read a message. Not readable is three different
     *  things and telling them apart decides what happens next. */
    public enum Reading {
        CONTENT,
        /** A handshake message, or nothing to show. */
        NOTHING,
        /** Written on this device. MLS never lets a sender read their own
         *  ciphertext, so this is the design and not a fault - and a client that
         *  reads it as a broken conversation throws the conversation away and
         *  builds another after every message it sends. */
        WRITTEN_HERE,
        UNREADABLE
    }

    public static final class Opened {
        public final Reading reading;
        public final String text;
        public final ArrayList<TLRPC.MessageEntity> entities;

        Opened(Reading reading, String text, ArrayList<TLRPC.MessageEntity> entities) {
            this.reading = reading;
            this.text = text;
            this.entities = entities;
        }

        static Opened of(Reading reading) {
            return new Opened(reading, null, null);
        }
    }

    /**
     * Turns a message back, or says why it could not.
     *
     * Opened with the conversation the message names rather than the one this
     * device keeps for whoever sent it, because those are not always the same.
     * Every reinstall makes them differ: the phone set up again has lost every
     * group it was in and starts a new one, while the other side goes on sending
     * in the old one until the welcome arrives. Picking by person opens neither.
     */
    public Opened read(String text) {
        if (!isCiphertext(text)) {
            return Opened.of(Reading.NOTHING);
        }
        byte[] ciphertext;
        try {
            ciphertext = Base64.decode(text.substring(CIPHERTEXT_PREFIX.length()), Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            FileLog.e("mls: a message marked as ours is not readable base64");
            return Opened.of(Reading.UNREADABLE);
        }

        byte[] groupId = MlsCore.messageGroupId(ciphertext);
        if (groupId == null) {
            return Opened.of(Reading.UNREADABLE);
        }

        try (MlsCore.Identity identity = MlsKeyPackages.getInstance(currentAccount).identity()) {
            MlsCore.Group group = MlsCore.Group.load(identity, groupId);
            if (group == null) {
                // Not a conversation this device is in. That is ordinary while a
                // welcome is still on its way, and it is also what a message
                // this device wrote looks like from here.
                return Opened.of(Reading.UNREADABLE);
            }
            try {
                byte[] plaintext = group.decrypt(identity, ciphertext);
                // Saved after anything that moves a ratchet, not at some
                // convenient later moment: the app can be killed at any point
                // and what is lost is the ability to read.
                MlsKeyPackages.getInstance(currentAccount).save(identity);
                if (plaintext == null) {
                    // A commit or a proposal. It moved the conversation on and
                    // there is nothing to show.
                    return Opened.of(Reading.NOTHING);
                }
                return decode(plaintext);
            } finally {
                group.close();
            }
        } catch (MlsCore.MlsException e) {
            FileLog.e("mls: cannot read a message in " + shortId(groupId) + ": " + e.getMessage());
            return Opened.of(Reading.UNREADABLE);
        }
    }

    /** What a message that cannot be opened yet shows instead of its ciphertext.
     *  A lock rather than mls1:AAEAAh..., because the second is in the chat, the
     *  chat list and the search results, and it means nothing to anybody. */
    public static final String LOCKED = "🔒";

    /**
     * Opens a message where it stands, before anything stores or shows it.
     *
     * Done this early, and by rewriting the text in place, because the text is
     * read by more than the chat: the chat list shows the last one, search
     * indexes it, a notification quotes it. Decrypting at each of those would be
     * four chances to miss one.
     *
     * A message that cannot be opened yet is usually one that overtook the
     * welcome letting this device into the conversation - the two travel by
     * different routes. It gets the lock, and the ciphertext stays in the
     * message so a later pass can read it once the welcome has arrived.
     */
    public boolean open(TLRPC.Message message) {
        if (message == null || !isCiphertext(message.message)) {
            return false;
        }
        Opened opened = read(message.message);
        if (opened.reading == Reading.CONTENT) {
            message.message = opened.text;
            if (opened.entities != null && !opened.entities.isEmpty()) {
                message.entities = opened.entities;
                message.flags |= 128;
            }
            return true;
        }
        // Not readable here, and not readable *yet*: usually the message
        // overtook the welcome that lets this device into the conversation,
        // because the two travel by different routes.
        //
        // So the ciphertext stays exactly where it is. Writing the lock over it
        // would be the last time anybody could read the message - the stored
        // text is all there is, and a pass after the welcome arrives would find
        // a lock and nothing to open. The lock belongs where a message is drawn,
        // over a ciphertext that is still underneath it.
        return false;
    }

    /**
     * The plaintext is not the text. It is a TL object holding the text and its
     * formatting, because an entity is a pair of offsets into the text and next
     * to a ciphertext those point at nothing.
     */
    private Opened decode(byte[] plaintext) {
        InputSerializedData stream = new SerializedData(plaintext);
        int constructor = stream.readInt32(false);
        if (constructor == TLRPCMls.TL_mls_content.constructor) {
            TLRPCMls.TL_mls_content content =
                    TLRPCMls.TL_mls_content.TLdeserialize(stream, constructor, false);
            if (content != null) {
                return new Opened(Reading.CONTENT, content.text, content.entities);
            }
        }
        FileLog.e("mls: a message opened into something this client does not know");
        return Opened.of(Reading.UNREADABLE);
    }

    // ----------------------------------------------------------------------
    // Writing
    // ----------------------------------------------------------------------

    /** Peers this device asked about and found no device for, and when. Asked
     *  again after a while rather than before every message. */
    private final Map<Long, Long> withoutDevices = new HashMap<>();

    private final java.util.Set<Long> starting = new java.util.HashSet<>();

    /**
     * Whether it makes any sense to encrypt to this peer at all.
     *
     * Only conversations between two people. A group or a channel has no device
     * to encrypt to, so every attempt would cost a round trip and end in the
     * clear anyway - once per message, for ever, because nothing is remembered.
     *
     * Saved Messages is out for a harder reason: a conversation with oneself
     * would be one where every message is written by the only person who cannot
     * read it back, and the notes would go in unreadable.
     */
    private boolean worthEncrypting(long peerId) {
        if (peerId <= 0 || peerId == UserConfig.getInstance(currentAccount).getClientUserId()) {
            return false;
        }
        Long asked = withoutDevices.get(peerId);
        return asked == null || System.currentTimeMillis() - asked > 600_000L;
    }

    /**
     * Turns a message into what travels, or null when this conversation cannot
     * carry it - and then the caller sends as it always did, in the clear.
     *
     * Sending in the clear rather than refusing to send is the whole shape of
     * this: a messenger that will not send is worse than one that sometimes
     * cannot protect, and it means this can ship without betting the product on
     * it.
     *
     * The formatting goes inside rather than beside it. An entity is a pair of
     * offsets into the text, and next to a ciphertext they point at nothing.
     */
    public String encrypt(long peerId, String text, ArrayList<TLRPC.MessageEntity> entities) {
        if (text == null || text.isEmpty() || !worthEncrypting(peerId)) {
            return null;
        }
        loadConversations();
        byte[] groupId;
        synchronized (this) {
            groupId = groupIdByPeer.get(peerId);
        }
        if (groupId == null) {
            // Nothing yet. Started now so the next message is protected; this
            // one goes as it always did.
            ensureConversation(peerId);
            return null;
        }

        try (MlsCore.Identity identity = MlsKeyPackages.getInstance(currentAccount).identity()) {
            MlsCore.Group group = MlsCore.Group.load(identity, groupId);
            if (group == null) {
                return null;
            }
            try {
                TLRPCMls.TL_mls_content content = new TLRPCMls.TL_mls_content();
                content.text = text;
                if (entities != null) {
                    content.entities.addAll(entities);
                }
                SerializedData out = new SerializedData();
                content.serializeToStream(out);

                byte[] ciphertext = group.encrypt(identity, out.toByteArray());
                // The ratchet has moved, so it is written back at once. Saving
                // late is the same as not saving: the app can be killed at any
                // moment, and what is lost is the ability to read.
                MlsKeyPackages.getInstance(currentAccount).save(identity);
                return CIPHERTEXT_PREFIX + Base64.encodeToString(ciphertext, Base64.NO_WRAP);
            } finally {
                group.close();
            }
        } catch (MlsCore.MlsException e) {
            FileLog.e("mls: cannot encrypt to " + peerId + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Builds a conversation with this peer if there is not one, so that the
     * messages after this one are protected.
     *
     * Every device of theirs at once: there is one welcome and it has to serve
     * all of them.
     */
    public void ensureConversation(long peerId) {
        if (!worthEncrypting(peerId)) {
            return;
        }
        loadConversations();
        synchronized (this) {
            if (groupIdByPeer.containsKey(peerId) || starting.contains(peerId)) {
                return;
            }
            starting.add(peerId);
        }

        TLRPCMls.TL_mls_claimKeyPackages request = new TLRPCMls.TL_mls_claimKeyPackages();
        request.user_id = peerId;
        ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) -> {
            if (error != null || !(response instanceof TLRPCMls.TL_mls_keyPackages)) {
                synchronized (MlsRuntime.this) {
                    starting.remove(peerId);
                }
                FileLog.e("mls: no key packages for " + peerId
                        + (error != null ? ": " + error.text : ""));
                return;
            }
            TLRPCMls.TL_mls_keyPackages claimed = (TLRPCMls.TL_mls_keyPackages) response;
            if (claimed.packages.isEmpty()) {
                // Not a failure: somebody whose client does not do this yet, or
                // a device that has not published. Remembered so the next
                // message does not pay for the same round trip.
                synchronized (MlsRuntime.this) {
                    starting.remove(peerId);
                    withoutDevices.put(peerId, System.currentTimeMillis());
                }
                return;
            }
            Utilities.globalQueue.postRunnable(() -> begin(peerId, claimed.packages));
        });
    }

    private void begin(long peerId, List<byte[]> keyPackages) {
        try (MlsCore.Identity identity = MlsKeyPackages.getInstance(currentAccount).identity()) {
            MlsCore.Group group = MlsCore.Group.create(identity);
            try {
                MlsCore.Invitation invitation = group.addMembers(identity, keyPackages);
                byte[] groupId = group.id();

                // Saved before the welcome is sent. A welcome delivered for a
                // conversation this device has forgotten is one the other side
                // can join and nobody can talk in.
                MlsKeyPackages.getInstance(currentAccount).save(identity);
                remember(peerId, groupId);

                TLRPCMls.TL_mls_sendWelcome send = new TLRPCMls.TL_mls_sendWelcome();
                send.user_id = peerId;
                send.welcome = invitation.welcome;
                ConnectionsManager.getInstance(currentAccount).sendRequest(send, (response, error) -> {
                    synchronized (MlsRuntime.this) {
                        starting.remove(peerId);
                    }
                    if (error != null) {
                        // The conversation exists here and they were never
                        // invited. Sending in the clear is right: a message they
                        // cannot read is worse than one the server can.
                        FileLog.e("mls: the welcome for " + peerId + " was not delivered");
                        return;
                    }
                    FileLog.d("mls: started conversation " + shortId(groupId) + " with " + peerId);
                });
            } finally {
                group.close();
            }
        } catch (MlsCore.MlsException e) {
            synchronized (this) {
                starting.remove(peerId);
            }
            FileLog.e("mls: cannot start a conversation with " + peerId + ": " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------------
    // The welcomes that let this device in
    // ----------------------------------------------------------------------

    /**
     * Collects the invitations waiting on the server and joins each one.
     *
     * Confirmed only after the join has been saved. A welcome confirmed and then
     * lost is a conversation the other side believes this device is in and it
     * can never read a word of.
     */
    public void collectWelcomes() {
        synchronized (this) {
            if (collectingWelcomes) {
                return;
            }
            collectingWelcomes = true;
        }

        TLRPCMls.TL_mls_getWelcomes request = new TLRPCMls.TL_mls_getWelcomes();
        ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) -> {
            synchronized (MlsRuntime.this) {
                collectingWelcomes = false;
            }
            if (error != null) {
                FileLog.e("mls: cannot ask for welcomes: " + error.text);
                return;
            }
            if (!(response instanceof TLRPCMls.TL_mls_welcomes)) {
                return;
            }
            TLRPCMls.TL_mls_welcomes welcomes = (TLRPCMls.TL_mls_welcomes) response;
            if (welcomes.welcomes.isEmpty()) {
                return;
            }
            Utilities.globalQueue.postRunnable(() -> join(welcomes));
        });
    }

    private void join(TLRPCMls.TL_mls_welcomes welcomes) {
        List<Long> joined = new ArrayList<>();
        try (MlsCore.Identity identity = MlsKeyPackages.getInstance(currentAccount).identity()) {
            for (TLRPCMls.TL_mls_welcome welcome : welcomes.welcomes) {
                try {
                    MlsCore.Group group = MlsCore.Group.join(identity, welcome.welcome);
                    byte[] groupId = group.id();
                    group.close();

                    // The state first, then the note about whose conversation it
                    // is, then the confirmation. Any other order leaves one of
                    // the three behind if the app dies between them.
                    MlsKeyPackages.getInstance(currentAccount).save(identity);
                    remember(welcome.from_id, groupId);
                    joined.add(welcome.id);
                    FileLog.d("mls: joined " + shortId(groupId) + " with " + welcome.from_id);
                } catch (MlsCore.MlsException e) {
                    // One invitation that cannot be joined must not stop the
                    // others: they are from different people.
                    FileLog.e("mls: cannot join an invitation from "
                            + welcome.from_id + ": " + e.getMessage());
                }
            }
        } catch (MlsCore.MlsException e) {
            FileLog.e("mls: no identity to join with: " + e.getMessage());
            return;
        }

        if (joined.isEmpty()) {
            return;
        }
        TLRPCMls.TL_mls_confirmWelcomes confirm = new TLRPCMls.TL_mls_confirmWelcomes();
        confirm.ids.addAll(joined);
        ConnectionsManager.getInstance(currentAccount).sendRequest(confirm, (response, error) -> {
            if (error != null) {
                // Not confirmed means they arrive again, which is harmless: a
                // welcome for a conversation already joined is refused by the
                // core and skipped.
                FileLog.e("mls: the welcomes were not confirmed: " + error.text);
            }
        });
    }
}

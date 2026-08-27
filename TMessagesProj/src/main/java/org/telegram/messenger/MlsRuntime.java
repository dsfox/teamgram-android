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

    /** What this device wrote, by the random id it wrote it under.
     *
     *  Small and short-lived: it holds a message only between sending it and the
     *  server's copy of it coming back, which is seconds. What is on disk is
     *  already the words, because the local copy was stored before the
     *  ciphertext existed. */
    private final Map<Long, String> wroteHere = new HashMap<>();

    /** And what the file in it was, for the same reason. */
    private final Map<Long, TLRPCMls.TL_mls_media> sentMedia = new HashMap<>();

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

    /**
     * Records which chat a group belongs to, the first time a message says so.
     *
     * Quiet when it already agrees, and it replaces rather than refuses when it
     * does not: a chat really can move to another group - the other side
     * rebuilds, starts a new one, and from then on that is where the chat is.
     */
    private void attach(TLRPC.Message message, String ciphertext) {
        long dialogId = MessageObject.getDialogId(message);
        if (dialogId == 0) {
            return;
        }
        byte[] groupId;
        try {
            // The group id lives in the message, ahead of the ciphertext, and
            // the message is base64 on the wire - so it has to be decoded
            // before the id can be read out of it.
            groupId = MlsCore.messageGroupId(
                    Base64.decode(ciphertext.substring(CIPHERTEXT_PREFIX.length()), Base64.NO_WRAP));
        } catch (IllegalArgumentException e) {
            return;
        }
        if (groupId == null) {
            return;
        }
        synchronized (this) {
            loadConversations();
            byte[] known = groupIdByPeer.get(dialogId);
            if (known != null && java.util.Arrays.equals(known, groupId)) {
                return;
            }
        }
        remember(dialogId, groupId);
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

    /** Who wrote a message first, for a forward. */
    public static final class Forwarded {
        public final long id;
        public final String name;
        public final int date;

        public Forwarded(long id, String name, int date) {
            this.id = id;
            this.name = name;
            this.date = date;
        }
    }

    public static final class Opened {
        public final Reading reading;
        public final String text;
        public final ArrayList<TLRPC.MessageEntity> entities;
        /** Who wrote it first, when this was forwarded. */
        public Forwarded forwarded;
        /** What the file is, when the message carries one. The server is
         *  holding it as a blob of noise and this is the only description of
         *  it that exists. */
        public TLRPCMls.TL_mls_media media;
        /** What came out of the ciphertext, before it was made sense of. Kept
         *  so the same message can be shown again without asking MLS, which
         *  would refuse: a message opens exactly once. */
        public byte[] plaintext;

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
                Opened opened = decode(plaintext);
                opened.plaintext = plaintext;
                return opened;
            } finally {
                group.close();
            }
        } catch (MlsCore.MlsException e) {
            FileLog.e("mls: cannot read a message in " + shortId(groupId) + ": " + e.getMessage());
            return Opened.of(Reading.UNREADABLE);
        }
    }

    // ----------------------------------------------------------------------
    // What has already been opened
    // ----------------------------------------------------------------------
    //
    // A message opens exactly once. The key moves on as it is used, and the
    // same ciphertext offered a second time is refused - so a second attempt
    // does not merely fail, it is the moment the message is lost: what is on
    // screen becomes a lock and there is nothing left to open.
    //
    // And the same ciphertext does arrive twice. The server's copy comes back
    // through the difference after a restart, through a history load, through
    // a chat being opened - all of them ordinary, none of them a fault. The
    // first message from an iPhone was lost exactly this way: opened at
    // 21:40:42, offered again at 21:41:23, and a lock stood where the words
    // had been.
    //
    // So what came out of the ciphertext is kept, and a message that arrives
    // again is answered from here rather than from MLS. The twin of wroteHere,
    // which does the same for messages written on this device - except that
    // this one has to survive being killed, because that is when it is needed.

    /** How many openings to remember. Bounded because it is a safety net for
     *  the window where the stored copy is still a ciphertext, not an archive:
     *  once the plaintext is in the database nothing asks here again. */
    private static final int REMEMBERED = 2000;

    private SharedPreferences openings() {
        return ApplicationLoader.applicationContext.getSharedPreferences(
                "mlsopened" + currentAccount, android.content.Context.MODE_PRIVATE);
    }

    /** Which conversation and which message, because an id alone is only
     *  unique within one. */
    private static String openingKey(TLRPC.Message message) {
        return MessageObject.getDialogId(message) + ":" + message.id;
    }

    private byte[] alreadyOpened(TLRPC.Message message) {
        String kept = openings().getString(openingKey(message), null);
        if (kept == null) {
            return null;
        }
        // date, then what came out of it. The date is only for deciding what to
        // forget first.
        int split = kept.indexOf(' ');
        try {
            return Base64.decode(split < 0 ? kept : kept.substring(split + 1), Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void rememberOpening(TLRPC.Message message, byte[] plaintext) {
        if (plaintext == null) {
            return;
        }
        SharedPreferences store = openings();
        store.edit()
                .putString(openingKey(message),
                        message.date + " " + Base64.encodeToString(plaintext, Base64.NO_WRAP))
                .apply();
        if (store.getAll().size() > REMEMBERED) {
            forgetOldestOpenings(store);
        }
    }

    /** Drops the oldest quarter, so this happens rarely rather than on every
     *  message once the limit is reached. */
    private void forgetOldestOpenings(SharedPreferences store) {
        java.util.List<Map.Entry<String, ?>> all =
                new ArrayList<>(store.getAll().entrySet());
        java.util.Collections.sort(all, (one, two) -> {
            return Integer.compare(dateOf(one.getValue()), dateOf(two.getValue()));
        });
        SharedPreferences.Editor editor = store.edit();
        for (int i = 0; i < all.size() / 4; i++) {
            editor.remove(all.get(i).getKey());
        }
        editor.apply();
    }

    private static int dateOf(Object value) {
        String kept = value instanceof String ? (String) value : "";
        int split = kept.indexOf(' ');
        try {
            return split < 0 ? 0 : Integer.parseInt(kept.substring(0, split));
        } catch (NumberFormatException e) {
            return 0;
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
        if (message == null) {
            return false;
        }
        // A message written on this device can never be opened here: MLS does
        // not let a sender read their own ciphertext, and that is the design
        // rather than a fault. What the sender has is better than the key - the
        // words themselves, from before they were encrypted.
        //
        // Without this the server's copy of our own message comes back and
        // replaces what we wrote with mls1:AAEAAh..., and a person watches
        // their own sentence turn into base64 a second after sending it.
        if (message.out && isCiphertext(message.message)) {
            String written;
            synchronized (this) {
                written = wroteHere.get(message.random_id);
            }
            if (written != null) {
                message.message = written;
                return true;
            }
            // Nothing on this device will ever open it: MLS does not let a
            // sender read their own ciphertext, so keeping it buys nothing.
            // A lock, and where there is a file the path to it is left alone -
            // attachPath is the only record of where that file is.
            if (message.media == null) {
                message.attachPath = message.message;
            }
            message.message = LOCKED;
            return false;
        }
        // Either the text is still the ciphertext, or it was put aside behind a
        // lock the last time this was tried.
        String carried = isCiphertext(message.message) ? message.message
                : (LOCKED.equals(message.message) && isCiphertext(message.attachPath)
                    ? message.attachPath : null);
        if (carried == null) {
            return false;
        }
        // Asked here first, and MLS only if this is genuinely new. The same
        // ciphertext arrives more than once through ordinary routes, and
        // handing it to MLS a second time is what destroys it.
        byte[] before = alreadyOpened(message);
        Opened opened = before != null ? decode(before) : read(carried);
        if (opened.reading == Reading.CONTENT) {
            if (before == null) {
                rememberOpening(message, opened.plaintext);
            }
            // Which conversation this group belongs to, learnt from a message
            // rather than from the welcome.
            //
            // A welcome says who sent it and nothing else, which is enough for
            // a conversation between two and wrong for a group: the joiner
            // recorded the group against the person who invited them, so their
            // own first message into the chat found no conversation and started
            // a second one for the same chat. Every message carries its group id
            // in the clear, so the first one that opens says which chat it is -
            // and that is the only place both facts are known at once (#40).
            attach(message, carried);
            message.message = opened.text;
            // A forward carries who wrote it first inside the ciphertext, since
            // the server copies by id and cannot copy something it cannot read.
            // Put back where the client draws it from.
            if (opened.forwarded != null) {
                TLRPC.TL_messageFwdHeader header = new TLRPC.TL_messageFwdHeader();
                header.from_id = MessagesController.getInstance(currentAccount)
                        .getPeer(opened.forwarded.id);
                header.from_name = opened.forwarded.name;
                header.date = opened.forwarded.date;
                header.flags |= 1;
                if (header.from_name != null && !header.from_name.isEmpty()) {
                    header.flags |= 32;
                }
                message.fwd_from = header;
                message.flags |= TLRPC.MESSAGE_FLAG_FWD;
            }
            if (opened.entities != null && !opened.entities.isEmpty()) {
                message.entities = opened.entities;
                message.flags |= 128;
            }
            // What arrived beside the message is a document full of noise. The
            // description of it - what it is, how big, and the key - was inside
            // the message, and this is where the two are put back together.
            if (opened.media != null) {
                MlsMedia.attach(message, opened.media);
            }
            // The ciphertext has done its work and cannot be used again: MLS
            // opens a message once.
            if (isCiphertext(message.attachPath)) {
                message.attachPath = "";
            }
            return true;
        }
        // Not readable here, and not readable *yet*: usually the message
        // overtook the welcome that lets this device into the conversation,
        // because the two travel by different routes.
        //
        // The ciphertext is put aside rather than thrown away - it has not been
        // opened, so its key is still good and a pass after the welcome arrives
        // will read it. What a person sees becomes a lock instead of
        // mls1:AAEAAh..., which is what the chat, the chat list, the search and
        // a reply quote were all showing.
        stash(message);
        return false;
    }

    /**
     * Puts the ciphertext behind a lock, when there is somewhere to put it.
     *
     * attachPath is where the client keeps things that never leave the device,
     * and it already carries the path to the file when the message has one. A
     * message with a file keeps its ciphertext in the caption instead: that is
     * a base64 caption until a later pass opens it, which is worse to look at
     * and better than overwriting the only path to the file itself.
     */
    private void stash(TLRPC.Message message) {
        if (!isCiphertext(message.message) || message.media != null) {
            return;
        }
        message.attachPath = message.message;
        message.message = LOCKED;
    }

    /**
     * The plaintext is not the text. It is a TL object holding the text and its
     * formatting, because an entity is a pair of offsets into the text and next
     * to a ciphertext those point at nothing.
     */
    private Opened decode(byte[] plaintext) {
        InputSerializedData stream = new SerializedData(plaintext);
        int constructor = stream.readInt32(false);
        // The shape everything is written in now, and the only one that can
        // carry a file. The two below it are older and still arrive, because
        // they are in people's chats.
        if (constructor == TLRPCMls.TL_mls_message.constructor) {
            TLRPCMls.TL_mls_message message =
                    TLRPCMls.TL_mls_message.TLdeserialize(stream, constructor, false);
            if (message != null) {
                Opened opened = new Opened(Reading.CONTENT, message.text, message.entities);
                if (message.forward != null) {
                    opened.forwarded = new Forwarded(
                            message.forward.from_id, message.forward.from_name, message.forward.date);
                }
                opened.media = message.media;
                return opened;
            }
        }
        if (constructor == TLRPCMls.TL_mls_content.constructor) {
            TLRPCMls.TL_mls_content content =
                    TLRPCMls.TL_mls_content.TLdeserialize(stream, constructor, false);
            if (content != null) {
                return new Opened(Reading.CONTENT, content.text, content.entities);
            }
        }
        if (constructor == TLRPCMls.TL_mls_forwarded.constructor) {
            TLRPCMls.TL_mls_forwarded forwarded =
                    TLRPCMls.TL_mls_forwarded.TLdeserialize(stream, constructor, false);
            if (forwarded != null) {
                Opened opened = new Opened(Reading.CONTENT, forwarded.text, forwarded.entities);
                opened.forwarded = new Forwarded(
                        forwarded.from_id, forwarded.from_name, forwarded.date);
                return opened;
            }
        }
        FileLog.e("mls: a message opened into something this client does not know");
        return Opened.of(Reading.UNREADABLE);
    }

    // ----------------------------------------------------------------------
    // Writing
    // ----------------------------------------------------------------------

    /** People this device asked about and found no device for, and when. Asked
     *  again after a while rather than before every message.
     *
     *  Keyed by whoever was asked about, which for a conversation between two
     *  is the peer and for a member of a group is that person. The two cannot
     *  collide: a group's peer id is negative. */
    private final Map<Long, Long> withoutDevices = new HashMap<>();

    private final java.util.Set<Long> starting = new java.util.HashSet<>();

    /** Sends held back until a conversation with that peer exists, by peer. */
    private final Map<Long, java.util.ArrayList<Waiter>> waitingForConversation = new HashMap<>();

    /**
     * How long a message may wait for a handshake before it goes anyway.
     *
     * Encryption must never be able to stop a message. A slow network, a server
     * that does not answer, anything - after this the message goes in the clear
     * rather than waiting behind something a person cannot see. The other client
     * gives it the same ten seconds, and the handshake it waits for has been
     * measured in milliseconds.
     */
    private static final long CONVERSATION_WAIT = 10_000L;

    /**
     * One held-back send. Fires once and once only: it has both a deadline of
     * its own and the conversation to wait for, and whichever arrives first
     * must not let the other send the message twice.
     */
    private static final class Waiter {
        private final Runnable then;
        private boolean fired;

        Waiter(Runnable then) {
            this.then = then;
        }

        void fire() {
            synchronized (this) {
                if (fired) {
                    return;
                }
                fired = true;
            }
            AndroidUtilities.runOnUIThread(then);
        }
    }

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
        if (peerId == 0 || peerId == UserConfig.getInstance(currentAccount).getClientUserId()) {
            return false;
        }
        // A group is an MLS group of n, which is what MLS was built for - the
        // protocol does not care whether a leaf belongs to a second person or
        // to a second phone of the first (#40).
        //
        // A channel is not: broadcasting is a different thing and none of it is
        // built (#16). A folder id is not a conversation at all.
        if (peerId < 0 && !DialogObject.isChatDialog(peerId)) {
            return false;
        }
        Long asked = withoutDevices.get(peerId);
        return asked == null || System.currentTimeMillis() - asked > 600_000L;
    }

    /**
     * Everybody who has to be able to read what is written here.
     *
     * One person for a conversation between two; every member but this account
     * for a group. Returns null when the membership is not known yet - the
     * caller then sends in the clear rather than encrypting to a list it is
     * guessing at, which would leave somebody out of their own conversation.
     */
    private List<Long> membersOf(long peerId) {
        if (peerId > 0) {
            return java.util.Collections.singletonList(peerId);
        }
        TLRPC.ChatFull full = MessagesController.getInstance(currentAccount).getChatFull(-peerId);
        if (full == null || full.participants == null || full.participants.participants.isEmpty()) {
            return null;
        }
        long self = UserConfig.getInstance(currentAccount).getClientUserId();
        java.util.ArrayList<Long> members = new java.util.ArrayList<>();
        for (int i = 0; i < full.participants.participants.size(); i++) {
            long id = full.participants.participants.get(i).user_id;
            if (id != self && id > 0) {
                members.add(id);
            }
        }
        return members.isEmpty() ? null : members;
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
    /** Remembers what was written, so the server's copy of it does not replace
     *  the words with base64 when it comes back. */
    public void wrote(long randomId, String text) {
        wrote(randomId, text, null);
    }

    /**
     * The same, for a message carrying a file.
     *
     * The description is kept as well as the words, because the server's copy
     * of a message we sent describes the file as what it was given: a document
     * with no type, no name and no size that means anything. Handed back and
     * stored, that copy replaces a local one that was right.
     */
    public void wrote(long randomId, String text, TLRPCMls.TL_mls_media media) {
        synchronized (this) {
            if (wroteHere.size() > 200) {
                wroteHere.clear();     // nothing here is worth keeping for long
                sentMedia.clear();
            }
            wroteHere.put(randomId, text);
            if (media != null) {
                sentMedia.put(randomId, media);
            }
        }
    }

    /**
     * Puts back what the server could not echo.
     *
     * A message this device sent comes back as a ciphertext it can never open
     * and, if it carried a file, as the blob the server was given. Both are
     * strictly less than what is already here, so neither is allowed to
     * replace it - the words come from what was written, and the file is
     * described from the same descriptor that was sent, over the server's own
     * id for it, so it stays downloadable.
     */
    public void restore(TLRPC.Message echoed, long randomId) {
        if (echoed == null || !isCiphertext(echoed.message)) {
            return;
        }
        String written;
        TLRPCMls.TL_mls_media media;
        synchronized (this) {
            written = wroteHere.get(randomId);
            media = sentMedia.get(randomId);
        }
        if (written != null) {
            echoed.message = written;
        }
        if (media != null) {
            MlsMedia.attach(echoed, media);
        }
    }

    /**
     * Whether there is a conversation with this peer to encrypt into, right
     * now, without asking anybody anything.
     *
     * Asked before a file is uploaded, because that decision cannot be taken
     * back: bytes that went up in the clear are up in the clear, and finding
     * out afterwards that the conversation exists is too late to help.
     */
    public boolean hasConversation(long peerId) {
        loadConversations();
        synchronized (this) {
            return groupIdByPeer.get(peerId) != null;
        }
    }

    /**
     * Everybody talked to in private, for the search that has to look at all of
     * them at once rather than at one chat.
     *
     * The server holds these conversations as ciphertext and cannot match a
     * word in one, so a search that only asks the server finds nothing in them
     * (#108). This is the list of chats the phone has to look through itself,
     * and it is short - one entry per person, not per message.
     *
     * The twin of encryptedPeerIds() on the other client.
     */
    public long[] encryptedPeerIds() {
        loadConversations();
        synchronized (this) {
            long[] peers = new long[groupIdByPeer.size()];
            int at = 0;
            for (Long peerId : groupIdByPeer.keySet()) {
                peers[at++] = peerId;
            }
            return peers;
        }
    }

    public String encrypt(long peerId, String text, ArrayList<TLRPC.MessageEntity> entities) {
        return encrypt(peerId, text, entities, null);
    }

    /**
     * The same, carrying who wrote the message first.
     *
     * A forward cannot be one on the wire here: the server copies a message by
     * its id, and a copy of a ciphertext lands where nobody holds the key. So
     * the client sends a new message and puts "forwarded from" inside it, where
     * the server cannot read it either.
     *
     * Losing that line was a real fault on the other client - a forward arrived
     * with no sign of who wrote it, which is not a forward at all.
     */
    public String encrypt(long peerId, String text, ArrayList<TLRPC.MessageEntity> entities,
                          Forwarded from) {
        return encrypt(peerId, text, entities, from, null);
    }

    /**
     * The same, carrying a file.
     *
     * The bytes went up encrypted and the server is holding them as a document
     * with no type, no name and no size that means anything. Everything needed
     * to turn them back into a picture travels here, inside the ciphertext:
     * what it is, how big it is on screen, and the key.
     */
    public String encrypt(long peerId, String text, ArrayList<TLRPC.MessageEntity> entities,
                          Forwarded from, TLRPCMls.TL_mls_media media) {
        // A file with no caption is still a message worth sending, so emptiness
        // only stops the ones that carry nothing at all.
        if ((text == null || text.isEmpty()) && media == null) {
            return null;
        }
        if (text == null) {
            text = "";
        }
        if (!worthEncrypting(peerId)) {
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
                // One shape for everything, the same one the other client
                // writes. It used to be a choice between two older shapes, and
                // neither of them could carry a file - nor could the client on
                // the other side read what this one chose to send.
                TLRPCMls.TL_mls_message content = new TLRPCMls.TL_mls_message();
                content.text = text;
                if (entities != null) {
                    content.entities.addAll(entities);
                }
                if (from != null) {
                    TLRPCMls.TL_mls_forward forward = new TLRPCMls.TL_mls_forward();
                    forward.from_id = from.id;
                    forward.from_name = from.name == null ? "" : from.name;
                    forward.date = from.date;
                    content.forward = forward;
                    content.flags |= 1;
                }
                if (media != null) {
                    content.media = media;
                    content.flags |= 2;
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
        ensureConversation(peerId, null);
    }

    /**
     * The same, with something to do once there is a conversation - or once it
     * is known there will not be one.
     *
     * This is what stops the first message to somebody going in the clear. The
     * conversation used to be started behind the send: the group appeared nine
     * milliseconds after the request had already left, so every conversation
     * began with one message the server could read - usually the one that says
     * why somebody is writing. The other client waits for it before sending and
     * has since it was built; this is the same thing, as a callback, because
     * here the send starts on the thread drawing the screen and cannot block.
     *
     * The callback always runs. Not worth encrypting, no key packages, a
     * handshake that fails, ten seconds gone - every one of them ends in the
     * callback and the message goes as it always did. A message that never
     * leaves is worse than a message the server can read.
     */
    public void ensureConversation(long peerId, Runnable then) {
        if (!worthEncrypting(peerId)) {
            fire(then);
            return;
        }
        loadConversations();
        Waiter waiter = null;
        boolean startNow;
        synchronized (this) {
            if (groupIdByPeer.containsKey(peerId)) {
                fire(then);
                // Before a message goes out is the moment worth checking that
                // the conversation still holds the people the chat does - it is
                // the one moment where being wrong is about to matter. Cheap
                // here: reconcile keeps its own interval and returns at once
                // when it has just looked.
                AndroidUtilities.runOnUIThread(() -> reconcile(peerId));
                return;
            }
            if (then != null) {
                waiter = new Waiter(then);
                java.util.ArrayList<Waiter> waiting = waitingForConversation.get(peerId);
                if (waiting == null) {
                    waiting = new java.util.ArrayList<>();
                    waitingForConversation.put(peerId, waiting);
                }
                waiting.add(waiter);
            }
            startNow = !starting.contains(peerId);
            if (startNow) {
                starting.add(peerId);
            }
        }
        if (waiter != null) {
            final Waiter deadline = waiter;
            AndroidUtilities.runOnUIThread(deadline::fire, CONVERSATION_WAIT);
        }
        if (!startNow) {
            // Somebody else is already building it; this send waits with theirs.
            return;
        }

        List<Long> members = membersOf(peerId);
        if (members == null) {
            // The membership is not known here yet. Sending in the clear is
            // right: a group encrypted to a guessed list leaves somebody out of
            // their own conversation, and with unreadable messages hidden they
            // would see an empty chat and never learn why.
            giveUp(peerId, "no membership for " + peerId);
            return;
        }
        claimEveryone(peerId, members);
    }

    /**
     * Collects the key packages of every member, then builds the conversation.
     *
     * All of them or none: a member whose devices we cannot reach is a member
     * who would sit in a chat where every message is hidden - which is worse
     * than a chat that says it is not encrypted, because nothing on screen says
     * why. So one empty answer sends the whole group in the clear.
     *
     * One at a time rather than in parallel. A group is a handful of people,
     * this happens once per conversation, and a sequence is a thing that can be
     * read in a log when it goes wrong.
     */
    private void claimEveryone(long peerId, List<Long> members) {
        claimNext(peerId, members, 0, new ArrayList<>());
    }

    private void claimNext(long peerId, List<Long> members, int at, ArrayList<byte[]> collected) {
        if (at >= members.size()) {
            final ArrayList<byte[]> packages = collected;
            Utilities.globalQueue.postRunnable(() -> begin(peerId, packages, members));
            return;
        }
        long member = members.get(at);
        TLRPCMls.TL_mls_claimKeyPackages request = new TLRPCMls.TL_mls_claimKeyPackages();
        request.user_id = member;
        ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) -> {
            if (error != null || !(response instanceof TLRPCMls.TL_mls_keyPackages)) {
                giveUp(peerId, "no key packages for " + member
                        + (error != null ? ": " + error.text : ""));
                return;
            }
            TLRPCMls.TL_mls_keyPackages claimed = (TLRPCMls.TL_mls_keyPackages) response;
            if (claimed.packages.isEmpty()) {
                // Not a failure: somebody whose client does not do this yet, or
                // a device that has not published. Remembered so the next
                // message does not pay for the same round trip.
                synchronized (MlsRuntime.this) {
                    withoutDevices.put(peerId, System.currentTimeMillis());
                }
                giveUp(peerId, members.size() > 1
                        ? "member " + member + " has no devices, so the group goes in the clear"
                        : null);
                return;
            }
            collected.addAll(claimed.packages);
            claimNext(peerId, members, at + 1, collected);
        });
    }

    /** Ends the building of a conversation that will not happen, once. */
    private void giveUp(long peerId, String why) {
        synchronized (MlsRuntime.this) {
            starting.remove(peerId);
        }
        settle(peerId);
        if (why != null) {
            FileLog.e("mls: " + why);
        }
    }

    /** The twin of fire(), for the collectors that report what they did. */
    private static void answer(Utilities.Callback<Boolean> then, boolean anything) {
        if (then != null) {
            AndroidUtilities.runOnUIThread(() -> then.run(anything));
        }
    }

    private static void fire(Runnable then) {
        if (then != null) {
            AndroidUtilities.runOnUIThread(then);
        }
    }

    /**
     * Lets go every send held for this peer, whatever the outcome was.
     *
     * Called from every path that ends the building of a conversation -
     * including the ones that end it badly. A path that quietly returns without
     * this is a message that never leaves, and there is no worse failure here.
     */
    private void settle(long peerId) {
        java.util.ArrayList<Waiter> waiting;
        synchronized (this) {
            waiting = waitingForConversation.remove(peerId);
        }
        if (waiting == null) {
            return;
        }
        for (Waiter waiter : waiting) {
            waiter.fire();
        }
    }

    private void begin(long peerId, List<byte[]> keyPackages, List<Long> members) {
        try (MlsCore.Identity identity = MlsKeyPackages.getInstance(currentAccount).identity()) {
            MlsCore.Group group = MlsCore.Group.create(identity);
            try {
                MlsCore.Invitation invitation = group.addMembers(identity, keyPackages);
                // Taken here and not asked about, which is the one place that is
                // right: this group did not exist a moment ago, so there is
                // nobody to have raced with and nothing for the server to order.
                // Every later change goes through sendCommit and waits.
                group.acceptCommit(identity);
                byte[] groupId = group.id();

                // Saved before the welcome is sent. A welcome delivered for a
                // conversation this device has forgotten is one the other side
                // can join and nobody can talk in.
                MlsKeyPackages.getInstance(currentAccount).save(identity);
                remember(peerId, groupId);

                // One welcome, every member. add_members made a single one that
                // serves all of them, and each has to be handed it separately
                // because the mailbox is addressed to a person.
                inviteEveryone(peerId, groupId, invitation.welcome, members, 0);
            } finally {
                group.close();
            }
        } catch (MlsCore.MlsException e) {
            synchronized (this) {
                starting.remove(peerId);
            }
            settle(peerId);
            FileLog.e("mls: cannot start a conversation with " + peerId + ": " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------------
    /**
     * Hands the welcome to every member, then lets the waiting sends go.
     *
     * Sequential, and the sends wait for the last of them: a message encrypted
     * before somebody has been invited is one they can never open, and with
     * unreadable messages hidden they would not even see that something was
     * said.
     */
    private void inviteEveryone(long peerId, byte[] groupId, byte[] welcome,
                                List<Long> members, int at) {
        handWelcomeTo(welcome, members, at, () -> {
            synchronized (MlsRuntime.this) {
                starting.remove(peerId);
            }
            settle(peerId);
            FileLog.d("mls: started conversation " + shortId(groupId) + " with "
                    + members.size() + " member(s) of " + peerId);
        });
    }

    /**
     * Hands one welcome to each of these people in turn, then does whatever
     * comes after.
     *
     * The mailbox is addressed to a person, so one welcome that serves several
     * devices still has to be posted once per member. Sequential: this happens
     * once per conversation and a sequence is something that can be read in a
     * log when it goes wrong.
     */
    private void handWelcomeTo(byte[] welcome, List<Long> members, int at, Runnable then) {
        if (at >= members.size()) {
            fire(then);
            return;
        }
        long member = members.get(at);
        TLRPCMls.TL_mls_sendWelcome send = new TLRPCMls.TL_mls_sendWelcome();
        send.user_id = member;
        send.welcome = welcome;
        ConnectionsManager.getInstance(currentAccount).sendRequest(send, (response, error) -> {
            if (error != null) {
                // The conversation exists here and somebody was never invited.
                // Sending in the clear from here on is right: a message they
                // cannot read is worse than one the server can.
                FileLog.e("mls: the welcome for " + member + " was not delivered");
            }
            handWelcomeTo(welcome, members, at + 1, then);
        });
    }

    // ----------------------------------------------------------------------
    // Changing who is in a conversation
    // ----------------------------------------------------------------------
    //
    // MLS moves a group one epoch at a time and validates a commit against the
    // epoch it was made from, so of two commits made from the same epoch exactly
    // one can be taken. RFC 9420 gives that ordering to the delivery service,
    // which is why mls.sendCommit exists and why nothing here is applied until
    // it answers.
    //
    // The shape of every change is the same: build the commit, offer it, and
    // then either keep it or let it go and start again on top of whoever won.

    /** How many times a change is rebuilt after losing a race. Bounded because
     *  a loop with no end is a client that never stops trying. */
    private static final int COMMIT_ATTEMPTS = 3;

    /** Conversations a change is being made to right now. Two at once on one
     *  device build two commits from one epoch and lose to each other. */
    private final java.util.Set<Long> changing = new java.util.HashSet<>();

    /** When each conversation was last compared against its chat. */
    private final Map<Long, Long> reconciledAt = new HashMap<>();

    /** How a device of this person is named: the user id, a slash, and which
     *  device. So the person is the prefix, and removing them means removing
     *  every name that starts with it. */
    private static byte[] nameOf(long userId) {
        return (userId + "/").getBytes();
    }

    private static boolean startsWith(byte[] name, byte[] prefix) {
        if (name.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (name[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private synchronized byte[] groupOf(long peerId) {
        loadConversations();
        return groupIdByPeer.get(peerId);
    }

    /** Which chat a group belongs to, for the times something arrives naming
     *  the group and nothing else. */
    private synchronized long peerOf(byte[] groupId) {
        loadConversations();
        for (Map.Entry<Long, byte[]> each : groupIdByPeer.entrySet()) {
            if (java.util.Arrays.equals(each.getValue(), groupId)) {
                return each.getKey();
            }
        }
        return 0;
    }

    /**
     * One change to who is in a conversation.
     *
     * It knows how to build itself against the group as it stands, who has to
     * be told, and what is left to do once it has been taken. It deliberately
     * does not know how to retry: a change made again after losing a race has
     * to be worked out afresh from what the group looks like now, not replayed.
     */
    private abstract class Change {
        final long peerId;

        Change(long peerId) {
            this.peerId = peerId;
        }

        /** The commit, built against the group exactly as it stands. Null when
         *  there is nothing left to do - which is ordinary, because somebody
         *  else's change may already have done it. */
        abstract byte[] build(MlsCore.Identity identity, MlsCore.Group group)
                throws MlsCore.MlsException;

        /** Who has to apply it. Everybody in the conversation, unless the
         *  change itself says otherwise. */
        List<Long> audience(List<Long> members) {
            return members;
        }

        /** What is left once the delivery service has taken it. */
        void taken(byte[] groupId, Runnable then) {
            fire(then);
        }

        abstract String describe();
    }

    /** Letting somebody in: a commit for those already here and a welcome for
     *  them, from the one call, because the two have to describe the same group. */
    private final class Adding extends Change {
        private final List<Long> newcomers;
        private final List<byte[]> keyPackages;
        private byte[] welcome;

        Adding(long peerId, List<Long> newcomers, List<byte[]> keyPackages) {
            super(peerId);
            this.newcomers = newcomers;
            this.keyPackages = keyPackages;
        }

        @Override
        byte[] build(MlsCore.Identity identity, MlsCore.Group group) throws MlsCore.MlsException {
            MlsCore.Invitation invitation = group.addMembers(identity, keyPackages);
            welcome = invitation.welcome;
            return invitation.commit;
        }

        @Override
        void taken(byte[] groupId, Runnable then) {
            // After the commit, not before. A welcome describes the group as it
            // is once the commit has been applied, so somebody who acts on it
            // first joins a conversation that does not exist yet.
            handWelcomeTo(welcome, newcomers, 0, then);
        }

        @Override
        String describe() {
            return "adding " + newcomers.size() + " to " + peerId;
        }
    }

    /** Taking people out, and with each of them every phone they hold. */
    private final class Removing extends Change {
        private final List<Long> leaving;

        Removing(long peerId, List<Long> leaving) {
            super(peerId);
            this.leaving = leaving;
        }

        @Override
        byte[] build(MlsCore.Identity identity, MlsCore.Group group) throws MlsCore.MlsException {
            List<byte[]> prefixes = new ArrayList<>();
            for (Long userId : leaving) {
                prefixes.add(nameOf(userId));
            }
            // Null when nobody matched, and that is not a failure: two people
            // removing the same person at once is ordinary, and the second is
            // looking at a group that already looks the way they wanted.
            return group.removeMembers(identity, prefixes);
        }

        @Override
        List<Long> audience(List<Long> members) {
            // Not the people being removed. They cannot apply it - being unable
            // to is the whole point - and it would sit in their box for ever.
            List<Long> rest = new ArrayList<>(members);
            rest.removeAll(leaving);
            return rest;
        }

        @Override
        String describe() {
            return "removing " + leaving + " from " + peerId;
        }
    }

    private synchronized boolean beginChanging(long peerId) {
        return changing.add(peerId);
    }

    private void doneChanging(long peerId) {
        synchronized (this) {
            changing.remove(peerId);
        }
    }

    /**
     * Offers a change to the delivery service and does whatever its answer
     * calls for.
     *
     * Nothing is applied here until that answer comes. Applying first is right
     * exactly until two people change one group at the same moment, and then
     * both move on into groups that hold different memberships and cannot read
     * each other - with nothing anywhere saying so, until a conversation quietly
     * stops working for some of the people in it.
     */
    private void commitChange(Change change, Runnable retry) {
        final long peerId = change.peerId;
        byte[] groupId = groupOf(peerId);
        if (groupId == null) {
            doneChanging(peerId);
            return;
        }
        List<Long> members = membersOf(peerId);
        if (members == null) {
            FileLog.e("mls: " + change.describe() + " - the membership is not known here");
            doneChanging(peerId);
            return;
        }

        byte[] commit;
        long epoch;
        try (MlsCore.Identity identity = MlsKeyPackages.getInstance(currentAccount).identity()) {
            MlsCore.Group group = MlsCore.Group.load(identity, groupId);
            if (group == null) {
                doneChanging(peerId);
                return;
            }
            try {
                commit = change.build(identity, group);
                if (commit == null) {
                    FileLog.d("mls: " + change.describe() + " - nothing left to do");
                    doneChanging(peerId);
                    return;
                }
                epoch = group.epoch();
                // Written down before it is offered. The commit is staged
                // rather than applied, and the answer may arrive after this
                // process has been killed - or never, if the connection drops.
                // Either way the way back is the commit box, and the box can
                // only help a device that still holds what it staged.
                MlsKeyPackages.getInstance(currentAccount).save(identity);
            } finally {
                group.close();
            }
        } catch (MlsCore.MlsException e) {
            // Usually a commit staged by an earlier attempt that never heard
            // back. Catching up resolves it - the server left us a copy of our
            // own commit for exactly this - and then the change is made again.
            FileLog.e("mls: " + change.describe() + " could not be built: " + e.getMessage());
            doneChanging(peerId);
            collectCommits(retry);
            return;
        }

        final long staked = epoch;
        TLRPCMls.TL_mls_sendCommit send = new TLRPCMls.TL_mls_sendCommit();
        send.group_id = groupId;
        send.epoch = epoch;
        send.commit = commit;
        send.members.addAll(change.audience(members));
        // And this account, which is not vanity: the other phones of the person
        // making the change are separate leaves and need the commit as much as
        // anybody, and this phone needs its own copy back to learn the outcome
        // if the answer below never arrives.
        send.members.add(UserConfig.getInstance(currentAccount).getClientUserId());

        ConnectionsManager.getInstance(currentAccount).sendRequest(send, (response, error) -> {
            if (error != null || !(response instanceof TLRPCMls.TL_mls_commitResult)) {
                // No answer is not the same as a refusal, and it must not be
                // treated as one: the commit may well have been taken. It stays
                // staged, and the copy the server left in our own box will say
                // how it ended.
                FileLog.e("mls: " + change.describe() + " went unanswered"
                        + (error != null ? ": " + error.text : ""));
                doneChanging(peerId);
                return;
            }
            TLRPCMls.TL_mls_commitResult result = (TLRPCMls.TL_mls_commitResult) response;
            Utilities.globalQueue.postRunnable(
                    () -> settleChange(change, groupId, staked, result, retry));
        });
    }

    private void settleChange(Change change, byte[] groupId, long staked,
                              TLRPCMls.TL_mls_commitResult result, Runnable retry) {
        final long peerId = change.peerId;
        boolean lost;
        try (MlsCore.Identity identity = MlsKeyPackages.getInstance(currentAccount).identity()) {
            MlsCore.Group group = MlsCore.Group.load(identity, groupId);
            if (group == null) {
                doneChanging(peerId);
                return;
            }
            try {
                if (result.accepted) {
                    group.acceptCommit(identity);
                    MlsKeyPackages.getInstance(currentAccount).save(identity);
                } else {
                    group.abandonCommit(identity);
                    MlsKeyPackages.getInstance(currentAccount).save(identity);
                }
                lost = !result.accepted;
            } finally {
                group.close();
            }
        } catch (MlsCore.MlsException e) {
            FileLog.e("mls: " + change.describe() + " could not be settled: " + e.getMessage());
            doneChanging(peerId);
            return;
        }

        if (!lost) {
            FileLog.d("mls: " + change.describe() + " taken, "
                    + shortId(groupId) + " is now at epoch " + result.epoch);
            change.taken(groupId, () -> doneChanging(peerId));
            return;
        }

        FileLog.d("mls: " + change.describe() + " lost epoch " + staked
                + "; the group is at " + result.epoch + ", catching up");
        doneChanging(peerId);
        collectCommits(retry);
    }

    // ----------------------------------------------------------------------
    // The two changes, from the outside
    // ----------------------------------------------------------------------

    /**
     * Somebody was added to a chat this device holds a conversation for.
     *
     * A chat that is not encrypted stays that way - adding a person to it does
     * not start anything, because the rule for a group is all of them or none
     * and that was decided when the conversation began.
     */
    public void memberAdded(long peerId, long userId) {
        if (userId == UserConfig.getInstance(currentAccount).getClientUserId()) {
            // Somebody let us in. We cannot add ourselves to an MLS group; the
            // welcome is on its way from whoever did it.
            return;
        }
        // Named rather than worked out from the chat. This runs the moment the
        // server confirms the addition, and the local idea of who is in the chat
        // has not caught up yet - so a comparison here would find nobody missing
        // and the person would wait for the next sweep to be let in.
        letIn(peerId, java.util.Collections.singletonList(userId), 1);
    }

    /** Somebody was taken out of a chat. */
    public void memberRemoved(long peerId, long userId) {
        removeMembers(peerId, java.util.Collections.singletonList(userId), 1);
    }

    private void removeMembers(long peerId, List<Long> leaving, int attempt) {
        for (Long userId : leaving) {
            removeMember(peerId, userId, attempt);
        }
    }

    private void removeMember(long peerId, long userId, int attempt) {
        // Said out loud, including the times it declines. A path that removes
        // nobody and says nothing is indistinguishable from one that was never
        // called, and the difference is exactly what has to be found when
        // somebody keeps reading a group they were thrown out of.
        FileLog.d("mls: asked to take " + userId + " out of " + peerId
                + " (attempt " + attempt + ")");
        if (!DialogObject.isChatDialog(peerId)) {
            FileLog.d("mls: " + peerId + " is not a group chat, nothing to do");
            return;
        }
        if (groupOf(peerId) == null) {
            FileLog.d("mls: " + peerId + " is not encrypted, nothing to do");
            return;
        }
        if (attempt > COMMIT_ATTEMPTS) {
            FileLog.e("mls: gave up removing " + userId + " from " + peerId
                    + " after " + COMMIT_ATTEMPTS + " attempts");
            return;
        }
        if (!beginChanging(peerId)) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> commitChange(
                new Removing(peerId, java.util.Collections.singletonList(userId)),
                () -> removeMember(peerId, userId, attempt + 1)));
    }

    /**
     * Makes the conversation match the chat: lets in whoever is missing, puts
     * out whoever should not be there any more.
     *
     * This exists because hanging the two changes on the places where they are
     * made does not work. A removal arrives at a device by more than one route -
     * the reply to whoever pressed the button, a service message, the
     * difference after being away - and hooking one of them is how somebody goes
     * on reading a group they were thrown out of. Membership is a fact about the
     * chat, not an event, so it is checked as a fact.
     *
     * The two halves are not symmetrical, and deliberately so. A wrong addition
     * costs a round trip. A missed removal is somebody reading a conversation
     * they are not in, for ever, with nothing on any screen to say so. So when
     * the two disagree and it is not clear which is stale, this takes the side
     * that fails safely: it removes.
     *
     * It also repairs an addition that failed - a dropped connection, a newcomer
     * whose client had not published a key package yet - instead of leaving
     * somebody sitting in a chat where nothing ever appears.
     */
    /**
     * @param listIsFromTheServer whether the membership being compared against
     *     was just handed over by the server, rather than remembered here.
     *     Only a fresh list may take somebody out: a remembered one can be
     *     missing people who joined while this device was away, and acting on
     *     it cuts them out of a conversation nobody asked to remove them from.
     *     It happened on the first run of this - a stale list dropped a member
     *     and the next comparison had to invite them back, which costs them
     *     every message in between.
     *
     *     Letting people in needs no such care, so it happens either way.
     */
    public void reconcile(long peerId, boolean listIsFromTheServer) {
        reconcile(peerId, listIsFromTheServer, 1);
    }

    /** With a list of unknown provenance, so additions only. */
    public void reconcile(long peerId) {
        reconcile(peerId, false, 1);
    }

    /** How long to leave between two comparisons of one conversation. Short,
     *  because a removal that waits is a removal that has not happened; long
     *  enough that a burst of chat-info loads does not open the group each time. */
    private static final long RECONCILE_NOT_BEFORE = 5_000L;

    private void reconcile(long peerId, boolean listIsFromTheServer, int attempt) {
        if (!DialogObject.isChatDialog(peerId) || groupOf(peerId) == null) {
            return;
        }
        synchronized (this) {
            Long last = reconciledAt.get(peerId);
            if (last != null && System.currentTimeMillis() - last < RECONCILE_NOT_BEFORE) {
                return;
            }
            reconciledAt.put(peerId, System.currentTimeMillis());
        }
        List<Long> members = membersOf(peerId);
        if (members == null) {
            // Not known here yet. Doing nothing is right: acting on a list this
            // device has never seen would be acting on nothing at all.
            return;
        }
        if (listIsFromTheServer) {
            putOutTheRest(peerId, members, attempt);
        }
        letIn(peerId, members, attempt);
    }

    /**
     * Removes from the conversation everybody the chat no longer holds.
     *
     * This account is never among them, whatever the list says. A person's own
     * membership is not something they work out by comparison - they are in the
     * chat or the chat is gone - and a device that removed itself would be left
     * holding a group it can no longer read or repair.
     */
    private void putOutTheRest(long peerId, List<Long> members, int attempt) {
        byte[] groupId = groupOf(peerId);
        if (groupId == null) {
            return;
        }
        java.util.Set<Long> belong = new java.util.HashSet<>(members);
        belong.add(UserConfig.getInstance(currentAccount).getClientUserId());

        List<Long> extra = whoIsExtra(groupId, belong);
        if (extra == null || extra.isEmpty()) {
            return;
        }
        FileLog.d("mls: " + extra + " are in " + shortId(groupId)
                + " and no longer in " + peerId);
        removeMembers(peerId, extra, attempt);
    }

    /**
     * Who is in the conversation and should not be.
     *
     * A leaf is named <user>/<device>, so the person is what comes before the
     * slash - and one person with two phones is two leaves that answer to the
     * same id. Null when the group cannot be opened, which is not the same as
     * nobody being extra.
     */
    private List<Long> whoIsExtra(byte[] groupId, java.util.Set<Long> belong) {
        try (MlsCore.Identity identity = MlsKeyPackages.getInstance(currentAccount).identity()) {
            MlsCore.Group group = MlsCore.Group.load(identity, groupId);
            if (group == null) {
                return null;
            }
            try {
                java.util.LinkedHashSet<Long> extra = new java.util.LinkedHashSet<>();
                for (byte[] name : group.memberNames()) {
                    long userId = userIdIn(name);
                    if (userId != 0 && !belong.contains(userId)) {
                        extra.add(userId);
                    }
                }
                return new ArrayList<>(extra);
            } finally {
                group.close();
            }
        } catch (MlsCore.MlsException e) {
            FileLog.e("mls: cannot see who is in " + shortId(groupId) + ": " + e.getMessage());
            return null;
        }
    }

    /** The person a leaf belongs to, or 0 when the name is not one of ours. */
    private static long userIdIn(byte[] name) {
        String text = new String(name);
        int slash = text.indexOf('/');
        if (slash <= 0) {
            return 0;
        }
        try {
            return Long.parseLong(text.substring(0, slash));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Lets into the conversation whichever of these people are not in it yet.
     *
     * The filtering is here rather than in the callers because it is what makes
     * a second attempt safe: whether the list came from one addition or from
     * comparing the whole chat, anybody already in the group is dropped, so
     * nobody is ever added twice.
     */
    private void letIn(long peerId, List<Long> candidates, int attempt) {
        if (!DialogObject.isChatDialog(peerId)) {
            return;
        }
        byte[] groupId = groupOf(peerId);
        if (groupId == null) {
            // Not an encrypted chat, and joining one does not make it so: the
            // rule for a group is all of them or none, and that was settled
            // when the conversation began.
            return;
        }
        if (attempt > COMMIT_ATTEMPTS) {
            FileLog.e("mls: gave up letting " + candidates.size() + " into " + peerId
                    + " after " + COMMIT_ATTEMPTS + " attempts");
            return;
        }
        if (!beginChanging(peerId)) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            List<Long> missing = whoIsMissing(groupId, candidates);
            if (missing == null || missing.isEmpty()) {
                doneChanging(peerId);
                return;
            }
            FileLog.d("mls: " + missing.size() + " of " + peerId
                    + " are not in " + shortId(groupId) + " yet");
            claimFor(peerId, missing, 0, new ArrayList<>(), attempt);
        });
    }

    /** Which of these people are not in the conversation. Null when the group
     *  cannot be opened, which is not the same as nobody missing. */
    private List<Long> whoIsMissing(byte[] groupId, List<Long> members) {
        try (MlsCore.Identity identity = MlsKeyPackages.getInstance(currentAccount).identity()) {
            MlsCore.Group group = MlsCore.Group.load(identity, groupId);
            if (group == null) {
                return null;
            }
            try {
                List<byte[]> names = group.memberNames();
                List<Long> missing = new ArrayList<>();
                for (Long member : members) {
                    byte[] prefix = nameOf(member);
                    boolean present = false;
                    for (byte[] name : names) {
                        if (startsWith(name, prefix)) {
                            present = true;
                            break;
                        }
                    }
                    if (!present) {
                        missing.add(member);
                    }
                }
                return missing;
            } finally {
                group.close();
            }
        } catch (MlsCore.MlsException e) {
            FileLog.e("mls: cannot see who is in " + shortId(groupId) + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Collects the key packages of everybody missing, then makes the change.
     *
     * Somebody with no devices to reach is left out of this round rather than
     * stopping it: the others should not wait for a client that has not
     * published anything yet. The comparison runs again later and picks them up
     * once it has.
     */
    private void claimFor(long peerId, List<Long> missing, int at,
                          ArrayList<byte[]> collected, int attempt) {
        if (at >= missing.size()) {
            List<Long> reachable = new ArrayList<>();
            for (Long member : missing) {
                if (!withoutDevices.containsKey(member)) {
                    reachable.add(member);
                }
            }
            if (collected.isEmpty()) {
                doneChanging(peerId);
                return;
            }
            final ArrayList<byte[]> packages = collected;
            final List<Long> newcomers = reachable;
            commitChange(new Adding(peerId, newcomers, packages),
                    () -> letIn(peerId, missing, attempt + 1));
            return;
        }
        long member = missing.get(at);
        TLRPCMls.TL_mls_claimKeyPackages request = new TLRPCMls.TL_mls_claimKeyPackages();
        request.user_id = member;
        ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) -> {
            if (error == null && response instanceof TLRPCMls.TL_mls_keyPackages) {
                TLRPCMls.TL_mls_keyPackages claimed = (TLRPCMls.TL_mls_keyPackages) response;
                if (claimed.packages.isEmpty()) {
                    FileLog.e("mls: " + member + " has no devices, so they stay outside "
                            + peerId + " for now");
                    synchronized (MlsRuntime.this) {
                        withoutDevices.put(member, System.currentTimeMillis());
                    }
                } else {
                    collected.addAll(claimed.packages);
                    synchronized (MlsRuntime.this) {
                        withoutDevices.remove(member);
                    }
                }
            } else {
                FileLog.e("mls: cannot reach " + member
                        + (error != null ? ": " + error.text : ""));
                synchronized (MlsRuntime.this) {
                    withoutDevices.put(member, System.currentTimeMillis());
                }
            }
            claimFor(peerId, missing, at + 1, collected, attempt);
        });
    }

    // ----------------------------------------------------------------------
    // The commits that came from everybody else
    // ----------------------------------------------------------------------

    private boolean collectingCommits;

    public void collectCommits() {
        collectCommits((Utilities.Callback<Boolean>) null);
    }

    /**
     * Catches this conversation up on everything waiting for it, then reads
     * again what could not be opened.
     *
     * Called from wherever a message would not open. The invitation or the
     * membership change that opens it travels by a different route from the
     * message and regularly arrives second, so the message is put aside behind
     * a lock and this is what comes back for it.
     *
     * The chat is named by the caller rather than worked out from the group,
     * and that is not a detail. An invitation says who sent it and nothing
     * else, so a device that has just joined has the conversation written down
     * against the person who invited it - reading again by that name reloads a
     * private chat while the group it was really about stays locked. Whoever
     * saw the locked message knows which chat it was in; nothing else does
     * until one of them opens (#40).
     */
    public void catchUp(long peerId) {
        // Once in a while per chat, and this is not tidiness. Reading again is
        // what asks the server for the history, and some of that history can
        // never be opened - a device that was out of the group for a while
        // holds ciphertexts from epochs it will never reach. So every reading
        // finds something it cannot open, which asks to catch up, which reads
        // again: a phone talking to the server several times a second and a
        // chat that never settles. It ran exactly that way before this line.
        long wait = 0;
        synchronized (this) {
            Long last = caughtUpAt.get(peerId);
            long since = last == null ? Long.MAX_VALUE : System.currentTimeMillis() - last;
            if (since < CATCH_UP_NOT_BEFORE) {
                // Too soon - but not never. What would open this may have been
                // posted since the last ask, and without a second attempt it
                // waits for whatever happens to trigger one: a phone sat three
                // minutes in a chat it had just been invited back into, with
                // the invitation waiting on the server the whole time.
                //
                // One queued attempt, no more. It is the reopening that finds
                // the next thing to ask about, and reopening only happens when
                // something was applied, so this stops of its own accord.
                if (!waitingToCatchUp.add(peerId)) {
                    return;
                }
                wait = CATCH_UP_NOT_BEFORE - since;
            } else {
                caughtUpAt.put(peerId, System.currentTimeMillis());
            }
        }
        if (wait > 0) {
            AndroidUtilities.runOnUIThread(() -> {
                synchronized (MlsRuntime.this) {
                    waitingToCatchUp.remove(peerId);
                }
                catchUp(peerId);
            }, wait);
            return;
        }

        collectWelcomes(joined -> collectCommits(applied -> {
            // Only when something changed. Reading again is what asks for the
            // history, and asking for it after learning nothing new finds the
            // same things that could not be opened last time - which asks to
            // catch up, and round it goes.
            if (joined || applied) {
                reopen(peerId);
            }
        }));
    }

    /** Chats with one attempt already queued behind the interval. */
    private final java.util.Set<Long> waitingToCatchUp = new java.util.HashSet<>();

    /** When this chat last asked for everything waiting for it. */
    private final Map<Long, Long> caughtUpAt = new HashMap<>();

    /** How long before it is worth asking again. Long enough that a history
     *  full of what cannot be opened does not turn into a loop, short enough
     *  that a welcome arriving late still surfaces the messages it opens. */
    private static final long CATCH_UP_NOT_BEFORE = 15_000L;

    /**
     * Collects the membership changes waiting on the server and applies each.
     *
     * Confirmed only after the new state has been saved. A commit confirmed and
     * then lost leaves this device an epoch behind, where nothing new opens -
     * and that shows up much later as a conversation that went quiet for one
     * person, which looks like anything but a lost commit.
     */
    public void collectCommits(Runnable then) {
        collectCommits(applied -> fire(then));
    }

    /** @param then given whether anything was actually applied. */
    public void collectCommits(Utilities.Callback<Boolean> then) {
        synchronized (this) {
            if (collectingCommits) {
                answer(then, false);
                return;
            }
            collectingCommits = true;
        }
        TLRPCMls.TL_mls_getCommits request = new TLRPCMls.TL_mls_getCommits();
        ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) -> {
            synchronized (MlsRuntime.this) {
                collectingCommits = false;
            }
            if (error != null) {
                FileLog.e("mls: cannot ask for commits: " + error.text);
                answer(then, false);
                return;
            }
            if (!(response instanceof TLRPCMls.TL_mls_commits)) {
                answer(then, false);
                return;
            }
            TLRPCMls.TL_mls_commits commits = (TLRPCMls.TL_mls_commits) response;
            if (commits.commits.isEmpty()) {
                answer(then, false);
                return;
            }
            Utilities.globalQueue.postRunnable(() -> applyCommits(commits, then));
        });
    }

    private void applyCommits(TLRPCMls.TL_mls_commits commits, Utilities.Callback<Boolean> then) {
        List<Long> applied = new ArrayList<>();
        java.util.Set<Long> moved = new java.util.HashSet<>();
        try (MlsCore.Identity identity = MlsKeyPackages.getInstance(currentAccount).identity()) {
            for (TLRPCMls.TL_mls_commit commit : commits.commits) {
                MlsCore.Group group = MlsCore.Group.load(identity, commit.group_id);
                if (group == null) {
                    // A conversation this device is not in yet. Ordinary while
                    // the welcome is still travelling, and it must not be
                    // confirmed - that would throw away the only copy.
                    continue;
                }
                try {
                    long epoch = group.epoch();
                    if (commit.epoch < epoch) {
                        // Already applied. The same commit arrives twice on
                        // ordinary routes: a confirmation that was lost, a
                        // device that stopped before saving.
                        applied.add(commit.id);
                        continue;
                    }
                    if (commit.epoch > epoch) {
                        // Not this one's turn. They are handed over oldest
                        // first, so an earlier one for this conversation has
                        // still to arrive, and applying out of order fails.
                        continue;
                    }
                    boolean somebodyElses = group.applyCommit(identity, commit.commit);
                    MlsKeyPackages.getInstance(currentAccount).save(identity);
                    applied.add(commit.id);
                    if (somebodyElses) {
                        moved.add(peerOf(commit.group_id));
                        FileLog.d("mls: " + shortId(commit.group_id) + " moved to epoch "
                                + (commit.epoch + 1) + ", changed by " + commit.from_id);
                    } else {
                        FileLog.d("mls: our own change to " + shortId(commit.group_id)
                                + " was taken after all, applied from the box");
                    }
                } catch (MlsCore.MlsException e) {
                    // Left unconfirmed on purpose: it may become applicable once
                    // an earlier one arrives.
                    FileLog.e("mls: cannot apply a commit to "
                            + shortId(commit.group_id) + ": " + e.getMessage());
                } finally {
                    group.close();
                }
            }
        } catch (MlsCore.MlsException e) {
            FileLog.e("mls: no identity to apply commits with: " + e.getMessage());
            answer(then, false);
            return;
        }

        // What was locked a moment ago may open now: a message written in the
        // new epoch arrived before the commit that opens it, which is ordinary -
        // the two travel by different routes.
        for (Long peerId : moved) {
            if (peerId != null && peerId != 0) {
                reopen(peerId);
            }
        }

        if (applied.isEmpty()) {
            answer(then, false);
            return;
        }
        TLRPCMls.TL_mls_confirmCommits confirm = new TLRPCMls.TL_mls_confirmCommits();
        confirm.ids.addAll(applied);
        ConnectionsManager.getInstance(currentAccount).sendRequest(confirm, (response, error) -> {
            if (error != null) {
                // Unconfirmed means they arrive again, which is harmless: one
                // that has already been applied is behind the group's epoch and
                // is dropped on sight.
                FileLog.e("mls: the commits were not confirmed: " + error.text);
            }
            answer(then, true);
        });
    }

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
        collectWelcomes(null);
    }

    /** @param then given whether this device joined anything. */
    public void collectWelcomes(Utilities.Callback<Boolean> then) {
        synchronized (this) {
            if (collectingWelcomes) {
                answer(then, false);
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
                answer(then, false);
                return;
            }
            if (!(response instanceof TLRPCMls.TL_mls_welcomes)) {
                answer(then, false);
                return;
            }
            TLRPCMls.TL_mls_welcomes welcomes = (TLRPCMls.TL_mls_welcomes) response;
            if (welcomes.welcomes.isEmpty()) {
                answer(then, false);
                return;
            }
            Utilities.globalQueue.postRunnable(() -> join(welcomes, then));
        });
    }

    /**
     * Reads again what is already stored for this person.
     *
     * Asked for from the server rather than rewritten in the database: the
     * history load goes through the same door every message does, so the
     * ciphertext is opened by the one piece of code that knows how, and the
     * chat, the chat list and the search all end up with the same words. Doing
     * it by hand in the database would be a second way of opening a message,
     * and two ways is how they come to disagree.
     */
    private void reopen(long peerId) {
        if (peerId == 0) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            // From the server, not the cache: the cache holds the ciphertext
            // that could not be opened, and asking it again would hand back the
            // same thing.
            MessagesController.getInstance(currentAccount).loadMessages(
                    peerId, 0L, false, 30, 0, 0, false, 0,
                    ConnectionsManager.generateClassGuid(), 0, 0, 0, 0L, 0, 0, false);
        });
    }

    private void join(TLRPCMls.TL_mls_welcomes welcomes, Utilities.Callback<Boolean> then) {
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
                    // What is already stored for this person was unreadable a
                    // moment ago and is not any more. A message and the welcome
                    // that opens it travel by different routes and the message
                    // usually wins, so without this the first thing anybody
                    // ever receives stays a ciphertext.
                    reopen(welcome.from_id);
                } catch (MlsCore.MlsException e) {
                    // One invitation that cannot be joined must not stop the
                    // others: they are from different people.
                    FileLog.e("mls: cannot join an invitation from "
                            + welcome.from_id + ": " + e.getMessage());
                }
            }
        } catch (MlsCore.MlsException e) {
            FileLog.e("mls: no identity to join with: " + e.getMessage());
            answer(then, false);
            return;
        }

        if (joined.isEmpty()) {
            answer(then, false);
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
            answer(then, true);
        });
    }
}

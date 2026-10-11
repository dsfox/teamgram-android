package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Locale;

/**
 * What the encryption needs from this device's message database (#108, #143):
 * searching the conversations the server cannot read, and never storing a lock
 * over words this device already holds. Kept out of MessagesStorage, which
 * upstream rewrites at every release, and reached from it by one call (#196).
 */
public final class MlsStorage {

    private final MessagesStorage storage;

    private MlsStorage(MessagesStorage storage) {
        this.storage = storage;
    }

    public static MlsStorage of(MessagesStorage storage) {
        return new MlsStorage(storage);
    }

    public static MlsStorage of(int account) {
        return new MlsStorage(MessagesStorage.getInstance(account));
    }

    /**
     * Looks for a word in the conversations the server cannot read (#108).
     *
     * Every message search on this client asks messages.searchGlobal and
     * nothing else, and the server holds an encrypted conversation as
     * ciphertext - so a word plainly visible in the chat list was not findable
     * at all. The phone has the plaintext; it simply had never been asked.
     *
     * No index and no new table, on purpose. An index means a schema version, a
     * write on the path that stores every message, and a backfill for the
     * history that predates it - three chances to damage the one file whose
     * damage cannot be undone. What is searched here is messages_v2 itself,
     * which already holds every message this device has.
     *
     * What keeps that honest is the peer list: only conversations that encrypt,
     * which is a handful of people rather than every chat, and a limit on top of
     * that. Everything else is still the server's to answer, and better answered
     * there - it has history this device never downloaded.
     *
     * Matched in Java rather than in SQL because the text lives inside a
     * serialised message, not in a column: LIKE against a blob stops at the
     * first zero byte and would answer with a confident nothing.
     */
    public void searchEncryptedMessages(String query, long[] peers, int limit,
                                        Utilities.Callback3<ArrayList<TLRPC.Message>, ArrayList<TLRPC.User>, ArrayList<TLRPC.Chat>> done) {
        if (done == null) {
            return;
        }
        if (TextUtils.isEmpty(query) || peers == null || peers.length == 0) {
            AndroidUtilities.runOnUIThread(() -> done.run(new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
            return;
        }
        final String needle = query.toLowerCase();
        storage.getStorageQueue().postRunnable(() -> {
            SQLiteCursor cursor = null;
            final ArrayList<TLRPC.Message> found = new ArrayList<>();
            final ArrayList<TLRPC.User> users = new ArrayList<>();
            final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
            try {
                final long selfId = storage.getUserConfig().getClientUserId();
                final ArrayList<Long> usersToLoad = new ArrayList<>();
                final ArrayList<Long> chatsToLoad = new ArrayList<>();
                final ArrayList<Long> animatedEmojiToLoad = new ArrayList<>();
                // Newest first, and stopped at a limit rather than read whole:
                // this runs on every keystroke, and a conversation of years is
                // still a conversation somebody is typing into.
                cursor = storage.getDatabase().queryFinalized(
                    "SELECT data FROM messages_v2 WHERE uid IN (" + TextUtils.join(",", asList(peers))
                        + ") ORDER BY date DESC LIMIT " + SEARCH_ENCRYPTED_SCAN);
                while (cursor.next() && found.size() < limit) {
                    NativeByteBuffer data = cursor.byteBufferValue(0);
                    if (data == null) {
                        continue;
                    }
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    if (message == null) {
                        data.reuse();
                        continue;
                    }
                    message.readAttachPath(data, selfId);
                    data.reuse();
                    if (TextUtils.isEmpty(message.message)
                            || !message.message.toLowerCase().contains(needle)) {
                        continue;
                    }
                    MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, animatedEmojiToLoad);
                    found.add(message);
                }
                cursor.dispose();
                cursor = null;

                if (!usersToLoad.isEmpty()) {
                    storage.getUsersInternal(usersToLoad, users);
                }
                if (!chatsToLoad.isEmpty()) {
                    storage.getChatsInternal(TextUtils.join(",", chatsToLoad), chats);
                }
            } catch (Exception e) {
                storage.checkSQLException(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
            AndroidUtilities.runOnUIThread(() -> done.run(found, users, chats));
        });
    }

    /**
     * How many stored messages are read before giving up, per search.
     *
     * A ceiling rather than a guess at what is enough: the rows are read newest
     * first, so what falls off the end is the oldest, and the server answers for
     * everything it can read anyway. Said out loud here because a silent cap is
     * a search that reports "nothing" when it means "not in the last few
     * thousand".
     */
    private static final int SEARCH_ENCRYPTED_SCAN = 4000;

    private static ArrayList<Long> asList(long[] values) {
        ArrayList<Long> list = new ArrayList<>(values.length);
        for (long value : values) {
            list.add(value);
        }
        return list;
    }

    /** What a message that cannot be read is stored as: the lock that stands in
     *  for the ciphertext, or the ciphertext itself. */
    static boolean unreadable(String text) {
        return MlsRuntime.LOCKED.equals(text) || MlsRuntime.isCiphertext(text);
    }

    /**
     * Keeps the words a stored message already has when the copy arriving has
     * none (#143).
     *
     * MLS gives a sender no way to open their own ciphertext - that is the
     * design, and the core holds it in `the_sender_cannot_read_their_own_message`.
     * So for a message this device wrote, the local copy is the only readable
     * one that will ever exist here, and the server sends its own copy back by
     * half a dozen routes: the confirmation of a send, a history load, the
     * dialog list, the difference after a restart. Each of them arrives as a
     * lock over a ciphertext, and storing that over the words is how a person
     * loses their own message - locally, silently, and beyond recovery, because
     * what is left on the server can never be opened by this device.
     *
     * The same holds for a message received and opened once: MLS opens a
     * ciphertext exactly once, so a copy arriving a second time is worth less
     * than the one already stored.
     *
     * This sits where messages reach the database rather than at whichever
     * route was found first, because the copy comes back by routes nobody has
     * thought of yet and the next one will too. The other client settled on the
     * same rule in the same place - `mlsKeepingWhatIsReadable`.
     */
    public void keepWhatIsReadable(ArrayList<TLRPC.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (int a = 0; a < messages.size(); a++) {
            TLRPC.Message message = messages.get(a);
            // A message with no id of its own has nothing stored to compare
            // against: it is being written for the first time.
            if (message == null || message.id <= 0 || !unreadable(message.message)) {
                continue;
            }
            long dialogId = MessageObject.getDialogId(message);
            TLRPC.Message stored = null;
            SQLiteCursor cursor = null;
            try {
                cursor = storage.getDatabase().queryFinalized(String.format(Locale.US,
                        "SELECT data FROM messages_v2 WHERE mid = %d AND uid = %d LIMIT 1",
                        message.id, dialogId));
                if (cursor.next()) {
                    NativeByteBuffer data = cursor.byteBufferValue(0);
                    if (data != null) {
                        stored = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                        if (stored != null) {
                            stored.readAttachPath(data, storage.getUserConfig().clientUserId);
                        }
                        data.reuse();
                    }
                }
            } catch (Exception e) {
                storage.checkSQLException(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
            if (stored == null || unreadable(stored.message)) {
                continue;
            }
            // Readable means words or a file: a picture sent without a caption
            // has nothing to say and is still the thing this device is holding.
            // Asking for words alone would leave every uncaptioned photo
            // replaced by the server's blob in the sender's own chat.
            if (TextUtils.isEmpty(stored.message) && stored.media == null) {
                continue;
            }
            message.message = stored.message;
            // The formatting is local too, and for the same reason: it went
            // inside the ciphertext, so the copy coming back carries none and
            // taking it would leave the message readable but stripped of its
            // bold and its links.
            message.entities = stored.entities;
            if (stored.entities != null && !stored.entities.isEmpty()) {
                message.flags |= TLRPC.MESSAGE_FLAG_HAS_ENTITIES;
            } else {
                message.flags &= ~TLRPC.MESSAGE_FLAG_HAS_ENTITIES;
            }
            // And the file. What arrives is the blob of noise the server was
            // given; the picture is the one already here, made by this device.
            if (stored.media != null) {
                message.media = stored.media;
            }
            // A forward into an encrypted conversation travels as an ordinary
            // message, so the copy coming back says nothing about who wrote it
            // first, and taking that would turn somebody else's words into the
            // sender's own.
            if (message.fwd_from == null && stored.fwd_from != null) {
                message.fwd_from = stored.fwd_from;
                message.flags |= TLRPC.MESSAGE_FLAG_FWD;
            }
            // attachPath is where the path to the file is kept, and where a
            // ciphertext waiting to be opened is put aside. The stored copy is
            // readable, so what it holds is the path.
            message.attachPath = stored.attachPath;
            // Said out loud, because silence is the whole of this fault. The
            // line means the words were about to be replaced by a lock and were
            // not.
            FileLog.d("mls: kept the words of message " + message.id + " in " + dialogId
                    + " - the copy that arrived had none");
        }
    }
}

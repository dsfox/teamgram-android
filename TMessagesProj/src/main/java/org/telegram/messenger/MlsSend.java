package org.telegram.messenger;

import android.widget.Toast;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.TLRPCMls;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Sending into a conversation that encrypts: text encrypted once there is a
 * conversation, files encrypted on the way up and described inside the
 * ciphertext, forwards of files sent again rather than copied. Kept out of
 * SendMessagesHelper, which upstream rewrites at every release, and reached
 * from it by one call at each place it begins (#196).
 */
final class MlsSend extends BaseController implements NotificationCenter.NotificationCenterDelegate {

    private static final MlsSend[] Instance = new MlsSend[UserConfig.MAX_ACCOUNT_COUNT];

    /**
     * Albums on their way into a conversation that encrypts, by the group id
     * the attachment sheet gave them (#254). Touched on the main thread only,
     * where every item is given, uploaded, failed and cancelled.
     */
    private final HashMap<Long, Album> albums = new HashMap<>();

    /**
     * Messages that cannot be encrypted yet, by their id: what to do again
     * once they can (#256). Touched on the main thread only.
     */
    private final HashMap<Integer, Waiting> waiting = new HashMap<>();

    private static final class Waiting {
        final long peer;
        final Runnable again;
        boolean going;

        Waiting(long peer, Runnable again) {
            this.peer = peer;
            this.again = again;
        }
    }

    static MlsSend getInstance(int num) {
        MlsSend local = Instance[num];
        if (local == null) {
            synchronized (MlsSend.class) {
                local = Instance[num];
                if (local == null) {
                    Instance[num] = local = new MlsSend(num);
                }
            }
        }
        return local;
    }

    private MlsSend(int num) {
        super(num);
        // An item of an album that fails or is cancelled has to stop being
        // waited for, or the rest of its album waits for good; and a message
        // deleted while it waits to be encrypted must never go.
        AndroidUtilities.runOnUIThread(() -> {
            getNotificationCenter().addObserver(this, NotificationCenter.messageSendError);
            getNotificationCenter().addObserver(this, NotificationCenter.messagesDeleted);
        });
    }

    /** One album being gathered: its items in the order they were given, and
     *  whether the last of them has been. */
    private static final class Album {
        final long id;
        final long peer;
        final ArrayList<Item> items = new ArrayList<>();
        boolean whole;

        Album(long id, long peer) {
            this.id = id;
            this.peer = peer;
        }

        Item item(long randomId) {
            for (Item each : items) {
                if (each.randomId == randomId) {
                    return each;
                }
            }
            return null;
        }

        boolean ready() {
            if (!whole) {
                return false;
            }
            for (Item each : items) {
                if (each.encrypted == null) {
                    return false;
                }
            }
            return true;
        }
    }

    /** An item of an album, and once it has gone up, the send that carries it
     *  - its request already holding the blob and the ciphertext. */
    private static final class Item {
        final int messageId;
        final long randomId;
        SendMessagesHelper.DelayedMessage encrypted;

        Item(int messageId, long randomId) {
            this.messageId = messageId;
            this.randomId = randomId;
        }
    }

    /**
     * Whether an item of an album leaves upstream's album path for this one
     * (#254): yes when the chat encrypts, whether or not a conversation exists
     * yet (#257) - its items then wait in sendMedia like any file.
     *
     * Upstream's album path uploaded the pictures as pictures and sent the
     * captions beside them, and nothing on it knew about encryption. So an
     * item here is sent the way a single file is - encrypted on the way up and
     * described inside its own ciphertext - and drawn at once, still in its
     * album; then, instead of going out alone, it waits in sendMedia for the
     * rest, and the album goes as one messages.sendMultiMedia. That is the
     * shape iOS sends, and the server makes it one album.
     *
     * An item sent again - after it failed, or after the app was closed under
     * it - is not gathered: the rest of its album may be long gone, and an item
     * waiting for them would wait for good. It goes alone, encrypted.
     */
    boolean takesAlbumItem(long peer, TLRPC.Message message, long groupId, boolean last, boolean again) {
        if (!MlsRuntime.getInstance(currentAccount).encrypts(peer)) {
            return false;
        }
        if (again) {
            FileLog.d("mls: message " + message.id + " of album " + groupId + " is sent again, alone");
            return true;
        }
        Album album = albums.get(groupId);
        if (album == null) {
            albums.put(groupId, album = new Album(groupId, peer));
        }
        album.items.add(new Item(message.id, message.random_id));
        album.whole |= last;
        FileLog.d("mls: message " + message.id + " is item " + album.items.size() + " of album " + groupId
                + " for " + peer + (last ? ", the last" : ""));
        return true;
    }

    /**
     * The end of an album, said after its last item was given rather than on
     * it: upstream closes a group this way when the next file is of another
     * kind, or when the last of the pictures went as a file.
     */
    void albumEnds(long groupId) {
        Album album = albums.get(groupId);
        if (album == null || album.whole) {
            return;
        }
        album.whole = true;
        FileLog.d("mls: album " + groupId + " ends with " + album.items.size() + " item(s)");
        if (album.ready()) {
            albums.remove(groupId);
            send(album);
        }
    }

    /** The album of the item this send carries, or null when it is not one
     *  gathered here. */
    private Album albumOf(SendMessagesHelper.DelayedMessage message) {
        if (message.obj == null) {
            return null;
        }
        for (Album album : albums.values()) {
            if (album.item(message.obj.messageOwner.random_id) != null) {
                return album;
            }
        }
        return null;
    }

    /**
     * An item whose request is ready - blob and ciphertext in it - kept until
     * the rest of its album is; the album goes when the last one is in.
     *
     * @return whether the send was an item of an album, and is taken care of.
     */
    private boolean gathered(SendMessagesHelper.DelayedMessage message) {
        Album album = albumOf(message);
        if (album == null || !(message.sendRequest instanceof TLRPC.TL_messages_sendMedia)) {
            return false;
        }
        album.item(message.obj.messageOwner.random_id).encrypted = message;
        if (album.ready()) {
            albums.remove(album.id);
            send(album);
        }
        return true;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (albums.isEmpty() && waiting.isEmpty()) {
            return;
        }
        if (id == NotificationCenter.messageSendError) {
            forget((Integer) args[0], "failed");
        } else if (id == NotificationCenter.messagesDeleted) {
            @SuppressWarnings("unchecked")
            ArrayList<Integer> ids = (ArrayList<Integer>) args[0];
            for (Integer each : new ArrayList<>(ids)) {
                forget(each, "was cancelled");
                // A message deleted while it waits must never go.
                if (waiting.remove(each) != null) {
                    FileLog.d("mls: message " + each + " was deleted while it waited");
                }
            }
        }
    }

    /** An item that will not be sent: the album goes without it once the rest
     *  is in, rather than waiting for something that cannot come. */
    private void forget(int messageId, String why) {
        for (Album album : albums.values()) {
            for (int index = 0; index < album.items.size(); index++) {
                Item item = album.items.get(index);
                if (item.messageId != messageId) {
                    continue;
                }
                album.items.remove(index);
                FileLog.d("mls: message " + messageId + " of album " + album.id + " " + why
                        + ", " + album.items.size() + " item(s) left");
                if (item.encrypted != null) {
                    // Whatever was sent after it and waits for it goes now.
                    item.encrypted.sendDelayedRequests();
                }
                if (album.ready()) {
                    albums.remove(album.id);
                    send(album);
                }
                return;
            }
        }
    }

    /**
     * The album, out as one messages.sendMultiMedia: each item the blob it
     * went up as and its own ciphertext, with its caption inside. What the
     * album shares - the peer, a reply, a schedule, an effect - is taken from
     * the first item's own request, where upstream put it.
     */
    private void send(Album album) {
        if (album.items.isEmpty()) {
            return;
        }
        SendMessagesHelper.DelayedMessage head = album.items.get(0).encrypted;
        TLRPC.TL_messages_sendMedia first = (TLRPC.TL_messages_sendMedia) head.sendRequest;
        TLRPC.TL_messages_sendMultiMedia request = new TLRPC.TL_messages_sendMultiMedia();
        request.peer = first.peer;
        request.silent = first.silent;
        request.invert_media = first.invert_media;
        // reply_to, schedule_date, quick_reply_shortcut and effect sit at the
        // same bits in both requests.
        request.flags = first.flags & (1 | 1024 | 131072 | 262144);
        request.reply_to = first.reply_to;
        request.schedule_date = first.schedule_date;
        request.quick_reply_shortcut = first.quick_reply_shortcut;
        request.effect = first.effect;

        ArrayList<MessageObject> objects = new ArrayList<>();
        ArrayList<String> paths = new ArrayList<>();
        ArrayList<Object> parents = new ArrayList<>();
        for (Item item : album.items) {
            TLRPC.TL_messages_sendMedia single = (TLRPC.TL_messages_sendMedia) item.encrypted.sendRequest;
            TLRPC.TL_inputSingleMedia media = new TLRPC.TL_inputSingleMedia();
            media.random_id = single.random_id;
            media.media = single.media;
            media.message = single.message;
            request.multi_media.add(media);
            objects.add(item.encrypted.obj);
            paths.add(item.encrypted.originalPath);
            parents.add(item.encrypted.parentObject);
        }
        FileLog.d("mls: album " + album.id + " goes to " + album.peer + " as " + request.multi_media.size()
                + " encrypted item(s)");
        getSendMessagesHelper().performSendMessageRequestMulti(request, objects, paths, parents, null, head.scheduled);
        // Whatever was sent after an item and waits for it goes after the album.
        for (Item item : album.items) {
            item.encrypted.sendDelayedRequests();
        }
    }

    /**
     * The text of a message, sent once there is a conversation to encrypt into.
     *
     * What travels is the ciphertext; what stays in the chat is the words. The
     * formatting travels inside the ciphertext, because an entity is a pair of
     * offsets into the text and beside a ciphertext they point at nothing - so
     * the entities are dropped from the request rather than sent pointing into
     * base64. Filled in just before the request goes out: the conversation may
     * not exist yet, and asking at once would start it and answer null in the
     * same breath - which is how every conversation used to begin with one
     * message the server could read.
     *
     * The message is already drawn in the chat as sending, so nothing on screen
     * waits but the clock. When no conversation can be had yet - none so far,
     * one this phone is waiting to be let into, a handshake that failed - the
     * message waits for it and goes once there is one (the owner's word of 12
     * October, #256); it is never sent in the clear. A chat that does not
     * encrypt at all, a bot or Saved Messages, sends as it always did.
     */
    void encryptThenSend(long peer, int messageId, TLRPC.TL_messages_sendMessage sending, long randomId, String plain,
                         ArrayList<TLRPC.MessageEntity> entities, MlsRuntime.Forwarded forwarded, Runnable send) {
        MlsRuntime runtime = MlsRuntime.getInstance(currentAccount);
        runtime.ensureConversation(peer, () -> {
            String carried = runtime.encrypt(peer, plain, entities, forwarded);
            if (carried == null && runtime.encrypts(peer)) {
                waiting.put(messageId, new Waiting(peer,
                        () -> encryptThenSend(peer, messageId, sending, randomId, plain, entities, forwarded, send)));
                FileLog.d("mls: message " + messageId + " to " + peer + " waits until it can be encrypted");
                return;
            }
            waiting.remove(messageId);
            if (carried != null) {
                // Remembered under the same random id the server will echo
                // back, so its copy does not replace what was written with
                // base64.
                runtime.wrote(randomId, plain);
                sending.message = carried;
                // The formatting travels inside the ciphertext, so it must not
                // travel beside it as well.
                sending.entities = null;
                sending.flags &= ~8;
            }
            send.run();
        });
    }

    /** Whether this message waits here to be encrypted, or is on its way: the
     *  app's own resend leaves it alone, since it goes by itself. */
    boolean isWaiting(int messageId) {
        return waiting.containsKey(messageId);
    }

    /** A conversation with this peer can be written into now: what waits for
     *  it goes, oldest first. */
    void conversationReady(long peer) {
        ArrayList<Integer> ready = new ArrayList<>();
        for (java.util.Map.Entry<Integer, Waiting> each : waiting.entrySet()) {
            if (each.getValue().peer == peer && !each.getValue().going) {
                ready.add(each.getKey());
            }
        }
        // Local ids count down, so the oldest message has the highest.
        java.util.Collections.sort(ready, java.util.Collections.reverseOrder());
        if (!ready.isEmpty()) {
            FileLog.d("mls: " + ready.size() + " message(s) to " + peer + " try again");
        }
        for (Integer id : ready) {
            Waiting held = waiting.get(id);
            held.going = true;
            held.again.run();
        }
    }

    /** On every round, whatever still waits tries again: a person who had no
     *  device may have one now, and a pause on building may be over. */
    void tryWaitingAgain() {
        AndroidUtilities.runOnUIThread(() -> {
            java.util.HashSet<Long> peers = new java.util.HashSet<>();
            for (Waiting held : waiting.values()) {
                if (!held.going) {
                    peers.add(held.peer);
                }
            }
            for (Long peer : peers) {
                conversationReady(peer);
            }
        });
    }

    /**
     * The new words of an edit, encrypted on their way out (#252).
     *
     * Editing used to undo the encryption of a message that had gone out
     * encrypted: the new text went up in the clear and the server held, from
     * then on, the words it had never been able to read. So an edit into a chat
     * with a conversation goes encrypted or not at all - when encrypting fails
     * the edit is taken back on this phone and the person is told, rather than
     * the words going up readable - and so is one in a chat that encrypts but
     * has no conversation yet (#256): an edit is not kept for later the way a
     * new message is. A chat that does not encrypt edits as before.
     *
     * Asked where every edit request ends (performSendMessageRequest), so the
     * text edit, the caption edit and the file edit all pass here. One already
     * carrying a ciphertext - a replaced file, encrypted with its description in
     * sendMedia - is left as it is.
     *
     * @return whether the request may go.
     */
    boolean encryptEdit(TLObject request, MessageObject message) {
        if (!(request instanceof TLRPC.TL_messages_editMessage) || message == null) {
            return true;
        }
        TLRPC.TL_messages_editMessage edit = (TLRPC.TL_messages_editMessage) request;
        // A caption taken away carries nothing to hide.
        if (edit.message == null || edit.message.isEmpty() || MlsRuntime.isCiphertext(edit.message)) {
            return true;
        }
        long peer = message.getDialogId();
        if (!MlsRuntime.getInstance(currentAccount).encrypts(peer)) {
            return true;
        }
        String carried = MlsRuntime.getInstance(currentAccount).encrypt(peer, edit.message, edit.entities, null);
        if (carried == null) {
            FileLog.e("mls: an edit of message " + message.getId() + " in " + peer
                    + " could not be encrypted, so it is not sent");
            getSendMessagesHelper().revertEditingMessageObject(message);
            AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext,
                    LocaleController.getString(R.string.ErrorOccurred), Toast.LENGTH_SHORT).show());
            return false;
        }
        MlsRuntime.getInstance(currentAccount).edited(peer, message.getId(), edit.message,
                edit.entities == null ? null : new ArrayList<>(edit.entities), null);
        FileLog.d("mls: an edit of message " + message.getId() + " in " + peer + " goes encrypted");
        edit.message = carried;
        // The formatting travelled inside the ciphertext.
        edit.entities = new ArrayList<>();
        edit.flags &= ~8;
        return true;
    }

    /**
     * Where a forward actually arrives, whatever started it. A forward of a
     * file into a conversation that encrypts cannot be a forward at all: the
     * server copies by id, so the copy points at the original blob - under a
     * key this conversation was never given, or under none - and carries
     * neither a description nor a key. Nobody could open it and nothing would
     * say so. Those files are sent again; null means nothing here was ours to
     * handle, a number is what the caller returns.
     */
    Integer splitForwards(ArrayList<MessageObject> messages, long peer, boolean forwardFromMyName, boolean hideCaption,
                          boolean notify, int scheduleDate, int scheduleRepeatPeriod, MessageObject replyToTopMsg,
                          int video_timestamp, long payStars, long monoForumPeerId, MessageSuggestionParams suggestionParams) {
        if (messages != null && !messages.isEmpty()
                && MlsRuntime.getInstance(currentAccount).hasConversation(peer)
                && hasFile(messages)) {
            ArrayList<MessageObject> plain = new ArrayList<>();
            for (MessageObject each : messages) {
                if (each != null && each.messageOwner != null && each.messageOwner.media != null
                        && !(each.messageOwner.media instanceof TLRPC.TL_messageMediaEmpty)
                        && !(each.messageOwner.media instanceof TLRPC.TL_messageMediaWebPage)) {
                    forwardEncryptedFile(each, peer, payStars, monoForumPeerId, suggestionParams);
                } else {
                    plain.add(each);
                }
            }
            if (plain.isEmpty()) {
                return 0;
            }
            return getSendMessagesHelper().sendMessage(plain, peer, forwardFromMyName, hideCaption, notify, scheduleDate,
                    scheduleRepeatPeriod, replyToTopMsg, video_timestamp, payStars, monoForumPeerId,
                    suggestionParams);
        }
        return null;
    }

    /** Who wrote a message first: whoever forwarded it before, or its author. */
    MlsRuntime.Forwarded forwardedFrom(MessageObject messageObject) {
        TLRPC.Message owner = messageObject.messageOwner;
        if (owner.fwd_from != null) {
            long id = owner.fwd_from.from_id != null
                    ? MessageObject.getPeerId(owner.fwd_from.from_id) : 0;
            return new MlsRuntime.Forwarded(id, owner.fwd_from.from_name, owner.fwd_from.date);
        }
        long author = owner.from_id != null ? MessageObject.getPeerId(owner.from_id) : 0;
        String name = null;
        TLRPC.User user = getMessagesController().getUser(author);
        if (user != null) {
            name = ContactsController.formatName(user.first_name, user.last_name);
        }
        return new MlsRuntime.Forwarded(author, name, owner.date);
    }

    /** Whether any of these carries a file, which is what cannot be forwarded
     *  into a conversation that encrypts. Asked before the list is split, so a
     *  list of plain text goes through untouched rather than round again. */
    static boolean hasFile(ArrayList<MessageObject> messages) {
        for (MessageObject each : messages) {
            if (each != null && each.messageOwner != null && each.messageOwner.media != null
                    && !(each.messageOwner.media instanceof TLRPC.TL_messageMediaEmpty)
                    && !(each.messageOwner.media instanceof TLRPC.TL_messageMediaWebPage)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sends a file again rather than forwarding it, when the conversation it is
     * going into encrypts.
     *
     * True when it has been taken care of. False when this is not that case at
     * all - no key, or nothing to send - and then the caller forwards as it
     * always did.
     *
     * A file that is not on this device cannot be sent again, because sending
     * it means reading it. Rather than forward it in the clear, which is the
     * thing this exists to prevent, the message is refused and says why.
     */
    boolean forwardEncryptedFile(MessageObject messageObject, long did, long payStars,
                                         long monoForumPeerId, MessageSuggestionParams suggestionParams) {
        if (!MlsRuntime.getInstance(currentAccount).hasConversation(did)) {
            return false;
        }
        File file = forwardableBytes(messageObject);
        if (file == null) {
            FileLog.e("mls: a file being forwarded to " + did + " is not on this device, "
                    + "so it is not sent - forwarding it would send it in the clear. "
                    + "attachPath '" + messageObject.messageOwner.attachPath + "', media "
                    + (messageObject.messageOwner.media == null ? "none"
                            : messageObject.messageOwner.media.getClass().getSimpleName()));
            AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext,
                    LocaleController.getString(R.string.ErrorOccurred), Toast.LENGTH_SHORT).show());
            return true;
        }

        final String path = file.getAbsolutePath();
        final String caption = messageObject.messageOwner.message;
        final ArrayList<TLRPC.MessageEntity> entities = messageObject.messageOwner.entities;
        final MlsRuntime.Forwarded from = forwardedFrom(messageObject);
        final TLRPC.MessageMedia media = messageObject.messageOwner.media;
        SendMessagesHelper.SendMessageParams fparams;
        if (media.photo instanceof TLRPC.TL_photo) {
            TLRPC.TL_photo photo = unsent((TLRPC.TL_photo) media.photo);
            // The upload reads a photograph by the name its largest size
            // carries, and a message restored after its echo can carry a name
            // the bytes were never stored under. Put them there, so the upload
            // finds what the bubble already shows.
            TLRPC.PhotoSize largest = photo.sizes.get(photo.sizes.size() - 1);
            File wanted = getFileLoader().getPathToAttach(largest);
            if (wanted != null && !wanted.exists()) {
                AndroidUtilities.copyFileSafe(file, wanted);
            }
            fparams = SendMessagesHelper.SendMessageParams.of(photo, path, did,
                    null, null, caption, entities, null, null, true, 0, 0, media.ttl_seconds,
                    null, false);
        } else if (media.document instanceof TLRPC.TL_document) {
            // The same document, not a plain file: the attributes are what say
            // a round video is round and a voice message has a length, and the
            // descriptor is written from them.
            fparams = SendMessagesHelper.SendMessageParams.of(unsent((TLRPC.TL_document) media.document), null, path,
                    did, null, null, caption, entities, null, null, true, 0, 0, media.ttl_seconds,
                    null, null, false);
        } else {
            AccountInstance account = AccountInstance.getInstance(currentAccount);
            SendMessagesHelper.prepareSendingDocument(account, path, path, null, caption,
                    messageObject.getDocument() == null ? null : messageObject.getDocument().mime_type,
                    did, null, null, null, null, null, true, 0, null, null, false);
            FileLog.d("mls: a file forwarded to " + did + " is sent again rather than copied, "
                    + "as a plain file with no author");
            return true;
        }
        fparams.mlsForwarded = from;
        fparams.payStars = payStars;
        fparams.monoForumPeer = monoForumPeerId;
        fparams.suggestionParams = suggestionParams;
        getSendMessagesHelper().sendMessage(fparams);
        FileLog.d("mls: a file forwarded to " + did + " is sent again rather than copied, "
                + (from == null ? "with no author" : "from " + from.id));
        return true;
    }

    /**
     * A send that was parked until its recording finished is started now.
     *
     * A round video in a conversation that encrypts gives up its head start:
     * nothing is uploaded while the file is still growing, so the message
     * sits in delayedMessages with notReadyYet on its videoEditedInfo. The
     * recorder calls this when the file is whole; the same re-entry the
     * convert pipeline uses then reads the finished file and uploads it
     * encrypted.
     */
    void wake(String location, Utilities.CallbackReturn<String, ArrayList<SendMessagesHelper.DelayedMessage>> takeParked,
              Utilities.Callback<SendMessagesHelper.DelayedMessage> send) {
        AndroidUtilities.runOnUIThread(() -> {
            ArrayList<SendMessagesHelper.DelayedMessage> arr = takeParked.run(location);
            FileLog.d("mls: waking " + (arr == null ? "no" : arr.size())
                    + " parked send(s) at " + location);
            if (arr == null) {
                return;
            }
            // Taken off the map before anything is re-entered: the send parks
            // itself again under the same key while it uploads, and walking
            // the live list while it grows is a loop that never ends.
            for (SendMessagesHelper.DelayedMessage message : arr) {
                if (message.obj != null && message.obj.videoEditedInfo != null) {
                    // The recorder's own copy of this flag was cleared, but the
                    // message carries a copy of a copy - and the caller is the
                    // one who knows the file is whole.
                    message.obj.videoEditedInfo.notReadyYet = false;
                }
                message.videoEditedInfo = null;
                send.run(message);
            }
        });
    }

    /**
     * The bytes of this message on this device, wherever they actually are.
     *
     * The message's own idea of where they live is tried first and is often
     * wrong for a message of ours: after the echo the sender's copy can name a
     * location the file was never stored under, while the bytes sit beside it
     * under the blob's name - both for a picture this device sent and for one
     * it received and decrypted. So the blob is asked too, and only when
     * nobody holds the bytes is the forward refused.
     */
    private File forwardableBytes(MessageObject messageObject) {
        File file = getFileLoader().getPathToMessage(messageObject.messageOwner);
        if (file != null && file.exists() && file.length() > 0) {
            return file;
        }
        file = getFileLoader().getPathToMessage(messageObject.messageOwner, true, true);
        if (file != null && file.exists() && file.length() > 0) {
            return file;
        }
        String path = messageObject.messageOwner.attachPath;
        if (path != null && !path.isEmpty()) {
            file = new File(path);
            if (file.exists() && file.length() > 0) {
                return file;
            }
        }
        TLRPC.MessageMedia media = messageObject.messageOwner.media;
        TLRPC.Document blob = null;
        if (media != null && media.photo instanceof TLRPCMls.TL_mls_photoEncrypted) {
            blob = ((TLRPCMls.TL_mls_photoEncrypted) media.photo).document;
        } else if (media != null && media.document != null) {
            blob = media.document;
        }
        if (blob != null) {
            file = getFileLoader().getPathToAttach(blob, true);
            if (file != null && file.exists() && file.length() > 0) {
                return file;
            }
            file = getFileLoader().getPathToAttach(blob, false);
            if (file != null && file.exists() && file.length() > 0) {
                return file;
            }
        }
        // A sent picture's bytes end up under the photograph's own id - the
        // name the echo's descriptor gives it - even when the message in the
        // chat still points at the name it had before the echo.
        if (media != null && media.photo != null && media.photo.id != 0) {
            TLRPC.TL_photoSize twin = new TLRPC.TL_photoSize();
            twin.type = "x";
            twin.location = new TLRPC.TL_fileLocationToBeDeprecated();
            twin.location.volume_id = media.photo.id;
            twin.location.local_id = media.photo.dc_id > 0 ? media.photo.dc_id : 1;
            file = getFileLoader().getPathToAttach(twin);
            if (file != null && file.exists() && file.length() > 0) {
                return file;
            }
            file = getFileLoader().getPathToAttach(twin, true);
            if (file != null && file.exists() && file.length() > 0) {
                return file;
            }
        }
        return null;
    }

    /**
     * A copy the server has never been told about, so the send uploads the
     * bytes again rather than pointing at the original blob - which is the
     * leak this path exists to close. The thumbnails stay behind too: they
     * name files this device may not hold, and the descriptor makes its own.
     */
    private static TLRPC.TL_photo unsent(TLRPC.TL_photo photo) {
        TLRPC.TL_photo copy = new TLRPC.TL_photo();
        copy.date = photo.date;
        copy.sizes.addAll(photo.sizes);
        copy.file_reference = new byte[0];
        return copy;
    }

    private static TLRPC.TL_document unsent(TLRPC.TL_document document) {
        TLRPC.TL_document copy = new TLRPC.TL_document();
        copy.date = document.date;
        copy.mime_type = document.mime_type;
        copy.size = document.size;
        copy.attributes.addAll(document.attributes);
        copy.file_reference = new byte[0];
        return copy;
    }

    /**
     * A file that went up encrypted and cannot be described yet - no
     * conversation so far, or one this phone waits to be let into - waits the
     * way a message does (#257), and goes from what is already uploaded once
     * the conversation is ready.
     */
    private void waitWithFile(SendMessagesHelper.DelayedMessage message, TLRPC.InputEncryptedFile uploaded,
                              byte[] key, byte[] iv, long decryptedSize, String path) {
        int id = message.obj.getId();
        // Whatever was sent after it and waits for it goes now, and waits on
        // its own if it has to.
        message.sendDelayedRequests();
        waiting.put(id, new Waiting(message.peer, () -> sendMedia(message, uploaded, key, iv, decryptedSize, path)));
        FileLog.d("mls: message " + id + " to " + message.peer
                + " waits until it can be encrypted, its file already up");
    }

    /**
     * Whether the bytes of this message have to be encrypted on the way up.
     *
     * True for a secret chat, which is what "sendRequest == null" meant at
     * these call sites before, and now also for a conversation of ours - and
     * the answer is remembered on the message, because what comes back from an
     * encrypted upload is a different shape and the other half of the send has
     * to know which one to expect.
     *
     * Asked for every kind of file rather than only for pictures. A photograph
     * was going up encrypted and a video, a voice message and a document were
     * going up in the clear, in the same conversation, with nothing said.
     *
     * Decided by whether the chat encrypts, not by whether a conversation
     * exists yet: the first file to somebody went up as a picture anybody could
     * open (#257). Without a conversation the file goes up encrypted all the
     * same, one is started beside the upload, and the file waits in sendMedia
     * until it can be described.
     */
    boolean uploadEncrypted(SendMessagesHelper.DelayedMessage message) {
        if (message.sendRequest == null) {
            return true;
        }
        MlsRuntime runtime = MlsRuntime.getInstance(currentAccount);
        message.mlsEncrypted = runtime.encrypts(message.peer);
        if (message.mlsEncrypted && !runtime.hasConversation(message.peer)) {
            runtime.ensureConversation(message.peer);
        }
        FileLog.d("mls: a file of type " + message.type + " for " + message.peer
                + " goes up " + (message.mlsEncrypted ? "encrypted" : "in the clear"));
        return message.mlsEncrypted;
    }

    /**
     * Sends a file whose bytes went up encrypted.
     *
     * What the upload answered with is the secret-chat shape, because that is
     * the only caller the encrypting upload has ever had. It describes the same
     * uploaded bytes an ordinary answer would, so the file is sent as a plain
     * document - one the server sees as noise with no type, no name and no size
     * that means anything - and everything that says what it really is goes
     * inside the message, encrypted to the two people in the conversation.
     */
    void sendMedia(SendMessagesHelper.DelayedMessage message, TLRPC.InputEncryptedFile uploaded,
                              byte[] key, byte[] iv, long decryptedSize, String path) {
        if (message.obj != null) {
            waiting.remove(message.obj.getId());
        }
        TLRPC.InputFile file = MlsMedia.asPlainFile(uploaded);
        TLRPCMls.TL_mls_media descriptor = message.obj == null ? null
                : MlsMedia.describe(message.obj.messageOwner, key, iv, decryptedSize, path);

        String caption = "";
        ArrayList<TLRPC.MessageEntity> entities = null;
        if (message.sendRequest instanceof TLRPC.TL_messages_sendMedia) {
            TLRPC.TL_messages_sendMedia sending = (TLRPC.TL_messages_sendMedia) message.sendRequest;
            caption = sending.message == null ? "" : sending.message;
            entities = sending.entities;
        } else if (message.sendRequest instanceof TLRPC.TL_messages_editMessage) {
            // A file replaced by an edit: the caption is the edit's, or the one
            // the message already has when the edit left it alone (#252).
            TLRPC.TL_messages_editMessage editing = (TLRPC.TL_messages_editMessage) message.sendRequest;
            if (editing.message != null) {
                caption = editing.message;
                entities = editing.entities;
            } else if (message.obj != null && message.obj.messageOwner.message != null) {
                caption = message.obj.messageOwner.message;
                entities = message.obj.messageOwner.entities;
            }
        } else {
            FileLog.e("mls: a file went up encrypted for a request of another kind, so it is not sent");
            message.markAsError();
            return;
        }

        String carried = file == null || descriptor == null ? null
                : MlsRuntime.getInstance(currentAccount)
                        .encrypt(message.peer, caption, entities, message.mlsForwarded, descriptor);
        if (carried == null) {
            if (file != null && descriptor != null && message.obj != null
                    && message.sendRequest instanceof TLRPC.TL_messages_sendMedia
                    && MlsRuntime.getInstance(currentAccount).encrypts(message.peer)) {
                waitWithFile(message, uploaded, key, iv, decryptedSize, path);
                return;
            }
            // The bytes are already up there and nobody can read them: the key
            // never left this device. Sending the document anyway would post
            // something that can never be opened by anyone, so the message
            // fails here instead - where a person can see it and send again.
            FileLog.e("mls: a file went up encrypted and could not be described, so it is not sent");
            message.markAsError();
            return;
        }

        if (message.sendRequest instanceof TLRPC.TL_messages_editMessage) {
            TLRPC.TL_messages_editMessage editing = (TLRPC.TL_messages_editMessage) message.sendRequest;
            editing.media = MlsMedia.asBlob(file);
            editing.message = carried;
            editing.entities = new ArrayList<>();
            editing.flags = (editing.flags | 2048 | 16384) & ~8;
            if (message.obj != null) {
                // An edit has no random id to be remembered under: its copy is
                // put back by the message it edits.
                MlsRuntime.getInstance(currentAccount).edited(message.obj.getDialogId(), message.obj.getId(),
                        caption, entities == null ? null : new ArrayList<>(entities), descriptor);
            }
        } else {
            TLRPC.TL_messages_sendMedia request = (TLRPC.TL_messages_sendMedia) message.sendRequest;
            request.media = MlsMedia.asBlob(file);
            request.message = carried;
            // An entity is a pair of offsets into the caption, and beside a
            // ciphertext they point at nothing. They travelled inside instead.
            request.entities.clear();
            request.flags &= ~8;
            if (message.obj != null) {
                // Remembered under the random id the server will echo back, so its
                // copy of this message does not replace the caption with base64.
                MlsRuntime.getInstance(currentAccount)
                        .wrote(message.obj.messageOwner.random_id, caption, descriptor);
            }
        }

        FileLog.d("mls: sending " + decryptedSize + " encrypted bytes to " + message.peer
                + " as " + descriptor.name + ", placeholder "
                + (descriptor.thumb == null ? 0 : descriptor.thumb.length) + " bytes");
        if (gathered(message)) {
            return;
        }
        getSendMessagesHelper().performSendMessageRequest(message.sendRequest, message.obj, message.originalPath,
                message, true, null, message.parentObject, null, message.scheduled);
    }
}

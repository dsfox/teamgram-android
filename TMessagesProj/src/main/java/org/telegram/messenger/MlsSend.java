package org.telegram.messenger;

import android.widget.Toast;

import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.TLRPCMls;

import java.io.File;
import java.util.ArrayList;

/**
 * Sending into a conversation that encrypts: text encrypted once there is a
 * conversation, files encrypted on the way up and described inside the
 * ciphertext, forwards of files sent again rather than copied. Kept out of
 * SendMessagesHelper, which upstream rewrites at every release, and reached
 * from it by one call at each place it begins (#196).
 */
final class MlsSend extends BaseController {

    private static final MlsSend[] Instance = new MlsSend[UserConfig.MAX_ACCOUNT_COUNT];

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
     * waits; what waits is the ciphertext, and only the first time to each
     * person. The callback always runs - no conversation possible, a handshake
     * that failed, ten seconds gone - and then the message goes in the clear
     * exactly as it did before.
     */
    void encryptThenSend(long peer, TLRPC.TL_messages_sendMessage sending, long randomId, String plain,
                         ArrayList<TLRPC.MessageEntity> entities, MlsRuntime.Forwarded forwarded, Runnable send) {
        MlsRuntime.getInstance(currentAccount).ensureConversation(peer, () -> {
            String carried = MlsRuntime.getInstance(currentAccount).encrypt(peer, plain, entities, forwarded);
            if (carried != null) {
                // Remembered under the same random id the server will echo
                // back, so its copy does not replace what was written with
                // base64.
                MlsRuntime.getInstance(currentAccount).wrote(randomId, plain);
                sending.message = carried;
                // The formatting travels inside the ciphertext, so it must not
                // travel beside it as well.
                sending.entities = null;
                sending.flags &= ~8;
            }
            send.run();
        });
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
     */
    boolean uploadEncrypted(SendMessagesHelper.DelayedMessage message) {
        if (message.sendRequest == null) {
            return true;
        }
        message.mlsEncrypted = MlsRuntime.getInstance(currentAccount)
                .hasConversation(message.peer);
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
        TLRPC.InputFile file = MlsMedia.asPlainFile(uploaded);
        TLRPCMls.TL_mls_media descriptor = message.obj == null ? null
                : MlsMedia.describe(message.obj.messageOwner, key, iv, decryptedSize, path);

        String caption = "";
        ArrayList<TLRPC.MessageEntity> entities = null;
        if (message.sendRequest instanceof TLRPC.TL_messages_sendMedia) {
            TLRPC.TL_messages_sendMedia sending = (TLRPC.TL_messages_sendMedia) message.sendRequest;
            caption = sending.message == null ? "" : sending.message;
            entities = sending.entities;
        }

        String carried = file == null || descriptor == null ? null
                : MlsRuntime.getInstance(currentAccount)
                        .encrypt(message.peer, caption, entities, message.mlsForwarded, descriptor);
        if (carried == null) {
            // The bytes are already up there and nobody can read them: the key
            // never left this device. Sending the document anyway would post
            // something that can never be opened by anyone, so the message
            // fails here instead - where a person can see it and send again.
            FileLog.e("mls: a file went up encrypted and could not be described, so it is not sent");
            message.markAsError();
            return;
        }

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

        FileLog.d("mls: sending " + decryptedSize + " encrypted bytes to " + message.peer
                + " as " + descriptor.name + ", placeholder "
                + (descriptor.thumb == null ? 0 : descriptor.thumb.length) + " bytes");
        getSendMessagesHelper().performSendMessageRequest(message.sendRequest, message.obj, message.originalPath,
                message, true, null, message.parentObject, null, message.scheduled);
    }
}

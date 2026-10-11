package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * Receiving in conversations that encrypt: what MessagesController carried for
 * the encryption, kept out of it - upstream rewrites it at every release - and
 * reached from it by one call where each route begins (#196). Every route a
 * message arrives by opens it here: updates, history, the dialog list, and the
 * difference after a restart.
 */
final class MlsReceive extends BaseController {

    private static final MlsReceive[] Instance = new MlsReceive[UserConfig.MAX_ACCOUNT_COUNT];

    static MlsReceive getInstance(int num) {
        MlsReceive local = Instance[num];
        if (local == null) {
            synchronized (MlsReceive.class) {
                local = Instance[num];
                if (local == null) {
                    Instance[num] = local = new MlsReceive(num);
                }
            }
        }
        return local;
    }

    private MlsReceive(int num) {
        super(num);
    }

    /** At every app config load: keys, the boxes, and the recovery phrase. */
    void onAppConfig() {
    // Publishing a key package is a promise: encrypt to me, I can read it.
    // It was left out while this client had the crypto and no receive path,
    // because a false promise is worse than silence - an iPhone would
    // encrypt to a device that could never read a single message, and the
    // person here would see `mls1:...` for ever instead of merely an
    // unprotected message.
    //
    // Both halves are wired now, so the promise is one this client keeps.
    MlsKeyPackages.getInstance(currentAccount).publish();

    // And the invitations waiting on the server, which are what let this
    // device into conversations somebody else began. Asked for at start
    // rather than only when a message arrives, because a welcome sent while
    // this phone was off has nothing to arrive behind.
    MlsRuntime.getInstance(currentAccount).collectWelcomes();
    MlsRuntime.getInstance(currentAccount).collectCommits();

    // And the six words that get this account back when the phone is gone.
    // Made on the device: the server is told a one-way derivation of them
    // and never the words themselves.
    MlsRecoveryPhrase.getInstance(currentAccount).ensure();
    }

    /** The server says there is a commit or a welcome waiting. */
    void mailbox() {
        // Fetch both boxes: the two travel by different routes and a change of
        // membership can leave one of each. This is what used to be a poll on a
        // ladder of delays (#156).
        MlsRuntime.getInstance(currentAccount).collectWelcomes();
        MlsRuntime.getInstance(currentAccount).collectCommits();
    }

    /** A loaded page of history, opened before anything else reads it. */
    void openLoaded(TLRPC.messages_Messages messagesRes, long dialogId, boolean isCache) {
    // History goes through the same door as an update does. Without this a
    // message read after a restart, or re-read once the welcome that lets
    // this device in has arrived, stays a ciphertext for ever - and it is
    // exactly the re-read that matters, because a message and its welcome
    // travel by different routes and the message often wins.
    MlsRuntime mls = MlsRuntime.getInstance(currentAccount);
    boolean anyLocked = false;
    ArrayList<TLRPC.Message> opened = null;
    for (int i = 0; i < messagesRes.messages.size(); i++) {
        TLRPC.Message message = messagesRes.messages.get(i);
        if (!MlsRuntime.isCiphertext(message.message)) {
            continue;
        }
        if (mls.open(message)) {
            if (opened == null) {
                opened = new ArrayList<>();
            }
            opened.add(message);
        } else {
            anyLocked = true;
        }
    }
    if (opened != null) {
        // Written back at once, and whether this came from the cache or the
        // server. MLS opens a message exactly once: the key moves on as it
        // is used, and the same ciphertext handed back a second time is
        // refused with UnableToDecrypt. So the plaintext has to be kept the
        // moment it exists.
        //
        // It was kept only for loads from the server at first, and the one
        // successful reading happened during a load from the cache - so the
        // words were shown once, thrown away, and the message was then
        // unreadable for good. That is the whole cost of getting this wrong:
        // not a slow path, a lost message.
        FileLog.d("mls: opened " + opened.size() + " stored messages in " + dialogId
                + (isCache ? " from the cache" : " from the server"));
        getMessagesStorage().putMessages(opened, true, true, false, 0, 0, 0);

        // And to the chat somebody is looking at. The load that opened
        // these was asked for by the repair under a guid of its own, so
        // nothing was listening for the answer: they went into storage and
        // the screen went on showing what it had (#109).
        //
        // Replaced rather than added, and the difference is the whole fix.
        // A message that arrives and will not open is drawn straight away,
        // as a lock - only a load from storage hides those. So the chat
        // already holds it, by that id, and handing it over as new is
        // dropped on sight by the check that stops a message appearing
        // twice. What it needs is the same id with words in it.
        final ArrayList<TLRPC.Message> justOpened = new ArrayList<>(opened);
        AndroidUtilities.runOnUIThread(() -> {
            ArrayList<MessageObject> drawable = new ArrayList<>(justOpened.size());
            for (int i = 0; i < justOpened.size(); i++) {
                drawable.add(new MessageObject(currentAccount, justOpened.get(i), true, true));
            }
            FileLog.d("mls: handing " + drawable.size() + " opened messages to "
                    + dialogId + " to be drawn");
            getNotificationCenter().postNotificationName(
                    NotificationCenter.replaceMessagesObjects, dialogId, drawable);
            // And as new as well, for the ones the chat never had - a
            // message hidden by a load from storage was never given to it,
            // and replacing something that is not there does nothing.
            getNotificationCenter().postNotificationName(
                    NotificationCenter.didReceiveNewMessages, dialogId, drawable, false, 0);
        });

        // And the copy the chat list is holding in memory, which is a third
        // one and the one a person actually looks at. Storage alone leaves
        // the row saying mls1:... until something else happens to rebuild
        // it, which may be never.
        ArrayList<MessageObject> shownInList = getMessagesController().dialogMessage.get(dialogId);
        if (shownInList != null) {
            boolean changed = false;
            for (int i = 0; i < shownInList.size(); i++) {
                MessageObject shown = shownInList.get(i);
                if (shown == null) {
                    continue;
                }
                for (int j = 0; j < opened.size(); j++) {
                    if (opened.get(j).id == shown.getId()) {
                        shown.messageOwner.message = opened.get(j).message;
                        shown.messageOwner.entities = opened.get(j).entities;
                        shown.generateCaption();
                        shown.setType();
                        changed = true;
                    }
                }
            }
            if (changed) {
                AndroidUtilities.runOnUIThread(() ->
                        getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload));
            }
        }
    }
    if (anyLocked) {
        // Something here cannot be opened yet, and what would open it - an
        // invitation, or a membership change this device has not applied -
        // may be waiting on the server. Named by this chat, because a
        // conversation just joined is written down against whoever sent the
        // invitation, and reading again by that name reloads the wrong
        // dialog while this one stays locked (#40).
        mls.catchUp(dialogId);
    }
    }

    /** The top message of a dialog, which arrives by the dialog list's own route (#86). */
    void openTop(TLRPC.Message message) {
        // The dialog list arrives by its own route and used to miss this
        // entirely, so the row read `mls1:AAEAAh...` while the chat
        // behind it read a lock: history goes through
        // processLoadedMessages, which does open, and the top message of
        // a dialog does not. It shows on a device signing in to an
        // account that already has conversations, because the dialogs
        // land before any history does (#86).
        //
        // Safe to call from here as well: open() asks what it has
        // already opened before it asks MLS, and MLS opens a ciphertext
        // exactly once.
        if (MlsRuntime.isCiphertext(message.message)) {
            boolean read = MlsRuntime.getInstance(currentAccount).open(message);
            // Said out loud, because it cannot be checked any other way.
            // The row is drawn on a canvas, so no dump can see it, and
            // the ciphertext of a message this device sent is kept on
            // purpose - so the bytes in the database cannot tell the two
            // apart either. The log can: this line means a top message
            // arrived as a ciphertext and did not stay one.
            // Without the dialog id: it is not filled in yet on this
            // path, so printing it said "in 0" for every message and
            // would have sent somebody looking for dialog zero.
            FileLog.d("mls: dialog top message " + (read ? "opened" : "locked")
                    + ", id " + message.id);
        }
    }

    /** A message that came as an update, opened before anything stores or shows it. */
    void openOrCatchUp(TLRPC.Message message) {
        // Opened here, before anything stores it or shows it, because
        // the text is read by more than the chat: the chat list shows
        // the last one, search indexes it, a notification quotes it.
        // Decrypting at each of those would be four chances to miss one.
        //
        // One that cannot be opened is left exactly as it is - see
        // MlsRuntime.open - and asked for again once the welcome that
        // lets this device into the conversation has arrived.
        if (MlsRuntime.isCiphertext(message.message)) {
            if (!MlsRuntime.getInstance(currentAccount).open(message)) {
                MlsRuntime.getInstance(currentAccount)
                        .catchUp(MessageObject.getDialogId(message));
            }
        }
    }

    // ice9 #157: a difference holding a message that will not open waits for the
    // commits waiting in the box; any difference after it waits its turn, so the
    // state each one sets is never written out of order.
    private boolean differenceWaitsForCommits;
    private final ArrayList<Runnable> differencesWaiting = new ArrayList<>();

    /**
     * Applies a difference once its messages are open - on the stage queue, in
     * the order the differences came in.
     *
     * Everything that arrives while the app was not running comes through
     * here, as a list of messages rather than as updates - so the hook on
     * updateNewMessage never saw any of it, and every message received while
     * the phone was closed stayed a ciphertext. A phone back from offline
     * learns its messages from here, and this never carries #156's wake-up: a
     * message of a new epoch can be here before the commit that opens it,
     * waiting in the box. Stored as it was, it stood as a lock until a
     * catch-up read it back. So whatever opened is kept as it is - applying the
     * commits first would lose the older epochs' messages, since the core keeps
     * only two - and only what would not open waits for the commits and is
     * opened once more.
     */
    void whenDifferenceOpens(TLRPC.updates_Difference res, Runnable apply) {
        Runnable opening = () -> open(res, apply);
        if (differenceWaitsForCommits) {
            differencesWaiting.add(opening);
            return;
        }
        opening.run();
    }

    private void open(TLRPC.updates_Difference res, Runnable apply) {
        ArrayList<TLRPC.Message> locked = new ArrayList<>();
        MlsRuntime mls = MlsRuntime.getInstance(currentAccount);
        for (int a = 0; a < res.new_messages.size(); a++) {
            TLRPC.Message message = res.new_messages.get(a);
            if (MlsRuntime.isCiphertext(message.message) && !mls.open(message)) {
                locked.add(message);
            }
        }
        if (locked.isEmpty()) {
            apply.run();
            return;
        }
        differenceWaitsForCommits = true;
        FileLog.d("mls: " + locked.size() + " message(s) of a difference would not open; catching up on commits before storing it");
        mls.catchUpThen(() -> {
            int still = 0;
            for (TLRPC.Message message : locked) {
                if (!mls.open(message)) {
                    still++;
                }
            }
            FileLog.d("mls: caught up; " + still + " of " + locked.size() + " still locked");
            apply.run();
            differenceWaitsForCommits = false;
            while (!differenceWaitsForCommits && !differencesWaiting.isEmpty()) {
                differencesWaiting.remove(0).run();
            }
        });
    }
}

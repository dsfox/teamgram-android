package org.telegram.ui.Components;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

/**
 * Whether a typed number belongs to somebody on ice9 (#164). A contact of
 * ours is answered from the cache; anybody else is asked of the server, which
 * says PHONE_NOT_OCCUPIED for a stranger - the signal to invite them. The
 * address book is never read: this is how a number becomes a row without
 * the contacts permission.
 */
public final class NumberLookup {

    private NumberLookup() {
    }

    /** onUser runs on the UI thread, with null for a number nobody on ice9 holds. */
    public static void resolve(int account, String digits, Utilities.Callback<TLRPC.User> onUser) {
        final TLRPC.TL_contact contact = ContactsController.getInstance(account).contactsByPhone.get(digits);
        if (contact != null) {
            final TLRPC.User user = MessagesController.getInstance(account).getUser(contact.user_id);
            if (user != null) {
                onUser.run(user);
            } else {
                MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() -> {
                    final TLRPC.User stored = MessagesStorage.getInstance(account).getUser(contact.user_id);
                    AndroidUtilities.runOnUIThread(() -> onUser.run(stored));
                });
            }
            return;
        }
        final TLRPC.TL_contacts_resolvePhone req = new TLRPC.TL_contacts_resolvePhone();
        req.phone = digits;
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            TLRPC.User user = null;
            if (res instanceof TLRPC.TL_contacts_resolvedPeer) {
                final TLRPC.TL_contacts_resolvedPeer r = (TLRPC.TL_contacts_resolvedPeer) res;
                MessagesController.getInstance(account).putUsers(r.users, false);
                MessagesController.getInstance(account).putChats(r.chats, false);
                long did = DialogObject.getPeerDialogId(r.peer);
                if (did >= 0) {
                    user = MessagesController.getInstance(account).getUser(did);
                }
            }
            onUser.run(user);
        }));
    }
}

package org.telegram.ui.Adapters;

import android.text.TextUtils;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MlsRuntime;
import org.telegram.messenger.MlsStorage;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * What the server cannot read, looked for on the phone (#108).
 *
 * A message in a conversation that encrypts reaches the server as
 * ciphertext, so messages.searchGlobal cannot match a word in one and never
 * will. The plaintext is here, in messages_v2, and until this nothing asked
 * it: the chat list showed "a message from before" while a search for
 * "before" came back empty.
 *
 * Kept in its own list rather than written straight into the results,
 * because the server's reply clears those when it arrives - whichever of
 * the two lands first, the other must still be able to put its own back.
 * Kept out of DialogsSearchAdapter, which upstream rewrites, and reached from
 * it by a few calls (#196).
 */
public final class MlsSearchResults {

    private final ArrayList<MessageObject> found = new ArrayList<>();
    private String foundFor;

    /**
     * Searches this phone's encrypted conversations. stillCurrent is asked
     * when the results arrive, on the UI thread; arrived runs only if it says
     * yes.
     */
    public void search(int account, String query, Utilities.Callback0Return<Boolean> stillCurrent, Runnable arrived) {
        found.clear();
        foundFor = null;
        final MlsRuntime mls = MlsRuntime.getInstance(account);
        MlsStorage.of(account).searchEncryptedMessages(
            query, mls.encryptedPeerIds(), 20, (messages, users, chats) -> {
                if (!stillCurrent.run()) {
                    return;
                }
                MessagesController.getInstance(account).putUsers(users, true);
                MessagesController.getInstance(account).putChats(chats, true);
                LongSparseArray<TLRPC.User> usersMap = new LongSparseArray<>();
                for (int a = 0; a < users.size(); a++) {
                    usersMap.put(users.get(a).id, users.get(a));
                }
                LongSparseArray<TLRPC.Chat> chatsMap = new LongSparseArray<>();
                for (int a = 0; a < chats.size(); a++) {
                    chatsMap.put(chats.get(a).id, chats.get(a));
                }
                found.clear();
                for (int a = 0; a < messages.size(); a++) {
                    MessageObject messageObject = new MessageObject(
                        account, messages.get(a), usersMap, chatsMap, false, true);
                    messageObject.setQuery(query);
                    found.add(messageObject);
                }
                foundFor = query;
                arrived.run();
            });
    }

    /**
     * Puts the phone's own hits in front of the server's, once.
     *
     * Returns whether anything moved, so a caller can decide whether the list
     * is worth redrawing.
     */
    public boolean mergeInto(ArrayList<MessageObject> results, String currentQuery) {
        if (found.isEmpty() || !TextUtils.equals(foundFor, currentQuery)) {
            return false;
        }
        int at = 0;
        for (int a = 0; a < found.size(); a++) {
            MessageObject mine = found.get(a);
            boolean already = false;
            for (int i = 0; i < results.size(); i++) {
                MessageObject theirs = results.get(i);
                if (theirs != null && theirs.getId() == mine.getId()
                        && theirs.getDialogId() == mine.getDialogId()) {
                    already = true;
                    break;
                }
            }
            if (!already) {
                results.add(at++, mine);
            }
        }
        return at > 0;
    }
}

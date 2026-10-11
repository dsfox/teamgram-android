package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * A chat's membership, followed into its MLS group: removals and joins learnt
 * from the service message every member gets (#40, #110). Kept out of
 * MessagesController and reached from it by one call each (#196).
 */
final class MlsMembership extends BaseController {

    private static final MlsMembership[] Instance = new MlsMembership[UserConfig.MAX_ACCOUNT_COUNT];

    static MlsMembership getInstance(int num) {
        MlsMembership local = Instance[num];
        if (local == null) {
            synchronized (MlsMembership.class) {
                local = Instance[num];
                if (local == null) {
                    Instance[num] = local = new MlsMembership(num);
                }
            }
        }
        return local;
    }

    private MlsMembership(int num) {
        super(num);
    }

    /** A member left or was removed: called from inside the chat-delete-user branch. */
    void removed(TLRPC.Message message) {
        // Out of the chat means out of the MLS group, or the removal
        // is only a line in an interface: a device left in the group
        // goes on reading everything said afterwards (#40).
        //
        // Hung on the service message, which is the one thing every
        // member gets. The button was the obvious place and was
        // wrong twice over: deleteParticipantFromChat has two
        // separate implementations and the member list uses the
        // other one, and the update naming the person who left goes
        // only to whoever pressed it - everybody else learns from
        // this message and nothing else.
        //
        // So every member tries, and that is deliberate. Removing
        // costs nothing: it is local, one commit takes the epoch,
        // and the rest then find the person already gone. Adding is
        // hooked at the action instead, because claiming a key
        // package spends one, and a sweep already catches the misses.
        if (message.peer_id != null && message.peer_id.chat_id != 0) {
            MlsRuntime.getInstance(currentAccount)
                    .memberRemoved(-message.peer_id.chat_id, message.action.user_id);
        }
    }

    /** Somebody joined, by being added, by a link or from a request. */
    void joined(TLRPC.Message message) {
        // Somebody joined, and if it was us there is an invitation
        // waiting on the server this second. Nothing else goes and
        // fetches it promptly: a phone sat three minutes in a chat it
        // had just been added to, with the welcome there the whole time,
        // until an unrelated four-minute check happened to run (#110).
        //
        // The same message every member gets, like the removal above.
        //
        // All three ways in, not just the one somebody presses. A person
        // who follows a link or is let in from a request joins without
        // anybody choosing them, so no device is running the addition -
        // they are let into the conversation by whoever next compares it
        // with the chat, and until they fetch that welcome they sit in a
        // group where nothing appears (#40, step 4.3).
        if ((message.action instanceof TLRPC.TL_messageActionChatAddUser
                || message.action instanceof TLRPC.TL_messageActionChatJoinedByLink
                || message.action instanceof TLRPC.TL_messageActionChatJoinedByRequest)
                && message.peer_id != null && message.peer_id.chat_id != 0) {
            MlsRuntime.getInstance(currentAccount).invited();
            // And the group has somebody in it that the conversation
            // does not. Named, like the removal above, and for the same
            // reason turned round: this message arrives before the
            // participant list here catches up, so comparing the two
            // finds nobody missing and the newcomer waits for a sweep -
            // which is what happened to the first person who followed a
            // link into an encrypted group and sat in silence (#40, 4.3).
            ArrayList<Long> newcomers = new ArrayList<>();
            if (message.action.users != null && !message.action.users.isEmpty()) {
                newcomers.addAll(message.action.users);
            } else if (message.from_id instanceof TLRPC.TL_peerUser) {
                // A link or a request is followed by one person alone,
                // and the message they cause is from them.
                newcomers.add(message.from_id.user_id);
            }
            MlsRuntime.getInstance(currentAccount)
                    .memberAdded(-message.peer_id.chat_id, newcomers);
        }
    }
}

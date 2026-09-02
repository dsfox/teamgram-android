package org.telegram.ui.Components;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Intent;
import android.net.Uri;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPCInvite;
import org.telegram.ui.ActionBar.BaseFragment;

/**
 * The one place an invitation SMS is composed from (#47).
 *
 * Four screens can invite a number - Invite Contacts, a phone-book row on the
 * Contacts tab, a number tapped in a message, and the "not registered yet"
 * dialog - and an SMS without a code is useless, because the code is what
 * lets that number in. So every one of them comes here: a code bound to the
 * number is minted first, and the phone's own SMS app then carries it over
 * the inviter's carrier, which is what makes "I vouch for this person" true.
 * A refusal is said in words rather than swallowed, and the composer never
 * opens without a code.
 */
public final class InvitationComposer {

    private InvitationComposer() {
    }

    /**
     * Mints a code for the number and opens the SMS app to it. afterComposing
     * runs once the SMS app has been asked to open, and not on a refusal - the
     * screen that asked is where the refusal is shown.
     */
    public static void invite(BaseFragment fragment, String phone, Runnable afterComposing) {
        final int account = fragment.getCurrentAccount();
        TLRPCInvite.TL_invite_mint ask = new TLRPCInvite.TL_invite_mint();
        ask.phone = phone;
        ConnectionsManager.getInstance(account).sendRequest(ask, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null || !(response instanceof TLRPCInvite.TL_invite_minted)) {
                boolean here = error != null && error.text != null && error.text.contains("PHONE_ALREADY_HERE");
                FileLog.d("invite: no code for the contact - " + (error != null ? error.text : "wrong answer"));
                BulletinFactory.of(fragment)
                        .createSimpleBulletin(R.raw.error, getString(here ? R.string.InviteAlreadyHere : R.string.InviteNoCode))
                        .show();
                return;
            }
            String code = ((TLRPCInvite.TL_invite_minted) response).code;
            String body = ContactsController.getInstance(account).getInviteText(1)
                    + "\n" + LocaleController.formatString(R.string.InviteCodeLine, code);
            // The walk reads this line: one number, and the code is in the body.
            FileLog.d("invite: composing an SMS to one number with code " + code);
            if (fragment.getParentActivity() == null) {
                FileLog.d("invite: the screen that asked is gone, nothing to compose in");
                return;
            }
            try {
                Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + phone));
                intent.putExtra("sms_body", body);
                fragment.getParentActivity().startActivityForResult(intent, 500);
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (afterComposing != null) {
                afterComposing.run();
            }
        }));
    }
}

package org.telegram.ui;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

/**
 * A key none of this account's phones made appeared in the key log (#44).
 *
 * That is how a device the server put into this account shows itself: the
 * server can log a key of its own under this account's name, and then it is in
 * the log for good, where the account's own phones read it. The person is told
 * at once, with what to do; a phone of their own that they just signed in
 * explains it, and nothing else does.
 */
public final class Ice9NewDeviceKey {

    private Ice9NewDeviceKey() {
    }

    public static void tell() {
        tell(0);
    }

    /**
     * Shown once the screen will take it: a window asked for while the first
     * screen is still sliding in is dropped without a word (BaseFragment
     * refuses during a transition), and the warning was lost that way on the
     * first walk. Asked again every second for half a minute, and the log says
     * which happened.
     */
    private static void tell(int attempt) {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment != null && fragment.getParentActivity() != null) {
            AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
            builder.setTitle(LocaleController.getString(R.string.Ice9NewDeviceKeyTitle));
            builder.setMessage(LocaleController.getString(R.string.Ice9NewDeviceKeyInfo));
            builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
            if (fragment.showDialog(builder.create()) != null) {
                FileLog.d("mls: told the person a key of a new device appeared in their account");
                return;
            }
        }
        if (attempt >= 30) {
            FileLog.e("mls: a new device's key could not be told: no screen took the window");
            return;
        }
        AndroidUtilities.runOnUIThread(() -> tell(attempt + 1), 1000);
    }
}

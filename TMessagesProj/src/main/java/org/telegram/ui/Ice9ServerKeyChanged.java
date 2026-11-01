package org.telegram.ui;

import android.text.InputType;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.ServerAddress;
import org.telegram.tgnet.ServerCode;
import org.telegram.tgnet.ServerKey;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.AlertsCreator;

/**
 * A server of one's own that stopped offering the key this phone holds (#245).
 *
 * Before, the phone said "Connecting" for good and nothing else. Now it says the
 * server has a new key and takes the new code from its owner without a sign-out:
 * the key is fetched, kept only if its code is the one typed, and the next
 * handshake uses it. The code is the whole defence against somebody posing as
 * the server, so the window says not to type one that did not come from the
 * owner. Asked once a launch; a wrong code lets it ask again.
 */
public final class Ice9ServerKeyChanged {

    private static boolean asked;

    private Ice9ServerKeyChanged() {
    }

    public static void ask() {
        if (asked || ServerAddress.keyDer() == null) {
            return;
        }
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        asked = true;
        FileLog.d("server: the server does not offer the key this phone holds; asking for its new code");
        AlertsCreator.createSimpleTextInputAlert(fragment.getParentActivity(), fragment,
                LocaleController.getString(R.string.Ice9ServerKeyChangedTitle),
                LocaleController.getString(R.string.Ice9ServerKeyChangedInfo),
                LocaleController.getString(R.string.Ice9ServerKeyChangedHint), null, 40,
                LocaleController.getString(R.string.Ice9ServerKeyChangedCheck), InputType.TYPE_CLASS_NUMBER,
                fragment.getResourceProvider(), Ice9ServerKeyChanged::check);
    }

    private static void check(String typed) {
        final String code = ServerCode.digits(typed);
        if (code == null || code.isEmpty()) {
            refuse(R.string.Ice9ServerCodeMalformed, "a malformed code");
            return;
        }
        final String host = ServerAddress.host();
        final int port = ServerAddress.port();
        final String dialable = ServerAddress.dialable();
        Utilities.globalQueue.postRunnable(() -> {
            ServerKey.Answer answer = ServerKey.fetch(dialable, host, port);
            AndroidUtilities.runOnUIThread(() -> {
                if (answer.kind == ServerKey.Kind.UNREACHABLE) {
                    refuse(R.string.Ice9ServerNoAnswer, "nothing answered");
                } else if (answer.kind == ServerKey.Kind.NO_KEY) {
                    refuse(R.string.Ice9ServerNoKey, "no key");
                } else if (!code.equals(ServerCode.code(answer.der))) {
                    refuse(R.string.Ice9ServerCodeMismatch, "a code that is not the server's");
                } else {
                    ServerAddress.replaceKey(answer.der);
                    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                        ConnectionsManager.native_setSeedAddress(a, dialable, port, ServerAddress.publicKeyPem());
                    }
                    FileLog.d("server: kept the server's new key with the code " + code);
                    asked = false;
                }
            });
        });
    }

    private static void refuse(int text, String why) {
        FileLog.d("server: the new code was refused: " + why);
        Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString(text), Toast.LENGTH_LONG).show();
        asked = false;
    }
}

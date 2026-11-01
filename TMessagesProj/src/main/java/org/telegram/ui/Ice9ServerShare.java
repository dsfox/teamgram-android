package org.telegram.ui;

import android.app.Activity;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.ServerAddress;
import org.telegram.tgnet.ServerCode;
import org.telegram.ui.Components.QRCodeBottomSheet;

/**
 * Handing a server of one's own on from the phone (#245), so a member does not
 * need the owner's terminal: the link the installer prints, as a QR code and as
 * text to send. Through ice9.ru when the app speaks Russian, since i.ice9.app is
 * often unreachable on Russian networks.
 */
public final class Ice9ServerShare {

    private Ice9ServerShare() {
    }

    public static String link() {
        String language = LocaleController.getInstance().getCurrentLocale().getLanguage();
        return ServerCode.Link.make(ServerAddress.host(), ServerAddress.port(), ServerAddress.code(), "ru".equals(language));
    }

    public static void show(Activity activity) {
        if (activity == null || ServerAddress.isOurs()) {
            return;
        }
        String link = link();
        FileLog.d("server: sharing the server as " + link);
        new QRCodeBottomSheet(activity, LocaleController.getString(R.string.Ice9ServerShare), link,
                LocaleController.getString(R.string.Ice9ServerShareInfo), true).show();
    }
}

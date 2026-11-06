package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * When a number of the address book that nobody in ice9 held is asked about
 * again (#297). The twin of Ice9ContactReask.swift.
 *
 * The phone sends each number to contacts.importContacts once, and the server
 * keeps only the numbers of people already here - that is the App Store
 * promise. So a person who signs up later stays nobody's contact: the server
 * cannot tell anybody, and the phone never asks again. Asking again once a day
 * is what makes them a contact, and what the Contacts tab's search finds.
 *
 * The time it last asked is a file of its own, one per account, so a walk can
 * make the next launch ask by removing it.
 */
public final class Ice9ContactReask {

    static final long INTERVAL_MS = 24L * 60 * 60 * 1000;

    private Ice9ContactReask() {
    }

    static File file(int account) {
        return new File(ApplicationLoader.getFilesDirFixed(), "ice9-contacts-asked-" + account);
    }

    /**
     * Whether a day has passed since this account last asked; when it has, this
     * round is the one that asks, and the time is written down now - whether or
     * not anybody turns out to be new, so a phone with nobody to ask about does
     * not ask on every change of the address book.
     */
    public static synchronized boolean take(int account) {
        File file = file(account);
        long now = System.currentTimeMillis();
        try {
            if (file.exists()) {
                long last = Long.parseLong(new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim());
                long since = now - last;
                if (since >= 0 && since < INTERVAL_MS) {
                    return false;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(Long.toString(now).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            FileLog.e(e);
        }
        FileLog.d("contacts: asking again about the numbers nobody held");
        return true;
    }

    /** The import of one number of an address-book entry, as upstream builds it. */
    static TLRPC.TL_inputPhoneContact input(ContactsController.Contact value, int a) {
        TLRPC.TL_inputPhoneContact imp = new TLRPC.TL_inputPhoneContact();
        imp.client_id = value.contact_id;
        imp.client_id |= ((long) a) << 32;
        imp.first_name = value.first_name;
        imp.last_name = value.last_name;
        imp.phone = value.phones.get(a);
        return imp;
    }
}

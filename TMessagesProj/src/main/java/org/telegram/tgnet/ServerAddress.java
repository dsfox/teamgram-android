package org.telegram.tgnet;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Which server this phone talks to (ice9 #65).
 *
 * A messenger that promises "your server" and compiles ours into the app is
 * promising nothing, and it makes us the one point everybody has to trust. So
 * the address is a thing a person types, ours is only what the field is filled
 * in with, and this class is where the answer is kept.
 *
 * Not per account, on purpose: it is needed before an account exists, at the
 * very first launch, and every account on this device reaches the same server.
 *
 * What it holds is only the <i>seed</i>. Once the client has connected it keeps
 * its own address list, refreshed by help.getConfig, and that list is what it
 * dials from then on. Changing what is kept here therefore does nothing on its
 * own — {@link ConnectionsManager#reseedFromAddress()} is what makes it take.
 */
public class ServerAddress {

    // Ours, offered as the default. A name rather than an address: an address
    // baked into a released build and later given up left phones rotating onto
    // a dead endpoint, showing "Connecting" every other minute, with no way to
    // reach the ones already installed. A name can be pointed elsewhere.
    public static final String DEFAULT_HOST = "common.ice9.app";
    public static final int DEFAULT_PORT = 10443;

    private static final String PREFS = "serveraddress";
    private static final String HOST = "host";
    private static final String PORT = "port";
    private static final String CHOSEN = "chosen";

    private ServerAddress() {
    }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String host() {
        try {
            String host = preferences().getString(HOST, DEFAULT_HOST);
            return host == null || host.isEmpty() ? DEFAULT_HOST : host;
        } catch (Throwable ignore) {
            // Before the application context exists there is nowhere to read
            // from, and the answer is the default anyway.
            return DEFAULT_HOST;
        }
    }

    public static int port() {
        try {
            int port = preferences().getInt(PORT, DEFAULT_PORT);
            return port > 0 && port <= 65535 ? port : DEFAULT_PORT;
        } catch (Throwable ignore) {
            return DEFAULT_PORT;
        }
    }

    /** True when this phone is talking to somebody else's server. */
    public static boolean isOurs() {
        return DEFAULT_HOST.equals(host()) && DEFAULT_PORT == port();
    }

    /** "name" when the port is the usual one, "name:port" when it is not. */
    public static String describe() {
        return port() == DEFAULT_PORT ? host() : host() + ":" + port();
    }

    /**
     * Keeps an address. Says nothing about whether it answers: that is checked
     * before this is called, because an address that does not answer turns into
     * a phone stuck on "Connecting" with no way back to it.
     */
    public static void set(String host, int port) {
        preferences().edit()
                .putString(HOST, host == null || host.isEmpty() ? DEFAULT_HOST : host)
                .putInt(PORT, port > 0 && port <= 65535 ? port : DEFAULT_PORT)
                .apply();
    }

    /**
     * Whether anybody has answered the question yet. False on a fresh install
     * and true ever after, which is what decides whether the first screen is
     * shown — asking again at every sign-in would be a question with the same
     * answer every time. Note that this is not "is it ours": somebody who
     * looked at the screen and kept the default has still chosen.
     */
    public static boolean wasChosen() {
        try {
            return preferences().getBoolean(CHOSEN, false);
        } catch (Throwable ignore) {
            return false;
        }
    }

    public static void markChosen() {
        preferences().edit().putBoolean(CHOSEN, true).apply();
    }

    /**
     * Splits what a person typed. Accepts "name", "name:port" and a bare
     * address; returns null when there is nothing usable in it, so the caller
     * can say so rather than store a value that cannot be dialled.
     */
    public static String[] parse(String typed) {
        if (typed == null) {
            return null;
        }
        String text = typed.trim();
        // People paste what they were sent, and what they were sent often has
        // a scheme and a trailing slash on it.
        text = text.replaceAll("^[a-zA-Z]+://", "").replaceAll("/+$", "");
        if (text.isEmpty()) {
            return null;
        }

        String host = text;
        String port = String.valueOf(DEFAULT_PORT);
        int colon = text.lastIndexOf(':');
        if (colon > 0 && colon < text.length() - 1) {
            host = text.substring(0, colon);
            port = text.substring(colon + 1);
        }
        if (host.isEmpty() || host.contains(" ")) {
            return null;
        }
        try {
            int number = Integer.parseInt(port);
            if (number <= 0 || number > 65535) {
                return null;
            }
            return new String[]{host, String.valueOf(number)};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

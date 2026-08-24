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
 * own — {@link ConnectionsManager#reseedFromAddress(boolean)} is what makes it
 * take.
 */
public class ServerAddress {

    // Ours, offered as the default. A name rather than an address: an address
    // baked into a released build and later given up left phones rotating onto
    // a dead endpoint, showing "Connecting" every other minute, with no way to
    // reach the ones already installed. A name can be pointed elsewhere.
    public static final String DEFAULT_HOST = "common.ice9.app";
    public static final int DEFAULT_PORT = 10443;

    // Where that name points today, for the one moment nothing else can answer:
    // the very first launch, when no lookup has run yet and the socket cannot
    // dial a name. It is a seed and nothing more - the client replaces its
    // address list from help.getConfig the moment it connects, and the lookup
    // below overwrites this as soon as it comes back. The name is still what
    // moves the server; this is only what gets the first packet out.
    private static final String DEFAULT_DIALABLE = "5.23.53.210";

    private static final String PREFS = "serveraddress";
    private static final String HOST = "host";
    private static final String PORT = "port";
    private static final String CHOSEN = "chosen";
    private static final String DIALABLE = "dialable";

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
     * What to hand the network layer, which is not always what was typed.
     *
     * ConnectionSocket dials with inet_pton and nothing else: a name reaches it,
     * fails as "bad ipv4", and the socket closes - quietly, for ever, on every
     * attempt. Upstream never meets this because dc_options always carries
     * addresses; naming a server is ours, so resolving the name is ours too.
     *
     * Falls back to the name when nothing has been resolved yet. That still does
     * not dial, but it keeps this honest: the caller gets what it asked for and
     * the failure stays visible rather than turning into a silent default.
     */
    public static String dialable() {
        try {
            String resolved = preferences().getString(DIALABLE, null);
            if (resolved != null && !resolved.isEmpty()) {
                return resolved;
            }
        } catch (Throwable ignore) {
            // Before the application context exists, fall through below.
        }
        String host = host();
        // Nothing has been looked up yet. For our own default that is the first
        // launch and the seed above answers it; for a name somebody typed it
        // cannot happen, because set() stores what it resolved to alongside it.
        return DEFAULT_HOST.equals(host) ? DEFAULT_DIALABLE : host;
    }

    /**
     * Looks the current name up again and keeps the answer, off the main
     * thread. Called at startup, so that a name pointed at a different machine
     * is followed rather than frozen at whatever it meant on the day somebody
     * typed it - which is the whole reason for preferring a name to an address.
     *
     * Silent when the lookup fails: the address already in hand is better than
     * nothing, and the failure will show up as a connection that does not open,
     * which is the honest place for it.
     */
    public static void refreshDialable() {
        final String host = host();
        String resolved = resolve(host);
        if (resolved == null || resolved.equals(dialable())) {
            return;
        }
        preferences().edit().putString(DIALABLE, resolved).apply();
    }

    /**
     * Turns a name into something dialable. Off the main thread only - this is
     * a DNS lookup, and Android throws NetworkOnMainThreadException for it.
     * Returns null when the name resolves to nothing, which the caller should
     * treat exactly like a server that did not answer.
     *
     * An address given instead of a name comes straight back, so the caller
     * does not have to know which it was handed.
     */
    public static String resolve(String host) {
        if (host == null || host.isEmpty()) {
            return null;
        }
        try {
            // IPv4 first, because the socket is opened as AF_INET unless the
            // address is written as IPv6. A name with only a AAAA record is a
            // case we have never had and would fail visibly rather than oddly.
            java.net.InetAddress[] all = java.net.InetAddress.getAllByName(host);
            for (java.net.InetAddress address : all) {
                if (address instanceof java.net.Inet4Address) {
                    return address.getHostAddress();
                }
            }
            return all.length > 0 ? all[0].getHostAddress() : null;
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * Keeps an address. Says nothing about whether it answers: that is checked
     * before this is called, because an address that does not answer turns into
     * a phone stuck on "Connecting" with no way back to it.
     *
     * dialable is what the network layer is given and host is what the person
     * sees. They differ whenever a name was typed, and keeping both means the
     * name survives on the screen while the socket gets what it can open.
     */
    public static void set(String host, int port, String dialable) {
        preferences().edit()
                .putString(HOST, host == null || host.isEmpty() ? DEFAULT_HOST : host)
                .putInt(PORT, port > 0 && port <= 65535 ? port : DEFAULT_PORT)
                .putString(DIALABLE, dialable == null || dialable.isEmpty() ? host : dialable)
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
     * Puts the question back without changing what is dialled. This is what
     * changing servers from Settings does before signing out: the screen comes
     * up again with the current address in it, and until somebody types another
     * one the client goes on reaching the same server it always did.
     *
     * Deliberately not {@code set(DEFAULT_HOST, DEFAULT_PORT)}: somebody who
     * opens that screen and then walks away must not find themselves on ours.
     */
    public static void askAgain() {
        preferences().edit().putBoolean(CHOSEN, false).apply();
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

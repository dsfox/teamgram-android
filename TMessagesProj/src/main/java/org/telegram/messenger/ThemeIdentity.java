package org.telegram.messenger;

import org.telegram.ui.ActionBar.Theme;

/**
 * Which theme the screens were built for, so that they are rebuilt when it
 * changes and only then (#88, #270).
 *
 * A theme change repaints only what a screen declares, which left Account
 * half-painted in the dark theme, so LaunchActivity rebuilds every screen on
 * didSetNewTheme (#88). But every chat that opens says didSetNewTheme too: its
 * theme delegate refreshes the colours, or applies the chat's own theme. The
 * rebuild that followed threw away what had just been put on the new screen,
 * and a forward picked for one other chat opened that chat with no forward
 * waiting above the field (#270).
 */
public final class ThemeIdentity {

    private static String builtFor;

    private ThemeIdentity() {
    }

    /** The screens are being built for the theme that is active now. */
    public static void remember() {
        builtFor = current();
    }

    /** Whether the active theme is not the one the screens were built for; from now on it is. */
    public static boolean changed() {
        String now = current();
        if (now.equals(builtFor)) {
            return false;
        }
        builtFor = now;
        return true;
    }

    private static String current() {
        Theme.ThemeInfo theme = Theme.getActiveTheme();
        return theme.getKey() + "/" + theme.currentAccentId;
    }
}

package org.telegram.messenger;

import java.util.Arrays;
import java.util.List;

/**
 * Where the site's Android build is fetched from, and whether it is the one to
 * take (Ice9Updates). java.* only, so tests/test_android_updates.py can compile
 * it on its own and ask it.
 *
 * The site and the mirror hand out the same APK beside ice9.version, the build
 * number written last by tools/android_release.py once the APK is there.
 * Russian readers reach ice9.ru more reliably than the Cloudflare-served
 * get.ice9.app, everybody else the other way round, so each asks its own first
 * and the other only when the first did not answer.
 */
public final class UpdateSource {

    public static final String SITE = "https://get.ice9.app/";
    public static final String MIRROR = "https://ice9.ru/";
    public static final String VERSION_FILE = "ice9.version";
    public static final String APK_FILE = "ice9.apk";

    private UpdateSource() {
    }

    /** The hosts to ask, in order, for a reader of this language. */
    public static List<String> hostsFor(String languageCode) {
        boolean russian = languageCode != null && languageCode.toLowerCase().startsWith("ru");
        return russian ? Arrays.asList(MIRROR, SITE) : Arrays.asList(SITE, MIRROR);
    }

    /** The build number a version file names, or -1 when it names none. */
    public static long buildIn(String versionFile) {
        if (versionFile == null) {
            return -1;
        }
        String number = versionFile.trim();
        if (number.isEmpty() || number.length() > 18) {
            return -1;
        }
        for (int i = 0; i < number.length(); i++) {
            if (number.charAt(i) < '0' || number.charAt(i) > '9') {
                return -1;
            }
        }
        return Long.parseLong(number);
    }

    /** Whether the offered build replaces the installed one. */
    public static boolean isNewer(long offered, long installed) {
        return offered > 0 && offered > installed;
    }

    /**
     * Whether a downloaded archive is the build the version file named, for our
     * package. A mirror that lags hands out yesterday's APK beside today's
     * number; that archive is not installed.
     */
    public static boolean isTheOfferedBuild(String archivePackage, long archiveBuild, String ourPackage, long offered) {
        return ourPackage.equals(archivePackage) && archiveBuild == offered;
    }

    /**
     * Whether an install question that was closed is the person's no. Android
     * 15 closes it too when the person went to Settings and allowed ice9 to
     * install apps; that is a yes, and the update then goes in quietly. Where
     * installing was allowed already when Android asked, closing it is a no.
     */
    public static boolean isRefusal(boolean allowedWhenAsked, boolean allowedNow) {
        return allowedWhenAsked || !allowedNow;
    }
}

package org.telegram.messenger;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The languages this client offers, and which one a phone gets first (#193).
 *
 * Two: English, built in, and Russian, whose full pack comes from our server.
 * Upstream's other translations stay in the tree as upstream wrote them -
 * deleting them made every upstream edit a conflict - and are left out of the
 * APK by localeFilters in TMessagesProj_App/build.gradle, which must name the
 * same two. They shipped a tenth to a seventh translated, which looks broken
 * rather than translated, and Ukrainian had no strings at all.
 *
 * Nothing here may import more than java.*: tests/test_android_languages.py
 * compiles and runs this file on its own.
 */
public final class OfferedLanguages {

    public static final List<String> OFFERED = Arrays.asList("en", "ru");

    // Russian where it is the language people read - by the device language,
    // or by the region it is set to: a phone set to English in Kazakhstan most
    // likely belongs to someone who reads Russian.
    private static final List<String> RUSSIAN_SPEAKING_LANGUAGES = Arrays.asList("ru", "uk", "kk", "be");
    private static final List<String> RUSSIAN_SPEAKING_COUNTRIES = Arrays.asList("RU", "UA", "KZ", "BY");

    private OfferedLanguages() {
    }

    /** Drops the built-in languages this client does not offer, keeping the order of the rest. */
    public static <T> void keepOffered(List<T> languages, Map<String, T> byShortName) {
        byShortName.keySet().retainAll(OFFERED);
        languages.retainAll(byShortName.values());
    }

    /** The language a phone gets before anybody chooses one. */
    public static String firstLanguageFor(Locale locale) {
        if (locale == null) {
            return "en";
        }
        String language = locale.getLanguage() == null ? "" : locale.getLanguage().toLowerCase(Locale.ROOT);
        String country = locale.getCountry() == null ? "" : locale.getCountry().toUpperCase(Locale.ROOT);
        return RUSSIAN_SPEAKING_LANGUAGES.contains(language) || RUSSIAN_SPEAKING_COUNTRIES.contains(country) ? "ru" : "en";
    }
}

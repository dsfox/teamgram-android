package org.telegram.ui;

import org.telegram.messenger.Offered;
import org.telegram.ui.ProfileActivity.SearchAdapter.SearchResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * What settings search may find (#91, #197).
 *
 * Settings search is a second copy of the whole settings tree: upstream's
 * onCreateSearchArray in ProfileActivity, a flat list of every destination in
 * settings. The sweeps of #91 took the switched-off rows out of the screens
 * that draw them, and the list went on offering every one of them - "two-step"
 * opened the cloud password screen, "calls" found six settings for calls
 * nobody could place. So an entry lives exactly as long as the row it points
 * at, and this table is where that is decided: upstream's list stays as
 * upstream writes it and passes through keep() once.
 *
 * An entry is known by its link, or by its number when it has none - not by
 * the number alone, because upstream gives 122 to two entries, the calls' P2P
 * setting and linking a desktop. tests/test_settings_doors.py checks that every
 * entry opening a hidden row is here under its switch, and that every door
 * here still names an entry: a door upstream renamed is open again.
 */
final class OfferedSearchItems {

    private static final Map<String, Boolean> BY_LINK = new HashMap<>();
    private static final Map<Integer, Boolean> BY_GUID = new HashMap<>();

    static {
        link("tg://settings/notifications/channels", Offered.CHANNELS);
        guid(5, Offered.CALLS);

        link("tg://settings/privacy/2sv", Offered.CLOUD_PASSWORD);
        link("tg://settings/privacy/calls/p2p", Offered.CALLS);
        link("tg://settings/privacy/calls", Offered.CALLS);
        link("tg://settings/privacy/data-settings/clear-payment-info", Offered.BOTS);
        link("tg://settings/privacy/active-websites", Offered.BOTS);
        link("tg://settings/devices/link-desktop", Offered.DESKTOP);

        guid(217, Offered.CALLS);
        link("tg://settings/data/use-less-data", Offered.CALLS);
        guid(219, Offered.CALLS);
        link("tg://settings/data/proxy/use-for-calls", Offered.CALLS);
        link("tg://settings/data/save-to-photos/channels", Offered.CHANNELS);

        guid(319, Offered.CLOUD_THEMES);
        link("tg://settings/appearance/themes/create", Offered.CLOUD_THEMES);

        guid(602, Offered.STICKER_PACKS);
        guid(603, Offered.STICKER_PACKS);
        link("tg://settings/appearance/stickers-and-emoji/archived", Offered.STICKER_PACKS);
        guid(605, Offered.STICKER_PACKS);
        guid(610, Offered.STICKER_PACKS);
        link("tg://settings/appearance/stickers-and-emoji/emoji/quick-reaction", Offered.REACTIONS);

        link("tg://settings/folders", Offered.FOLDERS);
        link("tg://settings/folders/create", Offered.FOLDERS);

        guid(906, Offered.REACTIONS);
        guid(910, Offered.CHANNELS);
        link("tg://settings/power-saving/call-animations", Offered.CALLS);

        link("tg://settings/language/show-button", Offered.TRANSLATION);
        link("tg://settings/language/do-not-translate", Offered.TRANSLATION);

        link("tg://settings/ask-question", Offered.HELP);
        link("tg://settings/faq", Offered.HELP);
        link("tg://settings/privacy-policy", Offered.HELP);
    }

    private OfferedSearchItems() {
    }

    private static void link(String entryLink, boolean offered) {
        BY_LINK.put(entryLink, offered);
    }

    private static void guid(int entryGuid, boolean offered) {
        BY_GUID.put(entryGuid, offered);
    }

    /** Upstream's entries without those whose row is switched off. A null stays: its readers skip it. */
    static SearchResult[] keep(SearchResult[] all) {
        ArrayList<SearchResult> kept = new ArrayList<>(all.length);
        for (SearchResult result : all) {
            if (result == null || offered(result)) {
                kept.add(result);
            }
        }
        return kept.toArray(new SearchResult[0]);
    }

    private static boolean offered(SearchResult result) {
        Boolean door = result.link != null ? BY_LINK.get(result.link) : BY_GUID.get(result.guid);
        return door == null || door;
    }
}

package org.telegram.messenger;

import android.net.Uri;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Links into what this client does not offer (#227).
 *
 * A link is a way in like any other. Hiding a row while its link still opens
 * the screen behind it is the hidden feature App Review rejected iOS for
 * (2.3.1(a), 5 October, #181): Premium, Stars, stickers, folders and the rest
 * were each one tap on a link away, and no Offered switch was ever asked.
 *
 * Every reader of a link - the old parser in LaunchActivity, LinkManager,
 * GiftInfoBottomSheet - starts from the intent LaunchActivity.handleIntent is
 * given, a tap on a link inside a chat included (Browser.openAsInternalIntent),
 * so the one check stands there. Only our own forms are read: tg2:// and
 * i.ice9.app, the two the manifest registers.
 *
 * Invitations into groups are left open: they work on the server, and iOS
 * opens them too.
 */
public final class OfferedLinks {

    private OfferedLinks() {
    }

    public static boolean leadsToWhatIsOff(Uri uri) {
        if (uri == null || uri.getScheme() == null) {
            return false;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("tg2")) {
            return appLinkLeadsToWhatIsOff(uri);
        }
        if ((scheme.equals("http") || scheme.equals("https")) && "i.ice9.app".equalsIgnoreCase(uri.getHost())) {
            return webLinkLeadsToWhatIsOff(uri);
        }
        return false;
    }

    /** tg2://host/path?query, also written tg2:host?query. */
    private static boolean appLinkLeadsToWhatIsOff(Uri uri) {
        String rest = uri.toString().substring("tg2:".length());
        if (rest.startsWith("//")) {
            rest = rest.substring(2);
        }
        Uri link = Uri.parse("tg2://" + rest);
        String host = lower(link.getHost());
        String path = lower(link.getPath());
        Set<String> query = link.getQueryParameterNames();
        switch (host) {
            // Payments of every kind: there is nothing to buy here.
            case "premium_offer":
            case "premium_multigift":
            case "stars":
            case "stars_topup":
            case "ton":
            case "invoice":
                return true;
            case "giftcode":
            case "nft":
            case "send_gift":
                return !Offered.GIFTS;
            case "boost":
                return !Offered.CHANNELS;
            case "addstickers":
            case "addemoji":
                return !Offered.STICKER_PACKS;
            case "addlist":
                return !Offered.FOLDERS;
            case "call":
                return !Offered.VIDEO_CHATS;
            case "bg":
                return !Offered.CHAT_WALLPAPERS;
            case "addtheme":
                return !Offered.CLOUD_THEMES;
            case "settings":
                if (path.startsWith("/folders")) {
                    return !Offered.FOLDERS;
                }
                if (path.startsWith("/login_email")) {
                    return !Offered.LOGIN_EMAIL;
                }
                // Telegram's developer switches for its log, kept out of every
                // build; the log is sent from Settings, in the open.
                if (query.contains("enablelogs") || query.contains("sendlogs") || query.contains("disablelogs")) {
                    return true;
                }
                break;
        }
        return asksForWhatIsOff(query);
    }

    /** https://i.ice9.app/first/second?query - the t.me of this server. */
    private static boolean webLinkLeadsToWhatIsOff(Uri uri) {
        List<String> segments = uri.getPathSegments();
        String first = segments.isEmpty() ? "" : lower(segments.get(0));
        String second = segments.size() > 1 ? lower(segments.get(1)) : "";
        if (first.startsWith("$")) {
            return true;
        }
        switch (first) {
            case "invoice":
                return true;
            case "giftcode":
            case "nft":
            case "auction":
            case "stargift_auction":
            case "stargift_preview":
                return !Offered.GIFTS;
            case "boost":
                return !Offered.CHANNELS;
            case "addstickers":
            case "addemoji":
                return !Offered.STICKER_PACKS;
            case "addlist":
            case "folder":
                return !Offered.FOLDERS;
            case "call":
                return !Offered.VIDEO_CHATS;
            case "bg":
                return !Offered.CHAT_WALLPAPERS;
            case "addtheme":
                return !Offered.CLOUD_THEMES;
            case "addstyle":
                return !Offered.AI_EDITOR;
            case "newbot":
            case "oauth":
                return !Offered.BOTS;
            case "s":
                return !Offered.STORIES;
            case "c":
                // A message in a group or channel: /c/<id>/<message>.
                return asksForWhatIsOff(uri.getQueryParameterNames());
        }
        // /<name>/s/<story>, /<name>/a/<album>, /<name>/c/<gift collection>.
        if ((second.equals("s") || second.equals("a")) && !Offered.STORIES) {
            return true;
        }
        if (second.equals("c") && !Offered.GIFTS) {
            return true;
        }
        return asksForWhatIsOff(uri.getQueryParameterNames());
    }

    /** What a link can ask for in its query, whatever its path. */
    private static boolean asksForWhatIsOff(Set<String> query) {
        if (!Offered.BOTS && containsAny(query, "start", "startgroup", "startchannel", "startattach",
                "attach", "startapp", "appname", "game")) {
            return true;
        }
        if (!Offered.STORIES && containsAny(query, "story", "album")) {
            return true;
        }
        if (!Offered.CHANNELS && query.contains("boost")) {
            return true;
        }
        return !Offered.VIDEO_CHATS && containsAny(query, "voicechat", "videochat", "livestream");
    }

    private static boolean containsAny(Set<String> query, String... names) {
        for (String name : names) {
            if (query.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}

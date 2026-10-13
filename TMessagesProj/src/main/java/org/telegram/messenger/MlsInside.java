package org.telegram.messenger;

import android.util.Base64;

import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.TLRPCMls;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * A location or a contact, carried inside the ciphertext (#267).
 *
 * The server used to be handed both as they were: a point or a place with its
 * coordinates, a contact with its number and card. In a chat that encrypts the
 * message goes as a text instead, its words empty, and what it carries travels
 * in the same ciphertext as mls.geo or mls.contact; the phone that opens it
 * draws the location or the contact again from there. A live location is not
 * carried: it is a stream of edits nothing here encrypts, and it stays off.
 */
final class MlsInside {

    /**
     * A contact's card longer than this, in bytes, is left out and the name
     * and the number go alone: the server keeps a message's text in 6000
     * characters, and a card can hold a photograph.
     */
    static final int VCARD_LIMIT = 2000;

    /** Where a forward's author rides with the message until it is sent:
     *  its params, which outlive a restart. */
    private static final String FORWARD = "mls_forward";

    private MlsInside() {
    }

    /** Whether this is a location or a contact that can travel inside. */
    static boolean carries(TLRPC.InputMedia media) {
        return media instanceof TLRPC.TL_inputMediaGeoPoint
                || media instanceof TLRPC.TL_inputMediaVenue
                || media instanceof TLRPC.TL_inputMediaContact;
    }

    /** What a message's own location or contact becomes inside the
     *  ciphertext, or null when it is neither. */
    static TLObject wire(TLRPC.MessageMedia media) {
        if (media instanceof TLRPC.TL_messageMediaVenue) {
            TLRPCMls.TL_mls_geo geo = point(media.geo);
            if (geo == null) {
                return null;
            }
            geo.title = text(media.title);
            geo.address = text(media.address);
            geo.provider = text(media.provider);
            geo.venue_id = text(media.venue_id);
            geo.venue_type = text(media.venue_type);
            return geo;
        }
        if (media instanceof TLRPC.TL_messageMediaGeo) {
            return point(media.geo);
        }
        if (media instanceof TLRPC.TL_messageMediaContact) {
            TLRPCMls.TL_mls_contact contact = new TLRPCMls.TL_mls_contact();
            contact.phone_number = text(media.phone_number);
            contact.first_name = text(media.first_name);
            contact.last_name = text(media.last_name);
            String vcard = text(media.vcard);
            contact.vcard = vcard.getBytes(StandardCharsets.UTF_8).length > VCARD_LIMIT ? "" : vcard;
            contact.user_id = media.user_id;
            return contact;
        }
        return null;
    }

    /** The location or the contact drawn again from what came inside, or null
     *  when this is neither. A new object every time. */
    static TLRPC.MessageMedia media(TLObject wire) {
        if (wire instanceof TLRPCMls.TL_mls_geo) {
            TLRPCMls.TL_mls_geo geo = (TLRPCMls.TL_mls_geo) wire;
            TLRPC.TL_geoPoint point = new TLRPC.TL_geoPoint();
            point.lat = geo.lat;
            point._long = geo._long;
            if (geo.accuracy_radius > 0) {
                point.flags |= 1;
                point.accuracy_radius = geo.accuracy_radius;
            }
            if (geo.title.isEmpty()) {
                TLRPC.TL_messageMediaGeo media = new TLRPC.TL_messageMediaGeo();
                media.geo = point;
                return media;
            }
            // Every string is written as it is, so none may be null.
            TLRPC.TL_messageMediaVenue venue = new TLRPC.TL_messageMediaVenue();
            venue.geo = point;
            venue.title = geo.title;
            venue.address = text(geo.address);
            venue.provider = text(geo.provider);
            venue.venue_id = text(geo.venue_id);
            venue.venue_type = text(geo.venue_type);
            return venue;
        }
        if (wire instanceof TLRPCMls.TL_mls_contact) {
            TLRPCMls.TL_mls_contact contact = (TLRPCMls.TL_mls_contact) wire;
            TLRPC.TL_messageMediaContact media = new TLRPC.TL_messageMediaContact();
            media.phone_number = text(contact.phone_number);
            media.first_name = text(contact.first_name);
            media.last_name = text(contact.last_name);
            media.vcard = text(contact.vcard);
            media.user_id = contact.user_id;
            return media;
        }
        return null;
    }

    /** The media put on a message so that it stays there: a message stores
     *  its media only with the flag that says it has one. */
    static void put(TLRPC.Message message, TLRPC.MessageMedia media) {
        if (message == null || media == null) {
            return;
        }
        message.media = media;
        message.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA;
    }

    /**
     * The request a location or a contact goes as: a text, its words empty,
     * the ciphertext filled in on the way. Everything else the media request
     * says is said again - the bits that mean a field is there are the same in
     * both - except a quote of the message replied to, which would go beside
     * the ciphertext.
     */
    static TLRPC.TL_messages_sendMessage asText(TLRPC.TL_messages_sendMedia media) {
        TLRPC.TL_messages_sendMessage text = new TLRPC.TL_messages_sendMessage();
        text.flags = media.flags & (TLObject.FLAG_0 | TLObject.FLAG_2 | TLObject.FLAG_10 | TLObject.FLAG_13
                | TLObject.FLAG_17 | TLObject.FLAG_18 | TLObject.FLAG_21 | TLObject.FLAG_24);
        text.no_webpage = true;
        text.silent = media.silent;
        text.background = media.background;
        text.clear_draft = media.clear_draft;
        text.noforwards = media.noforwards;
        text.update_stickersets_order = media.update_stickersets_order;
        text.invert_media = media.invert_media;
        text.peer = media.peer;
        text.reply_to = media.reply_to;
        if (text.reply_to instanceof TLRPC.TL_inputReplyToMessage) {
            TLRPC.TL_inputReplyToMessage reply = (TLRPC.TL_inputReplyToMessage) text.reply_to;
            reply.quote_text = null;
            reply.quote_entities = new java.util.ArrayList<>();
            reply.quote_offset = 0;
            reply.flags &= ~(4 | 8 | 16);
        }
        text.message = "";
        text.random_id = media.random_id;
        text.reply_markup = media.reply_markup;
        text.schedule_date = media.schedule_date;
        text.schedule_repeat_period = media.schedule_repeat_period;
        text.send_as = media.send_as;
        text.quick_reply_shortcut = media.quick_reply_shortcut;
        text.effect = media.effect;
        text.allow_paid_stars = media.allow_paid_stars;
        text.suggested_post = media.suggested_post;
        text.ephemeralReceiverBotId = media.ephemeralReceiverBotId;
        return text;
    }

    /** A forward's author kept with the message it is sent as. */
    static void keepForward(HashMap<String, String> params, MlsRuntime.Forwarded from) {
        if (params == null || from == null) {
            return;
        }
        TLRPCMls.TL_mls_forward forward = new TLRPCMls.TL_mls_forward();
        forward.from_id = from.id;
        forward.from_name = text(from.name);
        forward.date = from.date;
        SerializedData out = new SerializedData();
        forward.serializeToStream(out);
        params.put(FORWARD, Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
    }

    /** The forward's author kept with a message, or null when it is none. */
    static MlsRuntime.Forwarded forward(HashMap<String, String> params) {
        String kept = params == null ? null : params.get(FORWARD);
        if (kept == null) {
            return null;
        }
        try {
            SerializedData in = new SerializedData(Base64.decode(kept, Base64.NO_WRAP));
            TLRPCMls.TL_mls_forward forward = TLRPCMls.TL_mls_forward.TLdeserialize(in, in.readInt32(false), false);
            return forward == null ? null : new MlsRuntime.Forwarded(forward.from_id, forward.from_name, forward.date);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /** What the log calls it. */
    static String kindOf(TLObject wire) {
        if (wire instanceof TLRPCMls.TL_mls_geo) {
            return ((TLRPCMls.TL_mls_geo) wire).title.isEmpty() ? "a location" : "a venue";
        }
        return wire instanceof TLRPCMls.TL_mls_contact ? "a contact" : "something";
    }

    private static TLRPCMls.TL_mls_geo point(TLRPC.GeoPoint point) {
        if (!(point instanceof TLRPC.TL_geoPoint)) {
            return null;
        }
        TLRPCMls.TL_mls_geo geo = new TLRPCMls.TL_mls_geo();
        geo.lat = point.lat;
        geo._long = point._long;
        geo.accuracy_radius = (point.flags & 1) != 0 ? point.accuracy_radius : 0;
        return geo;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}

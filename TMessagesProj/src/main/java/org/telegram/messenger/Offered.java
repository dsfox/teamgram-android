package org.telegram.messenger;

/**
 * What this client offers, and what it does not offer yet.
 *
 * One place, on purpose. This is a fork of a client built against a server that
 * answers everything, running against a server that answers some of it - so
 * there are buttons here that would open an empty screen, or take an instruction
 * and drop it silently. A button that does nothing is worse than a button that
 * is not there: the first is a fault the person carries around wondering about,
 * the second is simply a thing this messenger does not do yet.
 *
 * Every switch below is false because something behind it is missing, and each
 * one says what. Turning one back to true is the last step of restoring it, not
 * the first - and the epic (#83) holds the same list with what each one needs.
 *
 * The rule: nothing is hidden anywhere in this client without a switch here.
 * A feature quietly removed and not written down is a feature nobody brings
 * back.
 */
public final class Offered {

    private Offered() {
    }

    /**
     * Scheduling a message for later.
     *
     * Reading the scheduled list is a stub on the server and sending one has no
     * handler at all, so the calendar opens, takes a date, and nothing is ever
     * sent or shown. Issue #26.
     */
    public static final boolean SCHEDULED_MESSAGES = false;

    /**
     * Translating a message.
     *
     * No implementation on the server, and none of ours. Issue #27.
     */
    public static final boolean TRANSLATION = false;

    /**
     * The archive.
     *
     * Moving a chat there has no handler, so the chat slides away and comes
     * back on the next sync as though nothing happened. Issue #25.
     */
    public static final boolean ARCHIVE = false;

    /**
     * Chat folders.
     *
     * Reading them returns nothing and creating one has no handler. Issue #22.
     */
    public static final boolean FOLDERS = false;

    /**
     * Chat themes and name colours.
     *
     * Both pickers would open empty - the server has no list to give them.
     * Issues #23 and #24.
     */
    public static final boolean CHAT_THEMES = false;
    public static final boolean NAME_COLOURS = false;

    /**
     * Video chats.
     *
     * No implementation anywhere yet. Issue #28.
     */
    public static final boolean VIDEO_CHATS = false;

    /**
     * Stories.
     *
     * Nothing behind them, and a private messenger for a few people is not
     * where they belong first.
     */
    public static final boolean STORIES = false;

    /**
     * Channels and public groups.
     *
     * This is an invitation-only messenger for conversations between people;
     * broadcasting is a different thing and none of it is built.
     */
    public static final boolean CHANNELS = false;

    /**
     * Sticker packs.
     *
     * messages.getAllStickers is answered with an empty list and installing one
     * has no handler (#20), so every list of packs opens empty. The emoji and
     * animation settings that live on the same screen work and stay.
     */
    public static final boolean STICKER_PACKS = false;

    /**
     * A cloud password - the second step asked for when signing in.
     *
     * account.getPassword is answered with "there is no password" and nothing
     * handles setting one, so the screen would take a password, send it, and
     * leave the account exactly as open as it was. Worse than absent: it reads
     * as a lock that is on.
     */
    public static final boolean CLOUD_PASSWORD = false;

    /**
     * Gifts.
     *
     * The catalogue is answered with an empty list and nothing can be bought or
     * sent, so a setting for who may send you one governs nothing.
     */
    public static final boolean GIFTS = false;

    /**
     * Voice and video calls between people.
     *
     * Nothing places or receives one. Issue #14.
     *
     * Three screens took their call rows out before this switch existed and
     * pin them to -1 with a note: the ringtone section in
     * NotificationsSettingsActivity, "who may call you" in
     * PrivacySettingsActivity, and the data section in DataSettingsActivity.
     * They are left as they are - identical behaviour, and rewriting a
     * working removal risks the screen for nothing - but this is the switch to
     * follow when the calls come back.
     */
    public static final boolean CALLS = false;

    /**
     * An address to receive login codes at.
     *
     * account.sendVerifyEmailCode is not implemented, so the screen would take
     * an address and never confirm it. This client already hides the row unless
     * the account has one, and no account can - the switch is here because the
     * other client hides it outright and the two have to hide the same things.
     */
    public static final boolean LOGIN_EMAIL = false;

    /**
     * Bots and the mini apps that run inside them.
     *
     * Not a stub but an absence: the server has no bots service, and not one
     * bots.* handler exists, so no account can be a bot and there is nothing
     * for a search over apps to find. Issue #105.
     */
    public static final boolean BOTS = false;

    /**
     * An avatar built out of an emoji on a coloured background.
     *
     * There are no emoji to build it from. The picker is filled from
     * account.getDefaultProfilePhotoEmojis, which is an empty stub, plus the
     * installed emoji packs, and there are none of those either (#20) - so the
     * screen opens on a white field with a search box that can find nothing,
     * and an active "Set" button over an empty choice. The backgrounds do work,
     * but a background on its own is not an avatar.
     *
     * Comes back with the emoji packs. Nothing else is needed for it.
     */
    public static final boolean EMOJI_AVATAR = false;

    /**
     * Reactions to a message.
     *
     * The server keeps none, so a tapped reaction appears for a moment on the
     * phone that tapped it and is gone by the next sync.
     */
    public static final boolean REACTIONS = false;

    /**
     * A group's invite link: the row in the add-member picker, the "Invite
     * links" cell in the group's settings, the copy-link row of the invite
     * sheet, and the QR behind them.
     *
     * Not missing on the server - the link is minted and resolves - but it is
     * not how anybody gets in here. An invitation is an SMS with a code bound
     * to a number (#47), so the link is useless to somebody not on ice9; and
     * the app drops it without a word for somebody signed out. Whoever is
     * signed in did not need it: any member adds them from contacts. What it
     * would do is admit anyone holding the hash. Issue #163.
     */
    public static final boolean GROUP_INVITE_LINKS = false;

    // A round video in a conversation that encrypts used to be switched off
    // here: the one message uploaded while it was still being recorded, which
    // an encrypted upload cannot take. It is offered again since #80 - the
    // head start is given up in InstantCameraView, the recording goes to a
    // file first, and the send uploads it whole and encrypted the way an
    // ordinary video goes. Kept as a note rather than a switch, so the next
    // person looking for the row finds where it went.
}

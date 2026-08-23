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
     * Reactions to a message.
     *
     * The server keeps none, so a tapped reaction appears for a moment on the
     * phone that tapped it and is gone by the next sync.
     */
    public static final boolean REACTIONS = false;

    // A round video in a conversation that encrypts used to be switched off
    // here: the one message uploaded while it was still being recorded, which
    // an encrypted upload cannot take. It is offered again since #80 - the
    // head start is given up in InstantCameraView, the recording goes to a
    // file first, and the send uploads it whole and encrypted the way an
    // ordinary video goes. Kept as a note rather than a switch, so the next
    // person looking for the row finds where it went.
}

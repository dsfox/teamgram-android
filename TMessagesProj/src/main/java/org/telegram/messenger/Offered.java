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
     * Reactions to a message.
     *
     * The server keeps none, so a tapped reaction appears for a moment on the
     * phone that tapped it and is gone by the next sync.
     */
    public static final boolean REACTIONS = false;

    /**
     * A round video message, in a conversation that encrypts.
     *
     * It is the one message uploaded while it is still being recorded, and an
     * encrypted upload cannot send a file that is still growing. Elsewhere it
     * works and is offered - this is the only switch here that is not
     * all-or-nothing, and it lives in ChatActivityEnterView.checkRoundVideo.
     */
    public static final boolean ROUND_VIDEO_WHEN_ENCRYPTED = false;
}

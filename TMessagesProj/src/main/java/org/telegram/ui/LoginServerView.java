package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import java.util.Set;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.ServerAddress;
import org.telegram.tgnet.ServerCode;
import org.telegram.tgnet.ServerKey;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.OutlineTextContainerView;
import org.telegram.ui.Components.SlideView;

/**
 * The first screen after installation: which server this app talks to.
 *
 * It stands before the phone number rather than beside the name, where it
 * was first wanted, because the name is asked after the code and the code
 * comes from the server. Nothing at all can happen until this is answered.
 *
 * Ours is filled in, so that the common case is one tap. Anything else is
 * accepted - but only after it has answered a real handshake, because an
 * address stored without that is a person carrying an app that will never
 * connect and cannot be told why. See ice9 #65.
 */
public class LoginServerView extends SlideView {

    /** What the view needs from the login screen it stands in. */
    public interface Host {
        void showProgress();

        void hideProgress();

        /** The server answered and is kept: on to the phone number. */
        void chosen();

        Activity activity();

        ConnectionsManager connections();
    }

    private final Host owner;

    private final OutlineTextContainerView outlineField;
    private final EditTextBoldCursor addressField;
    private final TextView titleTextView;
    private final TextView descriptionTextView;
    private final TextView errorTextView;
    private final TextView defaultButton;
    private final TextView cancelButton;
    private final OutlineTextContainerView codeOutlineField;
    private final EditTextBoldCursor codeField;
    private final TextView ownServerView;

    private int checkingRequest = 0;
    private Runnable giveUp;
    private boolean nextPressed;
    private boolean formattingCode;
    /// Set when the address, given without a code, turned out to have a
    /// key of its own: silence is then explained by the missing code.
    private boolean ownKeySeen;
    private int attempt;
    /// What the checking is interrupting, kept while it runs so that giving
    /// up can put it back.
    private ServerAddress.Kept checkingFrom;

    public LoginServerView(Context context, Host owner) {
        super(context);
        this.owner = owner;
        setOrientation(VERTICAL);

        titleTextView = new TextView(context);
        titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        titleTextView.setTypeface(AndroidUtilities.bold());
        titleTextView.setGravity(Gravity.CENTER_HORIZONTAL);
        titleTextView.setText(getString(R.string.Ice9ServerTitle));
        addView(titleTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 8, 24, 8, 0));

        descriptionTextView = new TextView(context);
        descriptionTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        descriptionTextView.setGravity(Gravity.CENTER_HORIZONTAL);
        descriptionTextView.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        descriptionTextView.setText(getString(R.string.Ice9ServerInfo));
        addView(descriptionTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 16, 8, 16, 20));

        outlineField = new OutlineTextContainerView(context);
        outlineField.setText(getString(R.string.Ice9ServerAddress));

        addressField = new EditTextBoldCursor(context);
        addressField.setSingleLine();
        addressField.setLines(1);
        addressField.setMaxLines(1);
        addressField.setBackground(null);
        addressField.setCursorSize(AndroidUtilities.dp(20));
        addressField.setCursorWidth(1.5f);
        addressField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        addressField.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        addressField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressField.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        addressField.setText(ServerAddress.describe());
        addressField.setOnFocusChangeListener((v, hasFocus) -> outlineField.animateSelection(hasFocus ? 1f : 0f));
        addressField.setOnEditorActionListener((textView, i, keyEvent) -> {
            if (i == EditorInfo.IME_ACTION_NEXT) {
                onNextPressed(null);
                return true;
            }
            return false;
        });
        addressField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                // The old complaint is about the old address; leaving it up
                // makes a corrected one look refused too.
                showError(null);
                updateDefaultButton();
            }
        });
        outlineField.attachEditText(addressField);
        // The same shape the name and e-mail fields use in this file: the
        // padding is what centres the text inside the outline, and the
        // container is measured rather than pinned to a height. Pinned to
        // 58dp with no padding, the text sat on the top edge and ran into
        // the floating label - which is what it did on a phone.
        outlineField.addView(addressField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        addView(outlineField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 0));

        // The server code (#244): only a server of one's own has one,
        // printed by its installer beside the address, and the app keeps
        // the server's key only if this is its code.
        codeOutlineField = new OutlineTextContainerView(context);
        codeOutlineField.setText(getString(R.string.Ice9ServerCode));
        codeField = new EditTextBoldCursor(context);
        codeField.setSingleLine();
        codeField.setLines(1);
        codeField.setMaxLines(1);
        codeField.setBackground(null);
        codeField.setCursorSize(AndroidUtilities.dp(20));
        codeField.setCursorWidth(1.5f);
        codeField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        codeField.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        codeField.setInputType(InputType.TYPE_CLASS_PHONE);
        codeField.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        codeField.setOnFocusChangeListener((v, hasFocus) -> codeOutlineField.animateSelection(hasFocus ? 1f : 0f));
        codeField.setOnEditorActionListener((textView, i, keyEvent) -> {
            if (i == EditorInfo.IME_ACTION_NEXT) {
                onNextPressed(null);
                return true;
            }
            return false;
        });
        codeField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (formattingCode) {
                    return;
                }
                StringBuilder digits = new StringBuilder();
                for (int i = 0; i < s.length() && digits.length() < 24; i++) {
                    char c = s.charAt(i);
                    if (c >= '0' && c <= '9') {
                        digits.append(c);
                    }
                }
                String grouped = ServerCode.grouped(digits.toString());
                if (!grouped.contentEquals(s)) {
                    formattingCode = true;
                    codeField.setText(grouped);
                    codeField.setSelection(codeField.length());
                    formattingCode = false;
                }
                showError(null);
                updateDefaultButton();
            }
        });
        codeOutlineField.attachEditText(codeField);
        codeOutlineField.addView(codeField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        addView(codeOutlineField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 12, 16, 0));

        errorTextView = new TextView(context);
        errorTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        errorTextView.setGravity(Gravity.CENTER_HORIZONTAL);
        errorTextView.setVisibility(GONE);
        addView(errorTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 16, 12, 16, 0));

        defaultButton = new TextView(context);
        defaultButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        defaultButton.setGravity(Gravity.CENTER);
        defaultButton.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        defaultButton.setText(getString(R.string.Ice9ServerSetDefault));
        defaultButton.setOnClickListener(v -> {
            addressField.setText(ServerAddress.DEFAULT_HOST);
            addressField.setSelection(addressField.length());
            codeField.setText("");
            showError(null);
        });
        addView(defaultButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 16, 8, 16, 0));

        // Fifteen seconds is a long time to be sure you have mistyped and
        // have no way to say so. It stands where the default button stands
        // and only while the checking runs, so the screen never offers both.
        cancelButton = new TextView(context);
        cancelButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        cancelButton.setGravity(Gravity.CENTER);
        cancelButton.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        cancelButton.setText(getString(R.string.Cancel));
        cancelButton.setVisibility(GONE);
        cancelButton.setOnClickListener(v -> giveUpChecking());
        addView(cancelButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 16, 8, 16, 0));

        // Said here, where it can be acted on, rather than on a page somebody
        // would have to go looking for. Most people will keep ours and
        // should - but a messenger that offers a server of your own and
        // never says how is offering it the way a form offers a tick box.
        ownServerView = new TextView(context);
        ownServerView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        ownServerView.setGravity(Gravity.CENTER_HORIZONTAL);
        ownServerView.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        ownServerView.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        ownServerView.setText(getString(R.string.Ice9ServerOwn));
        ownServerView.setOnClickListener(v -> Browser.openUrl(owner.activity(), getString(R.string.Ice9ServerInstructions)));
        addView(ownServerView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 16, 12, 16, 0));

        updateColors();
        updateDefaultButton();
    }

    /// A server link fills the fields and waits for Next: nothing is kept
    /// until the same check passes (#244).
    void fill(ServerCode.Link link) {
        giveUpChecking();
        if (link.malformed) {
            showError(getString(R.string.Ice9ServerLinkMalformed));
            return;
        }
        addressField.setText(link.port == ServerAddress.DEFAULT_PORT ? link.host : link.host + ":" + link.port);
        addressField.setSelection(addressField.length());
        codeField.setText(ServerCode.grouped(link.code));
    }

    private void updateDefaultButton() {
        if (nextPressed) {
            // Checking. The screen offers one thing at a time.
            defaultButton.setVisibility(INVISIBLE);
            cancelButton.setVisibility(VISIBLE);
            return;
        }
        cancelButton.setVisibility(GONE);
        // Nothing to put back when what is typed is already ours.
        boolean ours = ServerAddress.DEFAULT_HOST.contentEquals(addressField.getText().toString().trim())
                && codeField.length() == 0;
        defaultButton.setVisibility(ours ? INVISIBLE : VISIBLE);
    }

    /// Stops waiting. The address goes back to where it was and the screen
    /// says nothing about it: somebody who changed their mind has not been
    /// refused, and a complaint in front of them would read as one.
    private void giveUpChecking() {
        if (!nextPressed) {
            return;
        }
        nextPressed = false;
        if (giveUp != null) {
            AndroidUtilities.cancelRunOnUIThread(giveUp);
            giveUp = null;
        }
        if (checkingRequest != 0) {
            owner.connections().cancelRequest(checkingRequest, true);
            checkingRequest = 0;
        }
        owner.hideProgress();
        if (checkingFrom != null) {
            checkingFrom.restore();
            ConnectionsManager.reseedFromAddress(false);
            checkingFrom = null;
        }
        showError(null);
        updateDefaultButton();
    }

    private void showError(String text) {
        if (text == null) {
            errorTextView.setVisibility(GONE);
            return;
        }
        errorTextView.setText(text);
        errorTextView.setVisibility(VISIBLE);
        outlineField.animateError(1f);
        AndroidUtilities.shakeView(outlineField);
    }

    @Override
    public void updateColors() {
        if (titleTextView == null) {
            return;
        }
        titleTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        descriptionTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6));
        addressField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        addressField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        addressField.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        errorTextView.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        defaultButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        cancelButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        codeField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        codeField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        codeField.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        ownServerView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        outlineField.updateColor();
        codeOutlineField.updateColor();
    }

    @Override
    public String getHeaderName() {
        return getString(R.string.Ice9Server);
    }

    @Override
    public boolean needBackButton() {
        // The first screen, with nowhere behind it - unless a server link
        // brought it back, and the phone number is behind it.
        return ServerAddress.wasChosen();
    }

    @Override
    public boolean onBackPressed(boolean force) {
        giveUpChecking();
        return true;
    }

    @Override
    public void onShow() {
        super.onShow();
        addressField.setText(ServerAddress.describe());
        addressField.setSelection(addressField.length());
        codeField.setText(ServerCode.grouped(ServerAddress.code()));
        ServerCode.Link link = ServerAddress.takePending();
        if (link != null) {
            fill(link);
        }
        updateDefaultButton();
    }

    @Override
    public void onNextPressed(String code) {
        if (nextPressed) {
            return;
        }
        String[] parsed = ServerAddress.parse(addressField.getText().toString());
        if (parsed == null) {
            showError(getString(R.string.Ice9ServerMalformed));
            return;
        }
        final String serverCode = ServerCode.digits(codeField.getText().toString());
        if (serverCode == null) {
            showError(getString(R.string.Ice9ServerCodeMalformed));
            return;
        }

        nextPressed = true;
        ownKeySeen = false;
        attempt++;
        AndroidUtilities.hideKeyboard(addressField);
        updateDefaultButton();

        owner.showProgress();

        // A name has to become something the socket can open before it is
        // stored or dialled: ConnectionSocket takes an IPv4 literal and
        // nothing else, so a name reaches it as "bad ipv4" and the socket
        // closes silently on every attempt. That is a DNS lookup, which is
        // not a thing to do on the thread drawing the screen - so the
        // checking starts when the answer comes back.
        final String host = parsed[0];
        final int port = Integer.parseInt(parsed[1]);
        Utilities.globalQueue.postRunnable(() -> {
            final String dialable = ServerAddress.resolve(host);
            // With a code, the server's own key is fetched and kept only if
            // the code is the key's, before anything is pointed at it.
            final ServerKey.Answer key = dialable != null && !serverCode.isEmpty() ? ServerKey.fetch(dialable, host, port) : null;
            AndroidUtilities.runOnUIThread(() -> {
                if (!nextPressed) {
                    // Given up on while the name was being looked up.
                    return;
                }
                if (dialable == null) {
                    // A name that resolves to nothing cannot be dialled,
                    // and to the person that is the same news as a server
                    // that did not answer.
                    answered(false, null);
                    return;
                }
                if (key == null) {
                    // The key built into the app: ours, the stand. Anywhere
                    // else, ask the server for its key alongside the check.
                    boolean ours = ServerAddress.DEFAULT_HOST.equals(host) && port == ServerAddress.DEFAULT_PORT;
                    beginChecking(host, port, dialable, null, !ours);
                } else if (key.kind == ServerKey.Kind.UNREACHABLE) {
                    answered(false, null);
                } else if (key.kind == ServerKey.Kind.NO_KEY) {
                    answered(false, getString(R.string.Ice9ServerNoKey));
                } else if (!serverCode.equals(ServerCode.code(key.der))) {
                    answered(false, getString(R.string.Ice9ServerCodeMismatch));
                } else {
                    beginChecking(host, port, dialable, key.der, false);
                }
            });
        });
    }

    /**
     * Points the client at the address and asks it one question.
     *
     * Seeding costs nothing to undo here - nobody has signed in yet, so
     * there is no stored history keyed to an address, and a refusal simply
     * leaves the screen as it was. Unless it is where the client is already
     * pointed, which is the common case: the field is filled in with ours
     * and the answer is one tap. Seeding then would throw away a key just
     * agreed with this very server and make somebody wait through a second
     * handshake to be told what the client already knew.
     */
    private void beginChecking(String host, int port, String dialable, byte[] key, boolean probing) {
        boolean moved = !host.equals(ServerAddress.host()) || port != ServerAddress.port()
                || !java.util.Arrays.equals(key, ServerAddress.keyDer());
        checkingFrom = moved ? ServerAddress.kept() : null;
        if (moved) {
            ServerAddress.set(host, port, dialable, key);
            ConnectionsManager.reseedFromAddress(false);
        }
        if (probing) {
            // Only explains silence: the stand serves a key of its own and
            // still offers the built-in one, so the check decides.
            final int probed = attempt;
            Utilities.globalQueue.postRunnable(() -> {
                ServerKey.Answer answer = ServerKey.fetch(dialable, host, port);
                if (answer.kind == ServerKey.Kind.KEY && !ServerCode.BUILT_IN_KEY_CODE.equals(ServerCode.code(answer.der))) {
                    AndroidUtilities.runOnUIThread(() -> ownKeySeen |= probed == attempt);
                }
            });
        }

        checkingRequest = owner.connections().sendRequest(new TLRPC.TL_help_getConfig(),
                (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                    if (!nextPressed) {
                        return;
                    }
                    answered(response != null, error == null ? null : error.text);
                }),
                ConnectionsManager.RequestFlagWithoutLogin |
                        ConnectionsManager.RequestFlagEnableUnauthorized |
                        ConnectionsManager.RequestFlagFailOnServerErrors);

        // A wrong address does not refuse, it says nothing at all, and the
        // request would wait for as long as somebody is willing to look at
        // a spinner. Fifteen seconds is longer than any handshake we have
        // measured and short enough to be an answer. Started here rather
        // than before the lookup, so a slow name does not eat the wait.
        giveUp = () -> answered(false, null);
        AndroidUtilities.runOnUIThread(giveUp, 15000);
    }

    private void answered(boolean reached, String errorText) {
        if (!nextPressed) {
            return;
        }
        nextPressed = false;
        if (giveUp != null) {
            AndroidUtilities.cancelRunOnUIThread(giveUp);
            giveUp = null;
        }
        owner.hideProgress();

        if (reached) {
            showError(null);
            checkingFrom = null;
            ServerAddress.markChosen();
            owner.chosen();
            return;
        }

        if (checkingRequest != 0) {
            owner.connections().cancelRequest(checkingRequest, true);
            checkingRequest = 0;
        }
        // Put back what was there - what was there, not the default: from
        // Settings this screen is reached with somebody's own server behind
        // it, and answering the wrong one by moving them to ours would be a
        // change they did not ask for.
        if (checkingFrom != null) {
            checkingFrom.restore();
            ConnectionsManager.reseedFromAddress(false);
            checkingFrom = null;
        }
        showError(errorText != null && errorText.length() > 0
                ? errorText
                : getString(ownKeySeen ? R.string.Ice9ServerOwnKeyNeedsCode : R.string.Ice9ServerNoAnswer));
        updateDefaultButton();
    }

    @Override
    public void onHide() {
        super.onHide();
        nextPressed = false;
        checkingFrom = null;
        if (giveUp != null) {
            AndroidUtilities.cancelRunOnUIThread(giveUp);
            giveUp = null;
        }
    }
}

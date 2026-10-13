package org.telegram.ui.Components;

import androidx.annotation.NonNull;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MlsRuntime;
import org.telegram.utils.camera.roundvideo.RoundVideoSession;

import java.io.File;

/**
 * A round video in a chat that encrypts is uploaded whole, after recording (#80).
 *
 * Upstream's recorder hands every finished range of the file to the uploader
 * while it still records, so the plain bytes reach the server before the
 * message exists. The old recorder (InstantCameraView) did the same and is held
 * back the same way. Here the recorder gets no uploader at all: the finished
 * file goes through the ordinary send, where the chat's conversation encrypts
 * it like any other video (#257). tests/android/checks.py reads the line the
 * finished recording logs.
 */
final class MlsRoundVideo {
    private MlsRoundVideo() {
    }

    /** Upstream's live uploader, or nothing when the chat encrypts. */
    static TelegramRoundVideoUpload liveUpload(int currentAccount, long dialogId, boolean secretChat) {
        if (MlsRuntime.getInstance(currentAccount).encrypts(dialogId)) {
            FileLog.d("mls: a round video for " + dialogId + " uploads whole, after recording");
            return null;
        }
        return new TelegramRoundVideoUpload(currentAccount, secretChat);
    }

    /** What the recorder listens with: the uploader, or a listener that uploads nothing. */
    static RoundVideoSession.OutputListener listener(TelegramRoundVideoUpload upload) {
        if (upload != null) {
            return upload;
        }
        return new RoundVideoSession.OutputListener() {
            @Override
            public void onOutputCompleted(long outputId, @NonNull File file, long finalSize, long durationMs, boolean hasAudio) {
                FileLog.d("mls: a round recording finished whole at " + file);
            }
        };
    }
}

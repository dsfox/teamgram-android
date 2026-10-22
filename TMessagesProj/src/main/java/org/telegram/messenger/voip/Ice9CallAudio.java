package org.telegram.messenger.voip;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.util.List;

/**
 * What a private call did with sound, on this phone (#288).
 *
 * On 22 October a callee woken by the call push answered, heard the caller,
 * and was not heard; calling again worked. Nothing on either side said
 * whether the microphone gave silence, Android silenced the recording, or the
 * sound was lost on the way. This keeps the answer for every call: the levels
 * of this phone's microphone and of the other side as the engine reported
 * them, whether Android silenced the recording, and the state the app was in
 * when the call was answered. It goes into the app log and, under "ice9",
 * into the stats log the phone sends after the call (phone.saveCallDebug),
 * where the server writes it beside the route.
 */
public final class Ice9CallAudio {

    // tgcalls reports levels from 0 to 1; speech sits well above this, a
    // silenced or dead microphone at 0.
    private static final float HEARD = 0.01f;
    private static final long REPORT_EVERY_MS = 5000;

    private static long callId;
    private static boolean incoming;
    private static long processAgeAtStartMs = -1;
    private static boolean visibleAtAnswer;
    private static boolean screenOnAtAnswer;
    private static boolean serviceStartedByAnswer;
    private static int importanceAtAnswer;
    private static boolean micGranted;
    private static int foregroundTypes = -1;
    private static long answeredAt;
    private static long establishedAt;
    private static int samples;
    private static float micMax;
    private static float peerMax;
    private static int micHeard;
    private static int peerHeard;
    private static long micFirstMs = -1;
    private static long peerFirstMs = -1;
    private static String silenced = "unknown";
    private static long lastReport;

    private Ice9CallAudio() {
    }

    /** A call reached the service; everything before it is forgotten. */
    public static synchronized void started(long id, boolean isIncoming) {
        callId = id;
        incoming = isIncoming;
        processAgeAtStartMs = ApplicationLoader.startTime > 0 ? SystemClock.elapsedRealtime() - ApplicationLoader.startTime : -1;
        visibleAtAnswer = screenOnAtAnswer = serviceStartedByAnswer = micGranted = false;
        importanceAtAnswer = 0;
        foregroundTypes = -1;
        answeredAt = establishedAt = 0;
        samples = micHeard = peerHeard = 0;
        micMax = peerMax = 0;
        micFirstMs = peerFirstMs = -1;
        silenced = "unknown";
        lastReport = 0;
        FileLog.d("ice9 call audio: call " + id + (isIncoming ? " incoming" : " outgoing") + ", process up " + processAgeAtStartMs + " ms");
    }

    /** The person answered: the state Android judged the app by. */
    public static synchronized void answered(Context context, boolean startedByAnswer, int types) {
        answeredAt = SystemClock.elapsedRealtime();
        serviceStartedByAnswer = startedByAnswer;
        visibleAtAnswer = !ApplicationLoader.mainInterfacePaused;
        screenOnAtAnswer = ApplicationLoader.isScreenOn;
        foregroundTypes = types;
        micGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        try {
            ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(info);
            importanceAtAnswer = info.importance;
        } catch (Throwable ignore) {
        }
        FileLog.d("ice9 call audio: answered, visible=" + visibleAtAnswer + " screenOn=" + screenOnAtAnswer
                + " startedByAnswer=" + startedByAnswer + " importance=" + importanceAtAnswer
                + " fgsTypes=0x" + Integer.toHexString(types) + " micGranted=" + micGranted);
    }

    /** The call connected: from here a silent microphone is a fault. */
    public static synchronized void established(Context context) {
        if (establishedAt == 0) {
            establishedAt = SystemClock.elapsedRealtime();
        }
        checkSilenced(context);
        FileLog.d("ice9 call audio: established, recording silenced=" + silenced);
    }

    /** One report of levels from the engine: this phone's microphone, the other side. */
    public static synchronized void levels(Context context, float mine, float theirs) {
        samples++;
        long now = SystemClock.elapsedRealtime();
        long since = establishedAt != 0 ? now - establishedAt : -1;
        micMax = Math.max(micMax, mine);
        peerMax = Math.max(peerMax, theirs);
        if (mine > HEARD) {
            micHeard++;
            if (micFirstMs < 0) {
                micFirstMs = since;
            }
        }
        if (theirs > HEARD) {
            peerHeard++;
            if (peerFirstMs < 0) {
                peerFirstMs = since;
            }
        }
        if (now - lastReport >= REPORT_EVERY_MS) {
            lastReport = now;
            checkSilenced(context);
            FileLog.d("ice9 call audio: " + summaryLine());
        }
    }

    /** The stats log the phone sends, with this call's summary under "ice9". */
    public static synchronized String into(String statsLog) {
        JSONObject summary = summary();
        FileLog.d("ice9 call audio: ended, " + summaryLine());
        if (summary == null) {
            return statsLog;
        }
        try {
            JSONObject log = TextUtils.isEmpty(statsLog) ? new JSONObject() : new JSONObject(statsLog);
            log.put("ice9", summary);
            return log.toString();
        } catch (Throwable e) {
            FileLog.e(e);
            return statsLog;
        }
    }

    private static void checkSilenced(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            List<AudioRecordingConfiguration> active = am.getActiveRecordingConfigurations();
            boolean any = false;
            boolean quiet = false;
            for (AudioRecordingConfiguration c : active) {
                if (c.getClientAudioSource() != MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                    continue;
                }
                any = true;
                quiet |= c.isClientSilenced();
            }
            if (!any) {
                if ("unknown".equals(silenced)) {
                    silenced = "not recording";
                }
            } else if (quiet) {
                silenced = "yes";
            } else if (!"yes".equals(silenced)) {
                silenced = "no";
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static JSONObject summary() {
        if (callId == 0) {
            return null;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("build", BuildVars.BUILD_VERSION_STRING);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            o.put("incoming", incoming);
            o.put("process_ms", processAgeAtStartMs);
            o.put("answer_visible", visibleAtAnswer);
            o.put("answer_screen_on", screenOnAtAnswer);
            o.put("answer_started_service", serviceStartedByAnswer);
            o.put("answer_importance", importanceAtAnswer);
            o.put("answer_to_connect_ms", answeredAt != 0 && establishedAt != 0 ? establishedAt - answeredAt : -1);
            o.put("fgs_types", foregroundTypes);
            o.put("mic_granted", micGranted);
            o.put("silenced", silenced);
            o.put("samples", samples);
            o.put("mic_max", micMax);
            o.put("mic_heard", micHeard);
            o.put("mic_first_ms", micFirstMs);
            o.put("peer_max", peerMax);
            o.put("peer_heard", peerHeard);
            o.put("peer_first_ms", peerFirstMs);
            return o;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static String summaryLine() {
        return "call " + callId + " samples=" + samples + " mic max=" + micMax + " heard=" + micHeard
                + " first=" + micFirstMs + "ms, peer max=" + peerMax + " heard=" + peerHeard
                + " first=" + peerFirstMs + "ms, silenced=" + silenced;
    }
}

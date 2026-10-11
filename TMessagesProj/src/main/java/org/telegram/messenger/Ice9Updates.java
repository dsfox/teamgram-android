package org.telegram.messenger;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.ConnectivityManager;
import android.os.Build;
import android.util.Log;

import org.telegram.ui.Components.ForegroundDetector;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * The site's Android build keeps itself up to date.
 *
 * Whoever installed ice9.apk from get.ice9.app or ice9.ru heard of no newer
 * build ever: upstream's own check is for its standalone and beta builds, and
 * our server has no update to name. The site already names its build in
 * ice9.version, beside the APK, so the app reads that (UpdateSource decides
 * which host first), downloads a newer APK on an unmetered network, checks it
 * is ours - package, build number, signing certificate - and installs it when
 * the app goes to the background, the owner's choice: a person typing a
 * message is never thrown out of the app.
 *
 * On Android 12 and later an app updating itself needs no confirmation, once
 * the person has allowed ice9 to install apps; otherwise Android asks, and
 * the question is put the next time the app comes to the front. The debug
 * (.beta) and standalone (.web) builds are not the site's and leave this alone.
 */
public final class Ice9Updates implements ForegroundDetector.Listener {

    private static final String TAG = "ice9-update";
    private static final String SITE_PACKAGE = "app.twobytes.android";
    private static final String RESULT = "app.ice9.UPDATE_RESULT";
    private static final long FIRST_CHECK_MS = 30 * 1000L;
    private static final long CHECK_EVERY_MS = 6 * 60 * 60 * 1000L;

    private static Ice9Updates instance;

    private final Context context;
    private final File folder;
    private volatile File ready;
    private volatile long readyBuild;
    private volatile Intent confirmation;
    private volatile boolean installing;
    private volatile long declinedBuild;
    private volatile boolean allowedWhenAsked;

    private Ice9Updates(Context context) {
        this.context = context;
        this.folder = new File(context.getCacheDir(), "update");
    }

    /** Started once per process, from ApplicationLoader. */
    public static void start(Context context) {
        if (instance != null || !SITE_PACKAGE.equals(context.getPackageName())) {
            return;
        }
        instance = new Ice9Updates(context.getApplicationContext());
        ForegroundDetector detector = ForegroundDetector.getInstance();
        if (detector != null) {
            detector.addListener(instance);
        }
        instance.registerResult();
        Utilities.globalQueue.postRunnable(instance::checkAndReschedule, FIRST_CHECK_MS);
    }

    private static void say(String line) {
        Log.i(TAG, line);
        FileLog.d("ice9 update: " + line);
    }

    private void checkAndReschedule() {
        try {
            check();
        } catch (Throwable failure) {
            say("check failed: " + failure);
        }
        Utilities.globalQueue.postRunnable(this::checkAndReschedule, CHECK_EVERY_MS);
    }

    private void check() throws Exception {
        long installed = installedBuild();
        List<String> hosts = UpdateSource.hostsFor(language());
        long offered = -1;
        for (String host : hosts) {
            String text = fetchText(host + UpdateSource.VERSION_FILE, installed);
            offered = UpdateSource.buildIn(text);
            say(host + UpdateSource.VERSION_FILE + " says " + (offered > 0 ? offered : "nothing"));
            if (offered > 0) {
                break;
            }
        }
        if (!UpdateSource.isNewer(offered, installed)) {
            say("installed " + installed + ", offered " + offered + ": nothing to do");
            clearFolder(null);
            return;
        }
        if (ready != null && readyBuild == offered) {
            say("build " + offered + " is downloaded and waits for the background");
            return;
        }
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity != null && connectivity.isActiveNetworkMetered()) {
            say("build " + offered + " is out, not downloaded on a metered network");
            return;
        }
        File apk = new File(folder, "ice9-" + offered + ".apk");
        clearFolder(apk);
        if (!apk.exists()) {
            for (String host : hosts) {
                if (download(host + UpdateSource.APK_FILE, apk, installed)) {
                    break;
                }
            }
        }
        if (!apk.exists()) {
            say("build " + offered + " could not be downloaded from any host");
            return;
        }
        if (!isOursAndOffered(apk, offered)) {
            apk.delete();
            return;
        }
        ready = apk;
        readyBuild = offered;
        say("build " + offered + " downloaded and checked; installs when the app is in the background");
        ForegroundDetector detector = ForegroundDetector.getInstance();
        if (detector != null && detector.isBackground()) {
            install();
        }
    }

    private long installedBuild() throws PackageManager.NameNotFoundException {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static String language() {
        LocaleController.LocaleInfo current = LocaleController.getInstance().getCurrentLocaleInfo();
        return current != null && current.shortName != null ? current.shortName : Locale.getDefault().getLanguage();
    }

    private static HttpURLConnection open(String address, long installed) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "ice9-android/" + installed);
        return connection;
    }

    private static String fetchText(String address, long installed) {
        HttpURLConnection connection = null;
        try {
            connection = open(address, installed);
            int code = connection.getResponseCode();
            if (code != 200) {
                say(address + " answered " + code);
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[64];
                int read;
                while ((read = in.read(buffer)) > 0 && out.size() < 64) {
                    out.write(buffer, 0, read);
                }
                return out.toString("UTF-8");
            }
        } catch (Exception unreachable) {
            say(address + " did not answer: " + unreachable);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean download(String address, File into, long installed) {
        File part = new File(into.getPath() + ".part");
        HttpURLConnection connection = null;
        try {
            connection = open(address, installed);
            int code = connection.getResponseCode();
            if (code != 200) {
                say(address + " answered " + code);
                return false;
            }
            long written = 0;
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    written += read;
                }
            }
            if (!part.renameTo(into)) {
                say("could not keep " + into);
                return false;
            }
            say("downloaded " + written + " bytes from " + address);
            return true;
        } catch (Exception broken) {
            say(address + " broke off: " + broken);
            part.delete();
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void clearFolder(File keep) {
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (keep == null || !file.equals(keep)) {
                    file.delete();
                }
            }
        }
        if (keep != null) {
            folder.mkdirs();
        }
    }

    @SuppressWarnings("deprecation")
    private boolean isOursAndOffered(File apk, long offered) throws PackageManager.NameNotFoundException {
        PackageManager packages = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = packages.getPackageArchiveInfo(apk.getPath(), flags);
        if (archive == null) {
            say(apk + " is not a package");
            return false;
        }
        long archiveBuild = Build.VERSION.SDK_INT >= 28 ? archive.getLongVersionCode() : archive.versionCode;
        if (!UpdateSource.isTheOfferedBuild(archive.packageName, archiveBuild, context.getPackageName(), offered)) {
            say("the archive is " + archive.packageName + " " + archiveBuild + ", not build " + offered + " of ours");
            return false;
        }
        PackageInfo installed = packages.getPackageInfo(context.getPackageName(), flags);
        if (!new HashSet<>(Arrays.asList(signers(archive))).equals(new HashSet<>(Arrays.asList(signers(installed))))) {
            say("the archive is signed by somebody else");
            return false;
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signers(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            return info.signingInfo.getApkContentsSigners();
        }
        return info.signatures != null ? info.signatures : new Signature[0];
    }

    private void install() {
        File apk = ready;
        if (apk == null || installing || readyBuild == declinedBuild) {
            return;
        }
        installing = true;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                PackageInstaller installer = context.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(context.getPackageName());
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                }
                int sessionId = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                    try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("ice9.apk", 0, apk.length())) {
                        byte[] buffer = new byte[1 << 16];
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            out.write(buffer, 0, read);
                        }
                        session.fsync(out);
                    }
                    Intent result = new Intent(RESULT).setPackage(context.getPackageName());
                    int mutable = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
                    PendingIntent callback = PendingIntent.getBroadcast(context, sessionId, result, PendingIntent.FLAG_UPDATE_CURRENT | mutable);
                    say("installing build " + readyBuild + " (session " + sessionId + ")");
                    session.commit(callback.getIntentSender());
                }
            } catch (Throwable failure) {
                say("install failed to start: " + failure);
                installing = false;
            }
        });
    }

    private void registerResult() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context unused, Intent intent) {
                onResult(intent);
            }
        };
        IntentFilter filter = new IntentFilter(RESULT);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    @SuppressWarnings("deprecation")
    private void onResult(Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            allowedWhenAsked = mayInstall();
            confirmation = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : intent.getParcelableExtra(Intent.EXTRA_INTENT);
            say("Android asks the person first; asked when the app is next in front");
            ForegroundDetector detector = ForegroundDetector.getInstance();
            if (detector != null && !detector.isBackground()) {
                onBecameForeground();
            }
            return;
        }
        installing = false;
        if (status == PackageInstaller.STATUS_SUCCESS) {
            say("installed build " + readyBuild);
            return;
        }
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            if (!UpdateSource.isRefusal(allowedWhenAsked, mayInstall())) {
                say("installing was allowed on the way; build " + readyBuild + " installs once the app is in the background");
                ForegroundDetector detector = ForegroundDetector.getInstance();
                if (detector != null && detector.isBackground()) {
                    install();
                }
                return;
            }
            declinedBuild = readyBuild;
            say("the person declined build " + readyBuild + "; not asked again until the app starts again");
            return;
        }
        say("install of build " + readyBuild + " failed: " + status + " " + message);
        File apk = ready;
        ready = null;
        if (apk != null) {
            apk.delete();
        }
    }

    /** Whether the person allowed ice9 to install apps; before Android 8 that is one switch for every app. */
    private boolean mayInstall() {
        return Build.VERSION.SDK_INT < 26 || context.getPackageManager().canRequestPackageInstalls();
    }

    @Override
    public void onBecameForeground() {
        if (ready != null) {
            say("the app is in front");
        }
        Intent question = confirmation;
        if (question == null) {
            return;
        }
        confirmation = null;
        try {
            question.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(question);
        } catch (Exception failure) {
            say("could not ask: " + failure);
            installing = false;
        }
    }

    @Override
    public void onBecameBackground() {
        if (ready != null) {
            say("the app is in the background" + (installing ? ", an install is under way" : ""));
        }
        install();
    }
}

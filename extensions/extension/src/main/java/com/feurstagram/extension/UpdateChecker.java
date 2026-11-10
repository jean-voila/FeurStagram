package com.feurstagram.extension;

import android.app.Dialog;
import android.app.Activity;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Scanner;
import java.util.Set;
import java.util.Collections;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Checks GitHub for a newer Feurstagram release and, if found, shows what changed
 * and installs it in place — no browser required.
 *
 * The flow is three screens: a full-screen "update available" page carrying the
 * release's own notes, a full-screen progress page while the APK downloads and is
 * staged, and then Android's own install confirmation. Neither of ours is a popup
 * — an update is a task, not an interruption.
 *
 * The download is done here rather than handed to the system
 * {@code DownloadManager} so its progress can be shown in the page and cancelled;
 * it lives on a background thread tied to the application context, so leaving the
 * page (back gesture included) does not stop it — it carries on behind an ongoing
 * notification that mirrors the same progress and offers the same Cancel. Staging
 * goes through {@link PackageInstaller}. Anything that goes wrong falls back to
 * opening the release page.
 */
public final class UpdateChecker implements Runnable {

    private static final String REPO = "jean-voila/Feurstagram";
    private static final String LATEST_API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String LATEST_PAGE = "https://github.com/" + REPO + "/releases/latest";
    private static final String RELEASE_BY_TAG = "https://api.github.com/repos/" + REPO + "/releases/tags/";
    private static final String USER_AGENT = "Feurstagram-UpdateCheck";

    /** Action prefix for the per-session PackageInstaller status callback. */
    private static final String INSTALL_ACTION = "com.feurstagram.extension.INSTALL_STATUS";

    /**
     * Where the download lands: the app's internal cache. Up to Android 9 the
     * app-specific external directory is writable by any app holding the storage
     * permission, which could swap the file between its download (and digest
     * check) and its staging. The system may reclaim a leftover file.
     */
    private static final String UPDATE_FILE_NAME = "feurstagram-update.apk";

    private static boolean launchWatcherInstalled;
    private static final AtomicBoolean automaticCheckRunning = new AtomicBoolean();
    private static WeakReference<Activity> foregroundActivity = new WeakReference<>(null);
    private static WeakReference<Dialog> availablePrompt = new WeakReference<>(null);
    private static boolean whatsNewChecked;

    /**
     * One update at a time, process-wide. Without this a second run can create a
     * second installer session, and Android then queues a second confirmation
     * dialog behind the first — which is what made an install look like it failed
     * right after succeeding.
     */
    private static volatile boolean updateInFlight;

    private final Context context;
    /** Manual checks always run and report the outcome (up-to-date / failure). */
    private final boolean manual;

    private UpdateChecker(Context context, boolean manual) {
        this.context = context;
        this.manual = manual;
    }

    /** Install launch observation once, including returns to a still-running app. */
    public static void check(Context context) {
        if (!(context instanceof Activity) || launchWatcherInstalled) return;
        Activity activity = (Activity) context;
        launchWatcherInstalled = true;
        foregroundActivity = new WeakReference<>(activity);
        activity.getApplication().registerActivityLifecycleCallbacks(new LaunchWatcher(activity));
        checkAutomatically(activity);
    }

    private static void checkAutomatically(Activity activity) {
        if (!Config.isAutoUpdateEnabled() || !automaticCheckRunning.compareAndSet(false, true)) return;
        if (DebugBridge.isNetworkTraceEnabled()) Log.i("FeurUpdate", "automatic check started");
        new Thread(new UpdateChecker(activity, false), "FeurStagram-update-check").start();
    }

    private static final class LaunchWatcher implements Application.ActivityLifecycleCallbacks {
        private final Set<Activity> started = Collections.newSetFromMap(new WeakHashMap<>());
        private boolean foreground = true;
        private boolean checkOnResume;

        LaunchWatcher(Activity initial) {
            // The tab-bar hook may run before or after the first onStart. A set
            // avoids counting that same activity twice in either ordering.
            started.add(initial);
        }

        @Override public void onActivityStarted(Activity activity) {
            if (!foreground && started.isEmpty()) {
                foreground = true;
                checkOnResume = true;
            }
            started.add(activity);
        }

        @Override public void onActivityResumed(Activity activity) {
            foregroundActivity = new WeakReference<>(activity);
            if (checkOnResume) {
                checkOnResume = false;
                checkAutomatically(activity);
            }
        }

        @Override public void onActivityPaused(Activity activity) {
            if (foregroundActivity.get() == activity) foregroundActivity.clear();
        }

        @Override public void onActivityStopped(Activity activity) {
            started.remove(activity);
            if (started.isEmpty() && !activity.isChangingConfigurations()) foreground = false;
        }

        @Override public void onActivityDestroyed(Activity activity) { started.remove(activity); }
        @Override public void onActivityCreated(Activity activity, Bundle state) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    }

    /**
     * Run an on-demand check (from the settings menu), regardless of the
     * auto-update toggle or whether a check already ran this process. Always
     * tells the user the result.
     */
    public static void checkNow(Context context) {
        if (context == null) return;
        Toast.makeText(context, "Checking for updates…", Toast.LENGTH_SHORT).show();
        new Thread(new UpdateChecker(context, true)).start();
    }

    @Override
    public void run() {
        Handler ui = new Handler(Looper.getMainLooper());
        try {
            String body = fetch(LATEST_API);

            JSONObject json = new JSONObject(body);
            String tag = string(json, "tag_name");
            if (tag == null) {
                if (manual) ui.post(() -> toast("Update check failed"));
                return;
            }

            String installed = installedVersion();
            if (isNewer(normalize(tag), installed)) {
                final Release release = Release.parse(json, isCloneBuild());
                ui.post(() -> {
                    Context target = manual ? context : foregroundActivity.get();
                    if (target instanceof Activity
                            && !((Activity) target).isFinishing() && !((Activity) target).isDestroyed()) {
                        showPrompt(target, release, installed);
                    }
                });
            } else if (manual) {
                ui.post(() -> toast("FeurStagram is up to date (" + installed + ")"));
            }
        } catch (Throwable t) {
            // Network/parse failures are non-fatal: silent for auto checks,
            // reported for manual ones.
            if (manual) ui.post(() -> toast("Update check failed"));
        } finally {
            if (!manual) {
                automaticCheckRunning.set(false);
                if (DebugBridge.isNetworkTraceEnabled()) Log.i("FeurUpdate", "automatic check finished");
            }
        }
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setUseCaches(false);
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        try (InputStream input = connection.getInputStream();
             Scanner scanner = new Scanner(input, "UTF-8").useDelimiter("\\A")) {
            return scanner.hasNext() ? scanner.next() : "";
        } finally {
            connection.disconnect();
        }
    }

    private void toast(String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    /** True when this is the side-by-side clone package (asset name ends in -clone.apk). */
    private boolean isCloneBuild() {
        String pkg = context.getPackageName();
        return pkg != null && pkg.endsWith(".feurstagram");
    }

    /** Everything the prompt and the download need about the release on offer. */
    static final class Release {
        final String tag;
        final String notes;
        final String apkUrl;
        final long size;
        /** Lowercase hex SHA-256 of the asset, as GitHub reports it, or null. */
        final String sha256;

        Release(String tag, String notes, String apkUrl, long size, String sha256) {
            this.tag = tag;
            this.notes = notes;
            this.apkUrl = apkUrl;
            this.size = size;
            this.sha256 = sha256;
        }

        /**
         * Read a release from GitHub's release JSON, with the .apk asset this build
         * downloads: the "-clone.apk" asset for a clone install, the plain ".apk"
         * one otherwise. Without such an asset, apkUrl and sha256 are null and size
         * is -1.
         */
        static Release parse(JSONObject json, boolean clone) {
            JSONObject asset = apkAsset(json, clone);
            return new Release(
                    string(json, "tag_name"),
                    releaseNotes(json),
                    githubDownload(string(asset, "browser_download_url")),
                    asset == null ? -1 : asset.optLong("size", -1),
                    sha256(string(asset, "digest")));
        }
    }

    // --- Release JSON -------------------------------------------------------

    /**
     * A string field, or null when the object or the field is missing or the value
     * is JSON null (optString alone would return the text "null" for that).
     */
    static String string(JSONObject object, String key) {
        return object == null || object.isNull(key) ? null : object.optString(key, null);
    }

    /** The release description (Markdown, CRLF turned into LF), or null when blank. */
    static String releaseNotes(JSONObject json) {
        String body = string(json, "body");
        if (body == null || body.trim().isEmpty()) return null;
        return body.replace("\r", "");
    }

    /** The .apk asset matching this build (see {@link Release#parse}), or null. */
    static JSONObject apkAsset(JSONObject json, boolean clone) {
        JSONArray assets = json == null ? null : json.optJSONArray("assets");
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            String url = string(asset, "browser_download_url");
            if (url == null || !url.endsWith(".apk")) continue;
            if (url.endsWith("-clone.apk") == clone) return asset;
        }
        return null;
    }

    /**
     * The asset URL if it is an HTTPS download from github.com, else null: then
     * the prompt offers the release page instead of downloading. GitHub's asset
     * URLs always have this form; it redirects from there to its storage over
     * HTTPS, which HttpURLConnection follows (it never follows a switch to HTTP).
     */
    static String githubDownload(String url) {
        if (url == null) return null;
        try {
            URI uri = new URI(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && "github.com".equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443) ? url : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * GitHub's asset digest, "sha256:&lt;hex&gt;", as lowercase hex. Null for a
     * missing digest (older releases have none) or any other form, in which case
     * the download is not checked.
     */
    static String sha256(String digest) {
        if (digest == null || !digest.startsWith("sha256:")) return null;
        String hex = digest.substring("sha256:".length()).trim().toLowerCase(Locale.ROOT);
        return hex.matches("[0-9a-f]{64}") ? hex : null;
    }

    static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    /** "v434-0-0-44-74" -> "434.0.0.44.74" so it matches the installed versionName. */
    static String normalize(String tag) {
        if (tag == null) return "";
        String value = tag.trim();
        if (value.startsWith("v") || value.startsWith("V")) {
            value = value.substring(1);
        }
        return value.replace('-', '.');
    }

    /** Installed versionName (Instagram's), e.g. "435.0.0.37.76". "0" on failure. */
    private String installedVersion() {
        // A debug build reports an ancient version so the whole update flow can be
        // exercised against the real release. Always null in a release build.
        String pretend = DebugBridge.pretendInstalledVersion();
        if (pretend != null) return pretend;
        String real = realInstalledVersion(context);
        return real == null ? "0" : real;
    }

    /**
     * The genuine installed versionName, never the debug pretence: "What's new"
     * looks up a release by this exact tag, so a made-up version would only ever
     * 404.
     */
    private static String realInstalledVersion(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return null;
        }
    }

    /** True if dotted version `latest` is strictly greater than `installed`. */
    static boolean isNewer(String latest, String installed) {
        String[] a = latest.split("\\.");
        String[] b = installed.split("\\.");
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? parse(a[i]) : 0;
            int y = i < b.length ? parse(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int parse(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    // --- "What's new" after an update -------------------------------------------

    /**
     * Show the release notes of the version that is running, once, the first time
     * the app starts after it changed. A fresh install shows nothing — there is no
     * "new" to report — it just records where it started from.
     *
     * Deliberately not gated on the automatic-update-check toggle: that toggle is
     * about being nagged to update, not about being told what an update did.
     */
    public static void checkWhatsNew(final Context context) {
        if (context == null || whatsNewChecked) return;
        whatsNewChecked = true;
        final String installed = realInstalledVersion(context);
        if (installed == null) return;
        String seen = Config.getLastSeenVersion();
        if (seen == null) {
            Config.setLastSeenVersion(installed);
            return;
        }
        if (seen.equals(installed)) return;
        new Thread(() -> loadNotes(context, "v" + installed.replace('.', '-'), installed, true),
                "feurstagram-whatsnew").start();
    }

    /**
     * Fetch one release's notes by tag and show them.
     *
     * @param markSeen record the version afterwards so this only ever runs once.
     *                 A missing release (a self-built APK, a version never
     *                 published) counts as done; a network failure does not, so
     *                 the next launch tries again.
     */
    private static void loadNotes(Context context, String tag, String version, boolean markSeen) {
        Handler ui = new Handler(Looper.getMainLooper());
        try {
            final String notes = releaseNotes(new JSONObject(fetch(RELEASE_BY_TAG + tag)));
            if (notes != null) {
                ui.post(() -> showWhatsNew(context, version, notes));
            }
            if (markSeen) Config.setLastSeenVersion(version);
        } catch (java.io.FileNotFoundException missing) {
            // No release published under that tag: nothing to show, ever.
            if (markSeen) Config.setLastSeenVersion(version);
        } catch (Throwable t) {
            // Offline or rate-limited: leave it for the next launch.
        }
    }

    /** Development hook: show the latest release's notes on demand. */
    static void showLatestNotes(final Context context) {
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject(fetch(LATEST_API));
                String tag = string(json, "tag_name");
                String notes = releaseNotes(json);
                if (notes == null) return;
                new Handler(Looper.getMainLooper())
                        .post(() -> showWhatsNew(context, tag == null ? "" : tag, notes));
            } catch (Throwable ignored) {
            }
        }, "feurstagram-whatsnew-debug").start();
    }

    private static void showWhatsNew(Context context, String version, String notes) {
        try {
            Dialog dialog = new Dialog(context);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            FrameLayout frame = Settings.cardFrame(context);
            LinearLayout card = Settings.addCard(context, frame);

            TextView title = new TextView(context);
            title.setText("What's new");
            Settings.titleLarge(title);
            card.addView(title);

            TextView subtitle = new TextView(context);
            subtitle.setText("FeurStagram " + version);
            Settings.body(subtitle);
            subtitle.setPadding(0, Settings.dp(context, 4), 0, 0);
            card.addView(subtitle);

            ScrollView scroll = new ScrollView(context);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setVerticalFadingEdgeEnabled(true);
            scroll.setFadingEdgeLength(Settings.dp(context, 24));
            LinearLayout body = new LinearLayout(context);
            body.setOrientation(LinearLayout.VERTICAL);
            renderNotes(context, body, notes);
            scroll.addView(body);
            card.addView(scroll, marginParams(context, ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 8));
            Settings.boundToFrame(frame, card, scroll);

            Button done = Settings.makeButton(context, "Got it", Settings.PRIMARY, Settings.ON_PRIMARY, true);
            done.setOnClickListener(v -> dialog.dismiss());
            card.addView(done, marginParams(context, ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 20));

            dialog.setContentView(frame);
            dialog.setCanceledOnTouchOutside(true);
            Settings.styleWindow(dialog, 0, 0.6f);
            dialog.show();
        } catch (Throwable ignored) {
        }
    }

    // --- "Update available" page ----------------------------------------------

    private static void showPrompt(Context context, Release release, String installed) {
        try {
            Dialog existing = availablePrompt.get();
            if (updateInFlight || (existing != null && existing.isShowing())) return;
            Settings.Page page = Settings.newPage(context);
            Settings.addPageTitle(page, "Update available",
                    "FeurStagram " + release.tag + "  ·  you have " + installed);

            final Dialog dialog = Settings.newPageDialog(context, page.root);
            dialog.setOnDismissListener(d -> {
                if (availablePrompt.get() == dialog) availablePrompt.clear();
            });

            if (release.notes != null) {
                LinearLayout notes = new LinearLayout(context);
                notes.setOrientation(LinearLayout.VERTICAL);
                notes.setBackground(Settings.roundedRect(Settings.SURFACE_CONTAINER, 24f, context));
                int pad = Settings.dp(context, 20);
                notes.setPadding(pad, Settings.dp(context, 8), pad, pad);
                renderNotes(context, notes, release.notes);
                page.content.addView(notes, marginParams(context,
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 16));
            }

            boolean canInstall = release.apkUrl != null;
            if (!canInstall) {
                TextView note = new TextView(context);
                note.setText("No installable asset in this release — open the release page instead.");
                Settings.body(note);
                page.content.addView(note, marginParams(context,
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 16));
            }

            Button later = Settings.makeButton(context, "Later", 0, Settings.ON_SURFACE, false);
            later.setBackground(Settings.outlined(context, Settings.OUTLINE_VARIANT));
            later.setOnClickListener(v -> dialog.dismiss());
            page.addAction(later);

            Button update = Settings.makeButton(context,
                    canInstall ? "Update" : "Release page", Settings.PRIMARY, Settings.ON_PRIMARY, true);
            update.setOnClickListener(v -> {
                if (!canInstall) {
                    dialog.dismiss();
                    openReleasePage(context);
                    return;
                }
                // The system installer needs "install unknown apps" for this app.
                // Send the user to grant it and let them tap Update again, keeping
                // the page up so the flow can resume.
                if (!canRequestInstall(context)) {
                    requestInstallPermission(context);
                    Toast.makeText(context,
                            "Allow installing apps, then tap Update again.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                dialog.dismiss();
                Updater.start(context, release);
            });
            page.addAction(update);

            dialog.show();
            availablePrompt = new WeakReference<>(dialog);
        } catch (Throwable ignored) {
        }
    }

    private static LinearLayout.LayoutParams marginParams(Context context, int width, int height, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, height);
        lp.setMargins(0, Settings.dp(context, topDp), 0, 0);
        return lp;
    }

    /**
     * Render the release's Markdown as a small stack of text views: headings in
     * the surface colour, everything else in the muted one, bullets normalised.
     * Deliberately shallow — this is a changelog, not a document viewer.
     */
    static void renderNotes(Context context, LinearLayout parent, String markdown) {
        for (String rawLine : markdown.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("---")) continue;

            boolean heading = line.startsWith("#");
            while (line.startsWith("#")) line = line.substring(1);
            line = line.trim();
            if (line.startsWith("- ") || line.startsWith("* ")) {
                line = "•  " + line.substring(2);
            }
            line = stripInline(line);
            if (line.isEmpty()) continue;

            TextView view = new TextView(context);
            view.setText(line);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, heading ? 16f : 14f);
            view.setTextColor(heading ? Settings.ON_SURFACE : Settings.ON_SURFACE_VARIANT);
            view.setLineSpacing(0f, 1.15f);
            if (heading) {
                view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                view.setLetterSpacing(-0.01f);
            }
            view.setPadding(0, Settings.dp(context, heading ? 16 : 6), 0, 0);
            parent.addView(view);
        }
    }

    /** Drop the inline Markdown syntax that would otherwise show up as noise. */
    static String stripInline(String line) {
        String out = line.replace("**", "").replace("`", "");
        // [label](url) -> label
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (c != '[') {
                sb.append(c);
                continue;
            }
            int close = out.indexOf(']', i);
            if (close < 0 || close + 1 >= out.length() || out.charAt(close + 1) != '(') {
                sb.append(c);
                continue;
            }
            int end = out.indexOf(')', close);
            if (end < 0) {
                sb.append(c);
                continue;
            }
            sb.append(out, i + 1, close);
            i = end;
        }
        return sb.toString().trim();
    }

    // --- Download + install -------------------------------------------------

    /**
     * Owns one update attempt: the progress card, the download thread, and the
     * handover to {@link PackageInstaller}. One instance exists at a time, guarded
     * by {@link #updateInFlight}.
     */
    private static final class Updater {

        /** Ongoing-download notification. One at a time, so one id is enough. */
        private static final int NOTIFICATION_ID = 0x4645_5552;
        private static final String CHANNEL_ID = "feurstagram_update";
        private static final String CANCEL_ACTION = "com.feurstagram.extension.UPDATE_CANCEL";

        private final Context context;
        private final Context app;
        private final Release release;
        private final Handler ui = new Handler(Looper.getMainLooper());

        private Dialog dialog;
        private ProgressBar bar;
        private TextView status;
        private TextView detail;

        private BroadcastReceiver cancelReceiver;
        private long lastNotified;

        private volatile boolean cancelled;

        private Updater(Context context, Release release) {
            this.context = context;
            this.app = context.getApplicationContext();
            this.release = release;
        }

        static void start(Context context, Release release) {
            if (updateInFlight) {
                Toast.makeText(context, "An update is already in progress", Toast.LENGTH_SHORT).show();
                return;
            }
            updateInFlight = true;
            Updater updater = new Updater(context, release);
            updater.registerCancelAction();
            updater.showProgressPage();
            new Thread(updater::download, "feurstagram-update").start();
        }

        /** Tear down everything on-screen and in the shade. */
        private void finish() {
            updateInFlight = false;
            try {
                NotificationManager manager = app.getSystemService(NotificationManager.class);
                if (manager != null) manager.cancel(NOTIFICATION_ID);
            } catch (Throwable ignored) {
            }
            if (cancelReceiver != null) {
                try {
                    app.unregisterReceiver(cancelReceiver);
                } catch (Throwable ignored) {
                }
                cancelReceiver = null;
            }
            ui.post(this::dismissPage);
        }

        private void dismissPage() {
            if (dialog == null) return;
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {
            }
            dialog = null;
            bar = null;
            status = null;
            detail = null;
        }

        // --- the progress page ---

        private void showProgressPage() {
            try {
                Settings.Page page = Settings.newPage(context);
                Settings.addPageTitle(page, "Updating FeurStagram",
                        "FeurStagram " + release.tag);

                LinearLayout block = new LinearLayout(context);
                block.setOrientation(LinearLayout.VERTICAL);
                block.setBackground(Settings.roundedRect(Settings.SURFACE_CONTAINER, 24f, context));
                int pad = Settings.dp(context, 24);
                block.setPadding(pad, pad, pad, pad);
                page.content.addView(block, marginParams(context,
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 20));

                status = new TextView(context);
                status.setText("Starting download…");
                Settings.titleLarge(status);
                status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
                block.addView(status);

                bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
                bar.setMax(1000);
                bar.setIndeterminate(true);
                bar.setProgressDrawable(progressTrack(context));
                bar.setIndeterminateTintList(ColorStateList.valueOf(Settings.PRIMARY));
                block.addView(bar, marginParams(context, ViewGroup.LayoutParams.MATCH_PARENT,
                        Settings.dp(context, 10), 20));

                detail = new TextView(context);
                detail.setText(release.size > 0 ? "0 B / " + human(release.size) : "");
                Settings.body(detail);
                detail.setPadding(0, Settings.dp(context, 14), 0, 0);
                block.addView(detail);

                TextView hint = new TextView(context);
                hint.setText("You can leave this page — the download carries on in a notification.");
                Settings.body(hint);
                page.content.addView(hint, marginParams(context,
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 20));

                Button cancel = Settings.makeButton(context, "Cancel download", 0, Settings.ON_SURFACE, false);
                cancel.setBackground(Settings.outlined(context, Settings.OUTLINE_VARIANT));
                cancel.setOnClickListener(v -> requestCancel());
                page.addAction(cancel);

                Dialog d = Settings.newPageDialog(context, page.root);
                // Leaving the page only detaches the UI: the thread and the
                // notification carry the download on.
                d.setOnCancelListener(x -> {
                    dismissPage();
                    if (!cancelled) {
                        Toast.makeText(app, "Download continues in the notification",
                                Toast.LENGTH_SHORT).show();
                    }
                });
                d.show();
                dialog = d;
            } catch (Throwable ignored) {
                // No window to attach to: the download still runs, with the
                // notification as its only surface.
            }
        }

        /** A monochrome M3-style track: rounded, white fill. */
        private static Drawable progressTrack(Context context) {
            Drawable track = Settings.roundedRect(Settings.OUTLINE_VARIANT, 8f, context);
            Drawable fill = Settings.roundedRect(Settings.PRIMARY, 8f, context);
            ClipDrawable clip = new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL);
            LayerDrawable layers = new LayerDrawable(new Drawable[]{track, clip});
            layers.setId(0, android.R.id.background);
            layers.setId(1, android.R.id.progress);
            return layers;
        }

        // --- the notification the download survives behind ---

        private void requestCancel() {
            cancelled = true;
            ui.post(() -> {
                if (status != null) status.setText("Cancelling…");
            });
        }

        /** A Cancel the user can reach from the shade once the page is gone. */
        private void registerCancelAction() {
            try {
                cancelReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent intent) {
                        requestCancel();
                    }
                };
                IntentFilter filter = new IntentFilter(CANCEL_ACTION);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    app.registerReceiver(cancelReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    app.registerReceiver(cancelReceiver, filter);
                }
            } catch (Throwable ignored) {
                cancelReceiver = null;
            }
        }

        private void notifyProgress(String text, long done, long total, boolean force) {
            long now = android.os.SystemClock.uptimeMillis();
            if (!force && now - lastNotified < 500) return;
            lastNotified = now;
            try {
                NotificationManager manager = app.getSystemService(NotificationManager.class);
                if (manager == null) return;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                            "Feurstagram updates", NotificationManager.IMPORTANCE_LOW);
                    channel.setShowBadge(false);
                    manager.createNotificationChannel(channel);
                }
                Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? new Notification.Builder(app, CHANNEL_ID)
                        : new Notification.Builder(app);
                builder.setContentTitle("Updating FeurStagram")
                        .setContentText(text)
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setProgress(1000, total > 0 ? (int) (done * 1000L / total) : 0, total <= 0);

                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    flags |= PendingIntent.FLAG_IMMUTABLE;
                }
                PendingIntent cancel = PendingIntent.getBroadcast(app, 0,
                        new Intent(CANCEL_ACTION).setPackage(app.getPackageName()), flags);
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel);

                manager.notify(NOTIFICATION_ID, builder.build());
            } catch (Throwable ignored) {
                // Notifications may be denied; the page (if open) still shows progress.
            }
        }

        private void setStatus(String text) {
            ui.post(() -> {
                if (status != null) status.setText(text);
            });
            notifyProgress(text, 0, 0, true);
        }

        private void setProgress(long done, long total) {
            final String text = total > 0
                    ? human(done) + " / " + human(total) + "  ·  " + (done * 100L / total) + "%"
                    : human(done);
            ui.post(() -> {
                if (bar == null || detail == null) return;
                if (total > 0 && bar.isIndeterminate()) bar.setIndeterminate(false);
                if (total > 0) bar.setProgress((int) (done * 1000L / total));
                detail.setText(text);
            });
            notifyProgress(text, done, total, false);
        }

        private void fail(String message) {
            finish();
            ui.post(() -> {
                Toast.makeText(app, message + " Opening release page…", Toast.LENGTH_LONG).show();
                openReleasePage(app);
            });
        }

        // --- download ---

        private void download() {
            File dest = null;
            HttpURLConnection connection = null;
            try {
                File dir = app.getCacheDir();
                if (dir == null) throw new IllegalStateException("no download dir");
                dest = new File(dir, UPDATE_FILE_NAME);
                if (dest.exists() && !dest.delete()) {
                    throw new IllegalStateException("could not clear previous download");
                }

                connection = (HttpURLConnection) new URL(release.apkUrl).openConnection();
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.connect();

                long total = connection.getContentLengthLong();
                if (total <= 0) total = release.size;
                setStatus("Downloading update…");

                // Hashed as it arrives, so a corrupted or truncated file is caught
                // before Android is handed it. Older releases have no digest.
                MessageDigest sha = release.sha256 == null ? null : MessageDigest.getInstance("SHA-256");
                long done = 0;
                long lastPost = 0;
                byte[] buffer = new byte[131072];
                try (InputStream in = connection.getInputStream();
                     OutputStream out = new FileOutputStream(dest)) {
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        if (cancelled) break;
                        out.write(buffer, 0, n);
                        if (sha != null) sha.update(buffer, 0, n);
                        done += n;
                        // Repainting on every 128 KB chunk would post hundreds of
                        // runnables a second; a percent of the file is plenty.
                        if (total <= 0 || done - lastPost >= Math.max(total / 200, 262144L)) {
                            lastPost = done;
                            setProgress(done, total);
                        }
                    }
                }

                if (cancelled) {
                    dest.delete();
                    finish();
                    ui.post(() -> Toast.makeText(app, "Update cancelled", Toast.LENGTH_SHORT).show());
                    return;
                }
                setProgress(done, total > 0 ? total : done);

                if (total > 0 && done < total) {
                    throw new IllegalStateException("truncated download");
                }
                if (sha != null && !release.sha256.equals(toHex(sha.digest()))) {
                    dest.delete();
                    fail("Update download is corrupted (checksum mismatch).");
                    return;
                }

                setStatus("Preparing installation…");
                stage(dest);
            } catch (Throwable t) {
                if (dest != null) dest.delete();
                fail("Update download failed.");
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

        // --- staging + install ---

        /**
         * Write the APK into a {@link PackageInstaller} session and commit it, which
         * makes Android show its install confirmation.
         *
         * Any session left over from an earlier attempt is abandoned first. A stale
         * one re-raises its own confirmation dialog, which is what produced the
         * second "update failed" prompt right after a successful install.
         */
        private void stage(File apk) {
            PackageInstaller installer = app.getPackageManager().getPackageInstaller();
            abandonStaleSessions(installer);

            PackageInstaller.Session session = null;
            boolean committed = false;
            try {
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                int sessionId = installer.createSession(params);
                session = installer.openSession(sessionId);

                long length = apk.length();
                long written = 0;
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = session.openWrite("feurstagram", 0, length)) {
                    byte[] buffer = new byte[131072];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                        written += n;
                        setProgress(written, length);
                    }
                    session.fsync(out);
                }

                setStatus("Waiting for Android's install prompt…");

                String action = INSTALL_ACTION + "." + sessionId;
                registerInstallReceiver(app, action, apk);

                Intent intent = new Intent(action).setPackage(app.getPackageName());
                int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags |= android.app.PendingIntent.FLAG_MUTABLE;
                }
                android.app.PendingIntent pending =
                        android.app.PendingIntent.getBroadcast(app, sessionId, intent, flags);
                session.commit(pending.getIntentSender());
                committed = true;
                // The card's job ends here: Android's own confirmation takes over.
                finish();
            } catch (Throwable t) {
                if (session != null) {
                    try {
                        session.abandon();
                    } catch (Throwable ignored) {
                    }
                }
                apk.delete();
                fail("Install failed.");
            } finally {
                if (session != null && committed) {
                    session.close();
                }
            }
        }

        private void abandonStaleSessions(PackageInstaller installer) {
            try {
                for (PackageInstaller.SessionInfo info : installer.getMySessions()) {
                    try {
                        installer.abandonSession(info.getSessionId());
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        private static String human(long bytes) {
            if (bytes < 1024) return bytes + " B";
            double value = bytes / 1024.0;
            if (value < 1024) return String.format(Locale.US, "%.0f KB", value);
            value /= 1024.0;
            if (value < 1024) return String.format(Locale.US, "%.1f MB", value);
            return String.format(Locale.US, "%.2f GB", value / 1024.0);
        }
    }

    /**
     * Bridge Android's install status back to the user.
     *
     * {@code STATUS_PENDING_USER_ACTION} is handled <em>once</em>: the platform can
     * re-deliver it (and a leftover session delivers its own), and every delivery
     * used to start another confirmation activity — the second one surfacing after
     * the first install had already succeeded, reporting a failure for an APK that
     * was no longer there.
     */
    private static void registerInstallReceiver(Context app, String action, File apk) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            private boolean promptShown;

            @Override
            public void onReceive(Context c, Intent intent) {
                int statusCode = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
                if (statusCode == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    if (promptShown) return; // never raise a second confirmation
                    Intent confirm = safeConfirmIntent(
                            intent.getParcelableExtra(Intent.EXTRA_INTENT), c.getPackageName());
                    if (confirm == null) return;
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        c.startActivity(confirm);
                        promptShown = true;
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                // Terminal status: clean up.
                try {
                    c.unregisterReceiver(this);
                } catch (Throwable ignored) {
                }
                updateInFlight = false;
                apk.delete();
                if (statusCode == PackageInstaller.STATUS_SUCCESS) return;
                // A cancelled install is a choice, not an error worth a stack of
                // toasts; anything else gets the platform's own message.
                String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(c,
                        statusCode == PackageInstaller.STATUS_FAILURE_ABORTED
                                ? "Update cancelled"
                                : "Update failed" + (message == null ? "" : ": " + message),
                        Toast.LENGTH_LONG).show();
            }
        };
        IntentFilter filter = new IntentFilter(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(receiver, filter);
        }
    }

    /**
     * The install callback's confirmation Intent, made safe to start, or null.
     *
     * Below Android 13 a receiver registered at runtime is exported, so the
     * callback can come from any app, and starting its Intent as is runs with
     * this app's identity: it could open one of Instagram's private activities,
     * or hand out a grant to one of its files. The system always addresses the
     * confirmation to another package (the package installer, by setPackage), so
     * only such an Intent is started, with its URI grant flags, clip data and
     * selector removed; the installer's confirmation needs none of them.
     *
     * The action is not checked: it differs across Android versions
     * (CONFIRM_PERMISSIONS, then CONFIRM_INSTALL).
     */
    static Intent safeConfirmIntent(Intent confirm, String ownPackage) {
        if (confirm == null) return null;
        String component = confirm.getComponent() == null ? null : confirm.getComponent().getPackageName();
        if (!isForeignTarget(confirm.getPackage(), component, ownPackage)) return null;
        confirm.setSelector(null);
        confirm.setClipData(null);
        confirm.removeFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        return confirm;
    }

    /**
     * Whether an Intent addressed to {@code targetPackage} (and, if set, to a
     * component of {@code componentPackage}) can only reach another app. An
     * unaddressed Intent could resolve to one of this app's own activities.
     */
    static boolean isForeignTarget(String targetPackage, String componentPackage, String ownPackage) {
        if (targetPackage == null || targetPackage.isEmpty() || targetPackage.equals(ownPackage)) return false;
        return componentPackage == null || componentPackage.equals(targetPackage);
    }

    /** Whether this app may install packages (API 26+ gates on a per-app toggle). */
    private static boolean canRequestInstall(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        try {
            return context.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Open the per-app "install unknown apps" settings screen. */
    private static void requestInstallPermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            try {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void openReleasePage(Context context) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(LATEST_PAGE));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(context, "No browser available", Toast.LENGTH_LONG).show();
        }
    }
}

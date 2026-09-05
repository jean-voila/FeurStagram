package com.feurstagram.extension;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Development-only ADB bridge: drives Feurstagram's settings from a shell instead
 * of from the on-screen panel.
 *
 * Only compiled into the APK when the build enables the "Debug bridge" patch
 * ({@code ./build.sh … --debug}); nothing calls {@link #install} otherwise, so a
 * release build carries the class but never registers anything.
 *
 * It registers an exported {@link BroadcastReceiver} on the application context,
 * so every command is one {@code adb shell am broadcast}. Because {@code am}
 * passes a result receiver, the broadcast is ordered and {@link #setResultData}
 * comes straight back on stdout — no logcat scraping, no screenshots, no taps:
 *
 * <pre>
 * adb shell am broadcast -p com.instagram.android -a com.feurstagram.debug.DUMP
 * adb shell am broadcast -p com.instagram.android -a com.feurstagram.debug.SET \
 *     --es key block_ads --ez value true
 * adb shell am broadcast -p com.instagram.android -a com.feurstagram.debug.SET \
 *     --es key landing_page --es value search
 * adb shell am broadcast -p com.instagram.android -a com.feurstagram.debug.NAV
 * adb shell am broadcast -p com.instagram.android -a com.feurstagram.debug.TRACE --ez value true
 * adb shell am broadcast -p com.instagram.android -a com.feurstagram.debug.UPDATE
 * adb shell am broadcast -p com.instagram.android -a com.feurstagram.debug.WHATSNEW
 * adb shell am broadcast -p com.instagram.android -a com.feurstagram.debug.RESET
 * adb shell am broadcast -p com.instagram.android -a com.feurstagram.debug.RESTART
 * </pre>
 *
 * The receiver is dynamic, so it only exists while the app process is alive —
 * launch Instagram before sending commands.
 */
public final class DebugBridge {

    public static final String TAG = "FeurDebug";

    private static final String PREFIX = "com.feurstagram.debug.";

    /** Same store {@link Config} writes to. */
    private static final String PREFS = "feurstagram_prefs";

    private static final String LANDING_KEY = "landing_page";

    private static final String[] ACTIONS = {
            "SET", "GET", "DUMP", "NAV", "TRACE", "UPDATE", "WHATSNEW", "RESET", "RESTART", "SETTINGS", "PING",
    };

    private static boolean sInstalled;

    /**
     * Whether {@link Block} logs every request path it sees. Off even in a debug
     * build until TRACE turns it on, because the feed alone is hundreds of lines a
     * minute. Only this class ever sets it, and only a debug build installs this
     * class, so a release build reads a field that is always false.
     */
    private static volatile boolean sNetworkTrace;

    /** @see #sNetworkTrace */
    public static boolean isNetworkTraceEnabled() {
        return sNetworkTrace;
    }

    /**
     * Version {@link UpdateChecker} should believe is installed, or null to use the
     * real one. A debug build reports something older than any release so the whole
     * update flow — notes, download progress, install prompt — can be exercised
     * against the live GitHub release instead of waiting for a new one to exist.
     * Null in a release build, because nothing installs this class there.
     */
    private static final String PRETEND_VERSION = "1.0.0.0.0";

    /** @see #PRETEND_VERSION */
    public static String pretendInstalledVersion() {
        return sInstalled ? PRETEND_VERSION : null;
    }

    /** The tab bar, for the actions that need a live view (SETTINGS, NAV). */
    private static WeakReference<ViewGroup> sTabBar = new WeakReference<>(null);

    private DebugBridge() {}

    /**
     * Register the receiver. Called from the tab-bar hook by the Debug bridge
     * patch, so it runs once the user is past the login screens. Re-entrant: the
     * hook fires on every tab-bar build, only the first one registers.
     */
    public static void install(ViewGroup tabBar) {
        sTabBar = new WeakReference<>(tabBar);
        if (sInstalled) return;
        Context context = Config.getAppContext();
        if (context == null) return;
        try {
            IntentFilter filter = new IntentFilter();
            for (String action : ACTIONS) filter.addAction(PREFIX + action);
            if (Build.VERSION.SDK_INT >= 26) {
                // Context.RECEIVER_EXPORTED (0x2), spelled as a literal so the
                // extension still compiles against an older compileSdk. Required
                // from Android 14 on for a receiver `adb shell am` can reach.
                context.registerReceiver(new Receiver(), filter, 2);
            } else {
                context.registerReceiver(new Receiver(), filter);
            }
            sInstalled = true;
            Log.i(TAG, "debug bridge ready (" + PREFIX + "…)");
        } catch (Throwable t) {
            Log.w(TAG, "debug bridge registration failed", t);
        }
    }

    private static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null || !action.startsWith(PREFIX)) return;
            String command = action.substring(PREFIX.length());
            String reply;
            try {
                reply = handle(context, command, intent);
            } catch (Throwable t) {
                reply = "ERROR " + t;
            }
            Log.i(TAG, command + " -> " + reply);
            if (isOrderedBroadcast()) setResultData(reply);
        }
    }

    private static String handle(Context context, String command, Intent intent) {
        switch (command) {
            case "PING":
                return "ok";
            case "SET":
                return set(intent);
            case "GET":
                return get(intent);
            case "DUMP":
                return dump();
            case "NAV":
                return nav();
            case "UPDATE":
                return update();
            case "WHATSNEW":
                return whatsNew();
            case "TRACE":
                sNetworkTrace = intent.getBooleanExtra("value", !sNetworkTrace);
                return "network trace " + (sNetworkTrace ? "on (logcat -s FeurNet:I)" : "off");
            case "RESET":
                return reset();
            case "RESTART":
                return restart(context);
            case "SETTINGS":
                return settings();
            default:
                return "unknown command " + command;
        }
    }

    /**
     * {@code --es key <pref> --ez value <bool>} (or {@code --es value <string>}
     * for {@code landing_page}). Writes go through {@link Config} so the
     * permanent lock is honoured exactly as it is from the UI; add
     * {@code --ez force true} to write straight to the store and bypass it.
     */
    private static String set(Intent intent) {
        String key = intent.getStringExtra("key");
        if (key == null) return "ERROR missing --es key";
        boolean force = intent.getBooleanExtra("force", false);

        // --es value writes a string, --ez value a boolean. The landing page is the
        // one string that goes through Config, so its own rules still apply.
        String text = intent.getStringExtra("value");
        if (text != null) {
            if (LANDING_KEY.equals(key) && !force) {
                Config.setLandingPage(text);
                Config.setNeedsRestart();
                return LANDING_KEY + "=" + Config.getLandingPage();
            }
            SharedPreferences prefs = prefs();
            if (prefs == null) return "ERROR no context";
            prefs.edit().putString(key, text).apply();
            Config.setNeedsRestart();
            return key + "=" + text;
        }

        if (!intent.hasExtra("value")) return "ERROR missing --ez value (or --es value)";
        boolean value = intent.getBooleanExtra("value", false);
        if (force) {
            SharedPreferences prefs = prefs();
            if (prefs == null) return "ERROR no context";
            prefs.edit().putBoolean(key, value).apply();
        } else {
            Config.setBlocked(key, value);
        }
        Config.setNeedsRestart();
        return key + "=" + Config.getBlocked(key, value);
    }

    private static String get(Intent intent) {
        String key = intent.getStringExtra("key");
        if (key == null) return "ERROR missing --es key";
        SharedPreferences prefs = prefs();
        if (prefs == null) return "ERROR no context";
        if (!prefs.contains(key)) return key + "=<unset>";
        Object value = prefs.getAll().get(key);
        return key + "=" + value;
    }

    /** Every stored preference, one {@code key=value} per line, sorted. */
    private static String dump() {
        SharedPreferences prefs = prefs();
        if (prefs == null) return "ERROR no context";
        Map<String, ?> all = prefs.getAll();
        List<String> keys = new ArrayList<>(all.keySet());
        Collections.sort(keys);
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(key).append('=').append(all.get(key)).append('\n');
        }
        // Effective values matter as much as stored ones: an unset key still has
        // a default, and getLandingPage() can override what is on disk.
        out.append("--- effective ---\n");
        out.append("landing_page=").append(Config.getLandingPage()).append('\n');
        out.append("hardcore_mode=").append(Config.isHardcoreMode()).append('\n');
        out.append("pretend_installed_version=").append(pretendInstalledVersion()).append('\n');
        return out.toString();
    }

    /**
     * The bottom tab bar and the swipe pager as the code sees them: every tab
     * child with its resolved resource name, index and visibility, plus the
     * pager's current page and page count. This is the mapping
     * {@link HiddenTabSwipeSkipper} works from.
     */
    private static String nav() {
        ViewGroup tabBar = sTabBar.get();
        if (tabBar == null) return "ERROR no tab bar (open the app first)";
        Context context = tabBar.getContext();
        View root = tabBar.getRootView();
        View bar = root.findViewById(Hiders.resolveId(context, "tab_bar"));
        StringBuilder out = new StringBuilder();

        if (bar instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) bar;
            out.append("tab_bar children=").append(group.getChildCount()).append('\n');
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                out.append("  [").append(i).append("] ")
                        .append(nameOf(context, child.getId()))
                        .append(" visibility=").append(visibilityOf(child))
                        .append(" selected=").append(child.isSelected())
                        .append('\n');
            }
        } else {
            out.append("tab_bar not found\n");
        }

        View pager = root.findViewById(Hiders.resolveId(context, "swipeable_tab_view_pager"));
        if (pager == null) {
            out.append("pager not found\n");
        } else {
            out.append("pager class=").append(pager.getClass().getName()).append('\n');
            out.append("pager currentItem=").append(invokeInt(pager, "getCurrentItem"))
                    .append(" scrollState=").append(invokeInt(pager, "getScrollState"))
                    .append('\n');
        }
        return out.toString();
    }

    private static String nameOf(Context context, int id) {
        if (id == View.NO_ID) return "<no id>";
        try {
            return context.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return "0x" + Integer.toHexString(id);
        }
    }

    private static String visibilityOf(View view) {
        switch (view.getVisibility()) {
            case View.VISIBLE: return "VISIBLE";
            case View.INVISIBLE: return "INVISIBLE";
            default: return "GONE";
        }
    }

    private static String invokeInt(Object target, String method) {
        try {
            return String.valueOf(target.getClass().getMethod(method).invoke(target));
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * Wipe every stored preference, permanent lock included, so a lock-related
     * test can be run again without reinstalling the app.
     */
    private static String reset() {
        SharedPreferences prefs = prefs();
        if (prefs == null) return "ERROR no context";
        int count = prefs.getAll().size();
        prefs.edit().clear().apply();
        Config.setNeedsRestart();
        return "cleared " + count + " preferences";
    }

    private static String restart(Context context) {
        ViewGroup tabBar = sTabBar.get();
        final Context target = tabBar != null ? Settings.getActivityContext(tabBar) : context;
        // The cleaner force-stops the process, so answer before it runs.
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> CacheCleaner.clearAndRestart(target), 300);
        return "restarting";
    }

    /**
     * Run the update check the way the Settings button does. It has to go through
     * the tab bar's Activity: the prompt is a Dialog, and a Dialog on the
     * application context has no window token.
     */
    private static String update() {
        ViewGroup tabBar = sTabBar.get();
        if (tabBar == null) return "ERROR no tab bar (open the app first)";
        tabBar.post(() -> {
            Context activity = Settings.getActivityContext(tabBar);
            if (activity != null) UpdateChecker.checkNow(activity);
        });
        return "update check started (pretending " + PRETEND_VERSION + " is installed)";
    }

    /**
     * Show the "What's new" card for the latest published release. The real one
     * only fires when the installed version changes, which can't be staged on a
     * device, so this drives the same card straight from the newest release.
     */
    private static String whatsNew() {
        ViewGroup tabBar = sTabBar.get();
        if (tabBar == null) return "ERROR no tab bar (open the app first)";
        tabBar.post(() -> {
            Context activity = Settings.getActivityContext(tabBar);
            if (activity != null) UpdateChecker.showLatestNotes(activity);
        });
        return "showing latest release notes";
    }

    private static String settings() {
        ViewGroup tabBar = sTabBar.get();
        if (tabBar == null) return "ERROR no tab bar (open the app first)";
        tabBar.post(() -> {
            Context activity = Settings.getActivityContext(tabBar);
            if (activity != null) Settings.show(activity);
        });
        return "opening settings";
    }

    private static SharedPreferences prefs() {
        Context context = Config.getAppContext();
        return context == null ? null : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

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
 * adb shell am broadcast -p com.instagram.android -a com.feurstagram.debug.TREE \
 *     --es id direct_inbox_action_bar --ei depth 3
 * adb shell am broadcast -p com.instagram.android -a com.feurstagram.debug.TRACE --ez value true
 * adb shell am broadcast -p com.instagram.android -a com.feurstagram.debug.UPDATE
 * adb shell am broadcast -p com.instagram.android -a com.feurstagram.debug.WHATSNEW
 * adb shell am broadcast -p com.instagram.android -a com.feurstagram.debug.INTRO
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
            "SET", "GET", "REMOVE", "DUMP", "NAV", "TRACE", "UPDATE", "WHATSNEW", "RESET", "RESTART", "SETTINGS", "PING",
            "TREE", "INTRO", "CLICK", "TOUCH", "FIELDS",
    };

    private static boolean sInstalled;

    /** Held by the adb shell, which sends `am broadcast`, and by no ordinary app. */
    private static final String SENDER_PERMISSION = "android.permission.DUMP";

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
            // Only senders holding DUMP get through: the adb shell does, an app
            // cannot (it is a signature|privileged|development permission). So a
            // debug build installed by mistake is not drivable by other apps.
            if (Build.VERSION.SDK_INT >= 26) {
                // Context.RECEIVER_EXPORTED (0x2), spelled as a literal so the
                // extension still compiles against an older compileSdk. Required
                // from Android 14 on for a receiver `adb shell am` can reach.
                context.registerReceiver(new Receiver(), filter, SENDER_PERMISSION, null, 2);
            } else {
                context.registerReceiver(new Receiver(), filter, SENDER_PERMISSION, null);
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
            case "REMOVE":
                String key = intent.getStringExtra("key");
                if (key == null) return "ERROR missing --es key";
                SharedPreferences store = prefs();
                if (store == null) return "ERROR no context";
                store.edit().remove(key).apply();
                Config.setNeedsRestart();
                return key + "=<unset>";
            case "DUMP":
                return dump();
            case "NAV":
                return nav();
            case "TREE":
                return tree(intent);
            case "CLICK":
                return click(intent);
            case "TOUCH":
                return touch(intent);
            case "FIELDS":
                return fields(intent);
            case "UPDATE":
                return update();
            case "WHATSNEW":
                return whatsNew();
            case "INTRO":
                return intro();
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
        View bar = Hiders.tabBar(tabBar);
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

    /**
     * {@code --es id <name> [--ei depth <n>] [--ei up <n>]}: every view of the
     * window with that resource name, each with its ancestors ({@code up} levels,
     * 6 by default) and its subtree ({@code depth} levels, 2 by default) — class,
     * id, on-screen bounds and visibility. Without an id, the window's subtree.
     * Reading an Instagram screen this way beats uiautomator, which skips views
     * that are off screen (a pager page the user is not on).
     */
    private static String tree(Intent intent) {
        ViewGroup tabBar = sTabBar.get();
        if (tabBar == null) return "ERROR no tab bar (open the app first)";
        Context context = tabBar.getContext();
        View root = tabBar.getRootView();
        int depth = intent.getIntExtra("depth", 2);
        int up = intent.getIntExtra("up", 6);
        String name = intent.getStringExtra("id");
        StringBuilder out = new StringBuilder();
        if (name == null) {
            describe(context, root, 0, depth, out);
            return out.toString();
        }
        int id = Hiders.resolveId(context, name);
        if (id == 0) return "ERROR no resource id " + name;
        List<View> found = new ArrayList<>();
        collectById(root, id, found);
        out.append(name).append(" x").append(found.size()).append('\n');
        for (View view : found) {
            List<View> chain = new ArrayList<>();
            for (Object p = view.getParent(); p instanceof View && chain.size() < up; p = ((View) p).getParent()) {
                chain.add(0, (View) p);
            }
            for (int i = 0; i < chain.size(); i++) {
                out.append(indent(i)).append("^ ").append(line(context, chain.get(i))).append('\n');
            }
            describe(context, view, chain.size(), chain.size() + depth, out);
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * performClick() on the view {@link #target} picks, as a tap would, but on
     * views that are off screen too.
     */
    private static String click(Intent intent) {
        Object target = target(intent);
        if (target instanceof String) return (String) target;
        View view = (View) target;
        return "performClick=" + view.performClick() + " " + line(view.getContext(), view);
    }

    /**
     * A tap (down, then up) at the centre of the view {@link #target} picks,
     * dispatched to that view directly: for views driven by touch listeners
     * rather than click listeners, on screen or not.
     */
    private static String touch(Intent intent) {
        Object target = target(intent);
        if (target instanceof String) return (String) target;
        final View view = (View) target;
        final float x = view.getWidth() / 2f;
        final float y = view.getHeight() / 2f;
        final long down = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent press = android.view.MotionEvent.obtain(down, down,
                android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        boolean handled = view.dispatchTouchEvent(press);
        press.recycle();
        view.postDelayed(() -> {
            long now = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent release = android.view.MotionEvent.obtain(down, now,
                    android.view.MotionEvent.ACTION_UP, x, y, 0);
            view.dispatchTouchEvent(release);
            release.recycle();
        }, 60);
        return "touch down handled=" + handled + " " + line(view.getContext(), view);
    }

    /**
     * Same target as {@link #click}: every field of the view, from its class up to
     * View, with the value's class (and size and element classes for collections).
     * Instagram's androidx is obfuscated, so this is how its listeners and adapters
     * are told apart.
     */
    private static String fields(Intent intent) {
        Object target = target(intent);
        if (target instanceof String) return (String) target;
        View view = (View) target;
        StringBuilder out = new StringBuilder(line(view.getContext(), view)).append('\n');
        // --es field "A0E/A03": follow those fields from the view first (a list
        // element by index: "A0P/2"), and dump the object found there instead.
        String fieldPath = intent.getStringExtra("field");
        if (fieldPath != null) {
            Object object = view;
            for (String step : fieldPath.split("/")) {
                object = step(object, step);
                if (object == null) return out.append("null at ").append(step).toString();
            }
            out.append(fieldPath).append(" = ").append(object.getClass().getName()).append('\n');
            if (object instanceof java.util.List) {
                java.util.List<?> items = (java.util.List<?>) object;
                for (int i = 0; i < items.size(); i++) {
                    Object item = items.get(i);
                    out.append("  [").append(i).append("] ")
                            .append(item == null ? "null" : item.getClass().getName()).append('\n');
                }
                return out.toString();
            }
            if (object instanceof java.util.Map) {
                for (java.util.Map.Entry<?, ?> entry : ((java.util.Map<?, ?>) object).entrySet()) {
                    out.append("  ").append(describe(entry.getKey())).append(" -> ")
                            .append(describe(entry.getValue())).append('\n');
                }
                return out.toString();
            }
            if (object instanceof android.util.SparseArray) {
                android.util.SparseArray<?> array = (android.util.SparseArray<?>) object;
                for (int i = 0; i < array.size(); i++) {
                    out.append("  ").append(array.keyAt(i)).append(" -> ")
                            .append(describe(array.valueAt(i))).append('\n');
                }
                return out.toString();
            }
            for (Class<?> c = object.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                appendFields(c, object, out);
            }
            return out.toString();
        }
        for (Class<?> c = view.getClass(); c != null && c != View.class; c = c.getSuperclass()) {
            appendFields(c, view, out);
        }
        // The listeners a view carries live in View's hidden ListenerInfo.
        try {
            java.lang.reflect.Field info = View.class.getDeclaredField("mListenerInfo");
            info.setAccessible(true);
            Object listeners = info.get(view);
            if (listeners != null) appendFields(listeners.getClass(), listeners, out);
        } catch (Throwable t) {
            out.append("  (no ListenerInfo: ").append(t.getClass().getSimpleName()).append(")\n");
        }
        return out.toString();
    }

    /** A value as its class, or the class it names, for the FIELDS listings. */
    private static String describe(Object value) {
        if (value == null) return "null";
        if (value instanceof Class) return "class " + ((Class<?>) value).getName();
        if (value instanceof Number || value instanceof String) return value.toString();
        return value.getClass().getName();
    }

    /** One step of a field path: a field of {@code owner} (any class up), or a list index. */
    private static Object step(Object owner, String name) {
        if (owner instanceof java.util.List && name.matches("\\d+")) {
            java.util.List<?> list = (java.util.List<?>) owner;
            int index = Integer.parseInt(name);
            return index < list.size() ? list.get(index) : null;
        }
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException ignored) {
                // look in the superclass
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static void appendFields(Class<?> c, Object owner, StringBuilder out) {
        {
            for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (field.getType().isPrimitive()) continue;
                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(owner);
                } catch (Throwable t) {
                    continue;
                }
                if (value == null) continue;
                out.append("  ").append(c.getSimpleName()).append('.').append(field.getName())
                        .append(" = ").append(value.getClass().getName());
                if (value instanceof java.util.Collection) {
                    java.util.Collection<?> items = (java.util.Collection<?>) value;
                    out.append(" size=").append(items.size());
                    for (Object item : items) {
                        out.append(' ').append(item == null ? "null" : item.getClass().getName());
                    }
                }
                out.append('\n');
            }
        }
    }

    /**
     * {@code --es id <name> [--ei index <n>] [--es path <i/j/...>]}: the n-th view
     * with that resource name, then the child the path leads to. A String is an
     * error message.
     */
    private static Object target(Intent intent) {
        ViewGroup tabBar = sTabBar.get();
        if (tabBar == null) return "ERROR no tab bar (open the app first)";
        Context context = tabBar.getContext();
        String name = intent.getStringExtra("id");
        if (name == null) return "ERROR missing --es id";
        int id = Hiders.resolveId(context, name);
        if (id == 0) return "ERROR no resource id " + name;
        List<View> found = new ArrayList<>();
        collectById(tabBar.getRootView(), id, found);
        int index = intent.getIntExtra("index", 0);
        if (index >= found.size()) return "ERROR only " + found.size() + " " + name;
        View view = found.get(index);
        String path = intent.getStringExtra("path");
        if (path != null) {
            for (String step : path.split("/")) {
                if (!(view instanceof ViewGroup)) return "ERROR path leaves the tree at " + step;
                view = ((ViewGroup) view).getChildAt(Integer.parseInt(step));
                if (view == null) return "ERROR no child " + step;
            }
        }
        return view;
    }

    private static void collectById(View view, int id, List<View> out) {
        if (view.getId() == id) out.add(view);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collectById(group.getChildAt(i), id, out);
    }

    private static void describe(Context context, View view, int level, int maxLevel, StringBuilder out) {
        out.append(indent(level)).append(line(context, view)).append('\n');
        if (level >= maxLevel || !(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            describe(context, group.getChildAt(i), level + 1, maxLevel, out);
        }
    }

    private static String line(Context context, View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        String cls = view.getClass().getName();
        if (!cls.startsWith("androidx.")) cls = view.getClass().getSimpleName();
        StringBuilder line = new StringBuilder(cls)
                .append(' ').append(view.getId() == View.NO_ID ? "-" : nameOf(context, view.getId()))
                .append(" [").append(at[0]).append(',').append(at[1]).append(' ')
                .append(view.getWidth()).append('x').append(view.getHeight()).append(']');
        if (view.getVisibility() != View.VISIBLE) line.append(' ').append(visibilityOf(view));
        if (!view.isAttachedToWindow()) line.append(" detached");
        if (view.hasOnClickListeners()) line.append(" onClick");
        if (view.getPaddingTop() != 0 || view.getPaddingBottom() != 0) {
            line.append(" padding=").append(view.getPaddingTop()).append('/').append(view.getPaddingBottom());
        }
        if (view.getTranslationY() != 0) line.append(" ty=").append(view.getTranslationY());
        if (view.getAlpha() != 1f) line.append(" alpha=").append(view.getAlpha());
        if (view instanceof ViewGroup) line.append(" children=").append(((ViewGroup) view).getChildCount());
        return line.toString();
    }

    private static String indent(int level) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < level; i++) out.append("  ");
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
    /** Show the card presenting Quoicouview mode, whether or not it is due. */
    private static String intro() {
        ViewGroup tabBar = sTabBar.get();
        if (tabBar == null) return "ERROR no tab bar (open the app first)";
        tabBar.post(() -> {
            Context activity = Settings.getActivityContext(tabBar);
            if (activity != null) QuoicouviewIntro.showNow(activity);
        });
        return "showing the Quoicouview card";
    }

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

package com.feurstagram.extension;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

/**
 * Keeps a long-press listener that opens the Feurstagram settings on the Home
 * tab of the bottom bar (or, with Home hidden, the first icon still on it).
 *
 * The visible way in is {@link ProfileSettingsButton}, an icon in the header of
 * the user's own profile; this gesture stays for those used to it. Long-pressing
 * Home only needs the tab bar, which always exists.
 *
 * The watcher stays on the window's layout passes rather than arming the tab
 * once: Instagram rebuilds the tab bar after its first layout (456), can rebind a
 * tab's listeners, and the tab that should carry the gesture changes when Home
 * is hidden. Each pass checks the right tab still carries this listener and puts
 * it back otherwise; the check is a field read, not a layout.
 *
 * When the user hides the Home icon (issue #127) the gesture moves to the first
 * icon still on the bar ({@link Hiders#settingsTab}).
 */
public final class HomeTabWatcher implements ViewTreeObserver.OnGlobalLayoutListener {

    /** One listener for every tab: recognisable when read back from the view. */
    private static final View.OnLongClickListener OPEN = v -> {
        Context activity = Settings.getActivityContext(v);
        if (activity == null) return false;
        Settings.show(activity);
        return true;
    };

    private final WeakReference<ViewGroup> tabBar;
    private WeakReference<View> armed = new WeakReference<>(null);
    private boolean onboarding;

    public HomeTabWatcher(ViewGroup tabBar) {
        this.tabBar = new WeakReference<>(tabBar);
    }

    @Override
    public void onGlobalLayout() {
        try {
            ViewGroup bar = tabBar.get();
            if (bar == null || !bar.isAttachedToWindow()) return;
            View tab = Hiders.settingsTab(bar);
            if (tab == null) return; // not inflated yet; keep waiting

            // Note: do NOT call tab.setTag(...) to mark this view. Instagram stores
            // its own object in the tab's view tag and casts it back on resume, so an
            // overwritten tag crashes (ClassCastException).
            if (armed.get() != tab || !carriesOurListener(tab)) {
                tab.setOnLongClickListener(OPEN);
                armed = new WeakReference<>(tab);
            }

            // First launch with a signed-in account: point at the gesture we just
            // installed. The tab bar only exists once the user is past the login
            // screens, so reaching here *is* "after the Instagram connection". The
            // guide waits for a screen clear of Instagram's own post-login popups
            // before it appears, so it is handed the bar and finds the tab itself.
            if (!onboarding) {
                onboarding = true;
                Onboarding.maybeShow(bar);
            }
        } catch (Throwable ignored) {
            // A missing gesture is fine; taking Instagram down is not.
        }
    }

    private static Field listenerInfo;
    private static Field longClick;
    private static boolean reflectionFailed;

    /** Whether the view's long-click listener is still ours (true when unreadable). */
    private static boolean carriesOurListener(View view) {
        if (reflectionFailed) return true;
        try {
            if (listenerInfo == null) {
                listenerInfo = View.class.getDeclaredField("mListenerInfo");
                listenerInfo.setAccessible(true);
            }
            Object info = listenerInfo.get(view);
            if (info == null) return false;
            if (longClick == null) {
                longClick = info.getClass().getDeclaredField("mOnLongClickListener");
                longClick.setAccessible(true);
            }
            return longClick.get(info) == OPEN;
        } catch (Throwable t) {
            reflectionFailed = true;
            return true;
        }
    }
}

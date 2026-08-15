package com.feurstagram.extension;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

/**
 * Watches the bottom tab bar until the "feed_tab" child (the Home button at the
 * bottom-left) is inflated, then installs a long-press listener on it that opens
 * the Feurstagram settings, and detaches.
 *
 * This is the sole settings entry point. An injected action-bar button was tried
 * but failed to appear on many devices, since it depends on the feed's top action
 * bar being present and laid out a particular way. Long-pressing Home only needs
 * the tab bar, which always exists, so settings stay reachable everywhere. The id
 * is resolved by name (with a clone fallback) so it survives Instagram version bumps.
 */
public final class HomeTabWatcher implements ViewTreeObserver.OnGlobalLayoutListener {

    private ViewGroup tabBar;

    public HomeTabWatcher(ViewGroup tabBar) {
        this.tabBar = tabBar;
    }

    @Override
    public void onGlobalLayout() {
        ViewGroup bar = tabBar;
        if (bar == null) return;
        Context context = bar.getContext();
        if (context == null) return;

        int id = Hiders.resolveId(context, "feed_tab");
        if (id == 0) return; // resource not found yet; keep waiting

        View homeTab = bar.getRootView().findViewById(id);
        if (homeTab == null) return; // not inflated yet; keep waiting

        // Note: do NOT call homeTab.setTag(...) to mark this view. Instagram stores
        // its own object in the tab's view tag and casts it back on resume, so an
        // overwritten tag crashes (ClassCastException). Detaching after install is
        // enough to run once; re-setting the listener is harmless if it ever repeats.
        homeTab.setOnLongClickListener(v -> {
            Context activity = Settings.getActivityContext(v);
            if (activity == null) return false;
            Settings.show(activity);
            return true;
        });

        // First launch with a signed-in account: point at the gesture we just
        // installed. The tab bar only exists once the user is past the login
        // screens, so reaching here *is* "after the Instagram connection". The
        // guide waits for a screen clear of Instagram's own post-login popups
        // before it appears, so it is handed the bar and finds the tab itself.
        Onboarding.maybeShow(bar);

        // Done: detach so we don't keep re-running on every layout pass.
        bar.getViewTreeObserver().removeOnGlobalLayoutListener(this);
        tabBar = null;
    }
}

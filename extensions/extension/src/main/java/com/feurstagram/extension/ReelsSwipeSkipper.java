package com.feurstagram.extension;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Makes the blocked Reels page un-reachable by horizontal swipe.
 *
 * The bottom tab bar hides the Reels tab when its nav icon is turned off (see
 * {@link Hiders}), but the main {@code swipeable_tab_view_pager} still keeps the
 * Reels page in its order: Home, Reels, Direct, Search, Profile. Swiping from
 * Home toward Messages therefore lands on the (now empty) Reels page and needs a
 * second swipe to reach the DM inbox.
 *
 * This installer watches the pager's scrolling; while reels are blocked, the
 * moment a swipe settles onto the Reels page it drives the pager one more page in
 * the direction of travel — so a single swipe from Home reaches Messages (and a
 * swipe back from Messages reaches Home), matching the tab bar where Reels is
 * already gone.
 *
 * The pager is an {@code androidx.viewpager2.widget.ViewPager2} whose androidx
 * code is obfuscated inside Instagram, so we can't reference it at compile time
 * or subclass its abstract page-change callback. Instead we listen on the
 * framework {@link ViewTreeObserver.OnScrollChangedListener} (not obfuscated) and
 * read/drive the pager through its still-clear {@code getCurrentItem() /
 * getScrollState() / setCurrentItem(int)} methods by reflection.
 */
public final class ReelsSwipeSkipper {

    private ReelsSwipeSkipper() {}

    /** ViewPager2 scroll states. */
    private static final int STATE_DRAGGING = 1;

    /** Pagers we've already hooked, so a re-install doesn't stack listeners. */
    private static final Set<View> INSTALLED =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    /** Install on the tab-bar root; waits for the pager to appear, then hooks it once. */
    static void install(ViewGroup root) {
        if (root == null) return;
        root.getViewTreeObserver().addOnGlobalLayoutListener(new InstallWatcher(root));
    }

    /** Global-layout listener that locates the pager, hooks it, and detaches. */
    private static final class InstallWatcher
            implements ViewTreeObserver.OnGlobalLayoutListener {
        private ViewGroup root;

        InstallWatcher(ViewGroup root) {
            this.root = root;
        }

        @Override
        public void onGlobalLayout() {
            ViewGroup r = root;
            if (r == null) return;
            Context context = r.getContext();
            if (context == null) return;

            int pagerId = Hiders.resolveId(context, "swipeable_tab_view_pager");
            if (pagerId == 0) { detach(); return; }

            View pager = r.getRootView().findViewById(pagerId);
            if (pager == null) return; // not laid out yet; keep waiting

            View tabBar = r.getRootView().findViewById(Hiders.resolveId(context, "tab_bar"));
            int clipsId = Hiders.resolveId(context, "clips_tab");
            if (attach(pager, tabBar, clipsId)) {
                detach();
            }
        }

        private void detach() {
            ViewGroup r = root;
            if (r != null) {
                r.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                root = null;
            }
        }
    }

    /** Reflectively grab the pager's clear-named methods and hook the scroll listener. */
    private static boolean attach(View pager, View tabBar, int clipsId) {
        if (INSTALLED.contains(pager)) return true;
        try {
            Method getCurrentItem = pager.getClass().getMethod("getCurrentItem");
            Method getScrollState = pager.getClass().getMethod("getScrollState");
            Method setCurrentItem = pager.getClass().getMethod("setCurrentItem", int.class);

            // Optional: only present on ViewPager2. Used by the swipe-disable toggle.
            Method setUserInputEnabled = null;
            try {
                setUserInputEnabled = pager.getClass().getMethod("setUserInputEnabled", boolean.class);
            } catch (Throwable ignored) {
                // Not available on this Instagram build; the disable-swipe toggle will be a no-op.
            }

            // Apply swipe-disable immediately if the toggle is already on.
            if (setUserInputEnabled != null && Config.isSwipeDisabled()) {
                setUserInputEnabled.invoke(pager, false);
            }

            Skipper skipper = new Skipper(
                    pager, tabBar, clipsId, getCurrentItem, getScrollState, setCurrentItem,
                    setUserInputEnabled);
            pager.getViewTreeObserver().addOnScrollChangedListener(skipper);

            // Re-apply swipe-disable on every layout pass, since Instagram calls
            // setUserInputEnabled(true) again after navigation events.
            if (setUserInputEnabled != null) {
                final Method sue = setUserInputEnabled;
                pager.getViewTreeObserver().addOnGlobalLayoutListener(
                        new ViewTreeObserver.OnGlobalLayoutListener() {
                            @Override
                            public void onGlobalLayout() {
                                if (!Config.isSwipeDisabled()) return;
                                try { sue.invoke(pager, false); } catch (Throwable ignored) {}
                            }
                        });
            }

            INSTALLED.add(pager);
            return true;
        } catch (Throwable ignored) {
            // Pager API not as expected on this build: leave swipe behaviour alone,
            // and stop retrying.
            return true;
        }
    }

    /**
     * Fires on every scroll in the pager's window. When a swipe settles on the
     * Reels page (and reels are blocked) it advances one page further in the
     * direction of travel.
     */
    private static final class Skipper implements ViewTreeObserver.OnScrollChangedListener {
        private final View pager;
        private final View tabBar;
        private final int clipsId;
        private final Method getCurrentItem;
        private final Method getScrollState;
        private final Method setCurrentItem;
        private final Method setUserInputEnabled; // may be null

        /** Last page we settled on that wasn't Reels, to infer swipe direction. */
        private int prev;
        /** Set while a skip is in flight, to avoid issuing it repeatedly. */
        private boolean bouncing;

        Skipper(View pager, View tabBar, int clipsId,
                Method getCurrentItem, Method getScrollState, Method setCurrentItem,
                Method setUserInputEnabled) {
            this.pager = pager;
            this.tabBar = tabBar;
            this.clipsId = clipsId;
            this.getCurrentItem = getCurrentItem;
            this.getScrollState = getScrollState;
            this.setCurrentItem = setCurrentItem;
            this.setUserInputEnabled = setUserInputEnabled;
            this.prev = currentItem();
        }

        @Override
        public void onScrollChanged() {
            // If swipe is fully disabled, keep re-asserting it and skip the
            // Reels-skip logic (no swipe means no page to skip over).
            if (setUserInputEnabled != null && Config.isSwipeDisabled()) {
                try { setUserInputEnabled.invoke(pager, false); } catch (Throwable ignored) {}
                return;
            }

            int reels = reelsIndex();
            if (reels < 0) return;

            int cur = currentItem();
            if (cur != reels) {
                // On a real page: remember it as the origin and clear any guard.
                prev = cur;
                bouncing = false;
                return;
            }

            // We're on the (hidden, empty) Reels page.
            if (bouncing) return;                       // skip already issued
            if (Config.isReelsTabShown()) return;       // Reels tab visible: leave the page reachable
            if (scrollState() == STATE_DRAGGING) return; // user still has finger down

            int dir = prev <= reels ? 1 : -1;           // continue past reels
            int target = reels + dir;
            int count = pageCount();
            if (target < 0 || (count > 0 && target >= count)) return;

            bouncing = true;
            final int dest = target;
            // Post so we drive the pager once the current settle is underway,
            // avoiding a re-entrant setCurrentItem during the scroll callback.
            pager.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        setCurrentItem.invoke(pager, dest);
                    } catch (Throwable ignored) {
                        bouncing = false;
                    }
                }
            });
        }

        /** Reels page index = position of clips_tab among the tab bar's children. */
        private int reelsIndex() {
            if (!(tabBar instanceof ViewGroup) || clipsId == 0) return -1;
            ViewGroup bar = (ViewGroup) tabBar;
            View clips = bar.findViewById(clipsId);
            if (clips == null) return -1;
            return bar.indexOfChild(clips);
        }

        /** Page count = number of tab cells (one per pager page). */
        private int pageCount() {
            return tabBar instanceof ViewGroup ? ((ViewGroup) tabBar).getChildCount() : -1;
        }

        private int currentItem() {
            try {
                return (Integer) getCurrentItem.invoke(pager);
            } catch (Throwable ignored) {
                return 0;
            }
        }

        private int scrollState() {
            try {
                return (Integer) getScrollState.invoke(pager);
            } catch (Throwable ignored) {
                return 0;
            }
        }
    }
}
package com.feurstagram.extension;

import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.HorizontalScrollView;

/**
 * UI-level hiders and the landing-page redirect, all installed on the main
 * tab bar. Each is a persistent global-layout listener that resolves its
 * target by resource name (with a clone fallback) so it survives Instagram
 * version bumps that reshuffle hex resource ids.
 */
public final class Hiders {

    private Hiders() {}

    /** Install every UI hider and the landing redirect on the tab-bar root. */
    public static void installAll(ViewGroup root) {
        if (root == null) return;
        ViewTreeObserver observer = root.getViewTreeObserver();
        // Notes tray, Instants entry-points.
        observer.addOnGlobalLayoutListener(new VisibilityHider(root, "block_notes", "cf_hub_recycler_view"));
        observer.addOnGlobalLayoutListener(new VisibilityHider(root, "block_instants",
                "creation_entrypoint", "direct_quick_snap_consumption_preview"));
        // Notifications ("heart") button in the feed header. Opt-in (default visible).
        // Scoped to the feed action bar's right button container: the target view id
        // ("notification") is shared with the unread-DM badge on the Direct tab, so
        // an unscoped search could hide that badge instead.
        observer.addOnGlobalLayoutListener(new VisibilityHider(root, "block_notifications",
                false, false, "action_bar_buttons_container_right", "notification"));
        // Bottom-navigation icons, each shown/hidden independently. These use the
        // inverted "nav_show_<tab>" preference (true = shown). Home is intentionally
        // absent: long-pressing it opens Settings, so it must stay visible. Reels
        // defaults to hidden, matching the old "blocking reels hid its tab" behaviour.
        observer.addOnGlobalLayoutListener(new VisibilityHider(root, "nav_show_search", true, true, null, "search_tab"));
        observer.addOnGlobalLayoutListener(new VisibilityHider(root, "nav_show_reels", false, true, null, "clips_tab"));
        observer.addOnGlobalLayoutListener(new VisibilityHider(root, "nav_show_create", true, true, null, "creation_tab"));
        observer.addOnGlobalLayoutListener(new VisibilityHider(root, "nav_show_direct", true, true, null, "direct_tab"));
        observer.addOnGlobalLayoutListener(new VisibilityHider(root, "nav_show_profile", true, true, null, "profile_tab"));
        // "Friends" tab in the Reels viewer header.
        observer.addOnGlobalLayoutListener(new FriendsLaneHider(root));
        // Cold-start landing-page redirect.
        observer.addOnGlobalLayoutListener(new LandingWatcher(root));
        // Skip the blocked Reels page when swiping between Home and Messages.
        ReelsSwipeSkipper.install(root);
    }

    static int resolveId(Context context, String name) {
        Resources resources = context.getResources();
        int id = resources.getIdentifier(name, "id", context.getPackageName());
        if (id == 0) {
            id = resources.getIdentifier(name, "id", "com.instagram.android");
        }
        return id;
    }

    /**
     * Applies GONE/VISIBLE to one or more named views on every layout pass,
     * driven by a block_* preference. Toggling the preference off restores the
     * views on the next pass.
     */
    static final class VisibilityHider implements ViewTreeObserver.OnGlobalLayoutListener {
        private final ViewGroup root;
        private final String key;
        private final boolean defaultValue;
        private final boolean invert;
        private final String scope;
        private final String[] names;

        VisibilityHider(ViewGroup root, String key, String... names) {
            this(root, key, true, false, null, names);
        }

        /**
         * @param defaultValue value used when the preference is unset
         * @param invert       when true the preference means "shown" rather than
         *                     "hidden" (used for the nav_show_* toggles)
         * @param scope        optional container resource name to search within,
         *                     so a view id reused elsewhere is only touched inside it
         */
        VisibilityHider(ViewGroup root, String key, boolean defaultValue, boolean invert,
                        String scope, String... names) {
            this.root = root;
            this.key = key;
            this.defaultValue = defaultValue;
            this.invert = invert;
            this.scope = scope;
            this.names = names;
        }

        @Override
        public void onGlobalLayout() {
            Context context = root.getContext();
            if (context == null) return;
            // Search the whole window, not just the tab bar: some targets (the
            // Instants "+" overlay and the Notes tray) live in the DM-inbox
            // subtree, which is a sibling of the tab bar, not a descendant. The
            // tab bar's ViewTreeObserver still fires for those layout passes.
            View searchRoot = root.getRootView();
            if (searchRoot == null) searchRoot = root;
            // Optionally narrow the search to a named container, so a view id that
            // is reused elsewhere in the window is only touched inside that subtree.
            if (scope != null) {
                int scopeId = resolveId(context, scope);
                View scopeView = scopeId == 0 ? null : searchRoot.findViewById(scopeId);
                if (scopeView == null) return; // container not on this screen; leave everything alone
                searchRoot = scopeView;
            }
            boolean pref = Config.getBlocked(key, defaultValue);
            boolean hidden = invert ? !pref : pref; // invert: pref true = shown
            int visibility = hidden ? View.GONE : View.VISIBLE;
            for (String name : names) {
                int id = resolveId(context, name);
                if (id == 0) continue;
                View view = searchRoot.findViewById(id);
                if (view != null) view.setVisibility(visibility);
            }
        }
    }

    /**
     * Hides the "Friends" tab — the friend lane, shown as a label plus a facepile
     * of avatars next to "Reels" — from the Reels viewer's top action bar.
     *
     * Instagram only gates it on a server flag
     * ({@code friends_lane_floating_pogs_entrypoint_enabled}) reachable from an
     * internal developer menu, so there is nothing to intercept: the entry point
     * is removed from the view tree instead.
     *
     * The tabs carry no per-tab resource id, so they are addressed by position:
     * {@code clips_viewer_action_bar} holds {@code action_bar_tab_layout}, a
     * horizontal scroller wrapping a single row with one child per tab, Reels
     * first and any lane appended after it. Everything past the first tab is
     * hidden, so a second lane would go with it. The search is scoped to the
     * clips action bar because {@code action_bar_tab_layout} is a generic id
     * reused by other tabbed surfaces.
     */
    static final class FriendsLaneHider implements ViewTreeObserver.OnGlobalLayoutListener {
        private final ViewGroup root;

        FriendsLaneHider(ViewGroup root) {
            this.root = root;
        }

        @Override
        public void onGlobalLayout() {
            Context context = root.getContext();
            if (context == null) return;
            View searchRoot = root.getRootView();
            if (searchRoot == null) searchRoot = root;

            int barId = resolveId(context, "clips_viewer_action_bar");
            if (barId == 0) return;
            View bar = searchRoot.findViewById(barId);
            if (bar == null) return; // not on the Reels surface right now

            int tabsId = resolveId(context, "action_bar_tab_layout");
            if (tabsId == 0) return;
            View tabs = bar.findViewById(tabsId);
            if (!(tabs instanceof ViewGroup)) return;

            ViewGroup strip = (ViewGroup) tabs;
            // Step through the scroller to the row that actually holds the tabs.
            if (strip instanceof HorizontalScrollView && strip.getChildCount() == 1
                    && strip.getChildAt(0) instanceof ViewGroup) {
                strip = (ViewGroup) strip.getChildAt(0);
            }

            int visibility = Config.isFriendsLaneBlocked() ? View.GONE : View.VISIBLE;
            for (int i = 1; i < strip.getChildCount(); i++) {
                strip.getChildAt(i).setVisibility(visibility);
            }
        }
    }

    /**
     * Redirects to the chosen landing surface (search/direct/profile) once per
     * tab-bar build, then detaches. "home" needs no redirect.
     */
    static final class LandingWatcher implements ViewTreeObserver.OnGlobalLayoutListener {
        private static final int MAX_ATTEMPTS = 30;
        private ViewGroup container;
        private boolean done;
        private int attempts;

        LandingWatcher(ViewGroup container) {
            this.container = container;
        }

        @Override
        public void onGlobalLayout() {
            ViewGroup root = container;
            if (root == null) return;
            if (done) {
                detach();
                return;
            }

            Context context = root.getContext();
            if (context == null) return;

            String landing = Config.getLandingPage();
            String target;
            if ("search".equals(landing)) {
                target = "search_tab";
            } else if ("direct".equals(landing)) {
                target = "direct_tab";
            } else if ("profile".equals(landing)) {
                target = "profile_tab";
            } else {
                detach(); // "home" or unknown: nothing to do
                return;
            }

            int id = resolveId(context, target);
            if (id == 0) {
                detach(); // not present in this build
                return;
            }

            // Search the whole window so action-bar entries are reachable too.
            View view = root.getRootView().findViewById(id);
            if (view == null) {
                if (++attempts >= MAX_ATTEMPTS) detach();
                return; // not laid out yet; retry up to the bound
            }

            view.performClick();
            done = true;
            detach();
        }

        private void detach() {
            ViewGroup root = container;
            if (root != null) {
                root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                container = null;
            }
        }
    }
}
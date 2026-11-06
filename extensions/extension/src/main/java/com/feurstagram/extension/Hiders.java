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
        // Notes tray (also taken out of the inbox's list, see onListUpdate), Instants entry-points.
        observer.addOnGlobalLayoutListener(new VisibilityHider(root, "block_notes", "cf_hub_recycler_view"));
        observer.addOnGlobalLayoutListener(new VisibilityHider(root, "block_instants",
                "creation_entrypoint", "direct_quick_snap_consumption_preview"));
        // Notifications ("heart") button in the feed header. Opt-in (default visible).
        // Scoped to the feed action bar's right button container: the target view id
        // ("notification") is shared with the unread-DM badge on the Direct tab, so
        // an unscoped search could hide that badge instead.
        observer.addOnGlobalLayoutListener(new VisibilityHider(root, "block_notifications",
                false, false, "action_bar_buttons_container_right", "notification"));
        // On large screens that button is a tab of the side rail.
        observer.addOnGlobalLayoutListener(new VisibilityHider(root, "block_notifications",
                false, false, "ls_nav_bar", "news_tab"));
        // Bottom-navigation icons, each shown/hidden independently. These use the
        // inverted "nav_show_<tab>" preference (true = shown). Home has its own
        // hider: it carries the settings gesture, so it may only go when another
        // icon stays to take it over. Reels defaults to hidden, matching the old
        // "blocking reels hid its tab" behaviour.
        observer.addOnGlobalLayoutListener(new HomeTabHider(root));
        observer.addOnGlobalLayoutListener(new VisibilityHider(root, "nav_show_search", true, true, null, "search_tab"));
        observer.addOnGlobalLayoutListener(new VisibilityHider(root, "nav_show_reels", false, true, null, "clips_tab"));
        observer.addOnGlobalLayoutListener(new VisibilityHider(root, "nav_show_create", true, true, null, "creation_tab"));
        observer.addOnGlobalLayoutListener(new VisibilityHider(root, "nav_show_direct", true, true, null, "direct_tab"));
        observer.addOnGlobalLayoutListener(new VisibilityHider(root, "nav_show_profile", true, true, null, "profile_tab"));
        // "Friends" tab in the Reels viewer header.
        observer.addOnGlobalLayoutListener(new FriendsLaneHider(root));
        // Cold-start landing-page redirect.
        observer.addOnGlobalLayoutListener(new LandingWatcher(root));
        // Keep swipes off the pages whose tab was hidden.
        HiddenTabSwipeSkipper.install(root);
        // Stories tray on top of Messages, when Quoicouview mode is on.
        Quoicouview.install(root);
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
     * Called by Instagram's Back handling with the depth of its tab history (the
     * tabs Back walks back through, oldest first): the depth to act on.
     *
     * With the Home icon hidden (the toggle, or Quoicouview mode) the history
     * still starts on Home, where Instagram always starts, and Back from the
     * first tab shown would walk back onto the hidden feed. A hidden Home is not
     * counted, so Back from that tab leaves the app, as Back from Home does.
     * Instagram's own start is left alone: starting elsewhere changes how it
     * presents stories opened from Messages.
     */
    public static int backHistoryDepth(java.util.List<?> history, int depth) {
        try {
            if (history == null || Config.isHomeTabShown()) return depth;
            int shown = 0;
            for (Object entry : history) {
                if (!"fragment_feed".equals(fragmentTag(tabOf(entry)))) shown++;
            }
            return Math.min(depth, shown);
        } catch (Throwable ignored) {
            return depth;
        }
    }

    /** The tab enum constant a tab-history entry holds, or null. */
    private static Object tabOf(Object entry) {
        if (entry == null) return null;
        for (java.lang.reflect.Field field : entry.getClass().getDeclaredFields()) {
            if (!field.getType().isEnum() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            try {
                field.setAccessible(true);
                return field.get(entry);
            } catch (Throwable ignored) {
                // next field
            }
        }
        return null;
    }

    /** The "fragment_..." tag a tab enum constant carries, or null. */
    private static String fragmentTag(Object tab) {
        if (tab == null) return null;
        for (Class<?> c = tab.getClass(); c != null && c != Enum.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                if (field.getType() != String.class
                        || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(tab);
                    if (value instanceof String && ((String) value).startsWith("fragment_")) {
                        return (String) value;
                    }
                } catch (Throwable ignored) {
                    // next field
                }
            }
        }
        return null;
    }

    /**
     * The bar holding the tabs in the window of {@code view}, or null if it is not
     * laid out: the bottom bar, or on large screens (tablets, unfolded phones) the
     * side rail, where the bottom bar stays in the layout, empty (issue #160).
     */
    static ViewGroup tabBar(View view) {
        if (view == null) return null;
        Context context = view.getContext();
        View window = view.getRootView();
        if (context == null || window == null) return null;
        ViewGroup bottom = barById(window, context, "tab_bar");
        if (bottom != null && bottom.getChildCount() > 0) return bottom;
        ViewGroup rail = barById(window, context, "ls_nav_bar");
        return rail != null && rail.getChildCount() > 0 ? rail : bottom;
    }

    private static ViewGroup barById(View window, Context context, String name) {
        int id = resolveId(context, name);
        View bar = id == 0 ? null : window.findViewById(id);
        return bar instanceof ViewGroup ? (ViewGroup) bar : null;
    }

    /**
     * Whether a row of a list is the Notes tray. The tray's list model is
     * obfuscated, so the Notes tray patch rewrites this body with a check for it.
     */
    static boolean isNotesRow(Object row) {
        return false;
    }

    /**
     * Called with every list update an {@code IgRecyclerViewAdapter} applies: takes
     * the Notes tray out when Notes are blocked. Instagram draws that row either
     * around {@code cf_hub_recycler_view} or, on some accounts, with Compose and no
     * resource id at all, so the row itself goes rather than a view (issue #144).
     */
    public static void onListUpdate(Object update) {
        try {
            if (update == null || !Config.isNotesBlocked()) return;
            java.lang.reflect.Field field = Quoicouview.updateList(update);
            if (field == null) return;
            @SuppressWarnings("unchecked")
            java.util.List<Object> rows = (java.util.List<Object>) field.get(update);
            if (rows == null) return;
            java.util.List<Object> kept = null;
            for (int i = 0; i < rows.size(); i++) {
                Object row = rows.get(i);
                if (isNotesRow(row)) {
                    if (kept == null) kept = new java.util.ArrayList<>(rows.subList(0, i));
                } else if (kept != null) {
                    kept.add(row);
                }
            }
            if (kept != null) field.set(update, kept);
        } catch (Throwable ignored) {
            // the list goes through as Instagram built it
        }
    }

    /** A tab's resource name ("feed_tab", "direct_tab", ...), or null. */
    static String tabName(View tab) {
        int id = tab.getId();
        if (id == View.NO_ID) return null;
        try {
            return tab.getResources().getResourceEntryName(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Whether Home is off the bar: the user hid it and another icon of this bar
     * is still shown to carry the settings gesture (see {@link Config#isHomeTabShown}).
     * The bar is checked too because Instagram drops tabs between versions, so a
     * tab the preferences call shown may not exist here.
     */
    static boolean isHomeHidden(ViewGroup bar) {
        if (Config.isHomeTabShown()) return false;
        for (int i = 0; i < bar.getChildCount(); i++) {
            String name = tabName(bar.getChildAt(i));
            String key = Config.navKeyForTab(name);
            if (key != null && !"feed_tab".equals(name) && Config.isNavTabShown(key)) return true;
        }
        return false;
    }

    /**
     * The tab whose long-press opens the settings: Home while it is on the bar,
     * otherwise Messages in Quoicouview mode and the first icon still shown out of
     * it. Null until the bar is laid out.
     */
    static View settingsTab(View anyView) {
        ViewGroup bar = tabBar(anyView);
        if (bar == null) return null;
        // Messages is the main page there, wherever the layout puts its icon.
        if (Config.isQuoicouviewEnabled()) {
            for (int i = 0; i < bar.getChildCount(); i++) {
                View tab = bar.getChildAt(i);
                if ("direct_tab".equals(tabName(tab))) return tab;
            }
        }
        View home = null;
        for (int i = 0; i < bar.getChildCount(); i++) {
            View tab = bar.getChildAt(i);
            if ("feed_tab".equals(tabName(tab))) home = tab;
        }
        if (home != null && !isHomeHidden(bar)) return home;
        for (int i = 0; i < bar.getChildCount(); i++) {
            View tab = bar.getChildAt(i);
            String name = tabName(tab);
            String key = Config.navKeyForTab(name);
            if (key != null && !"feed_tab".equals(name) && Config.isNavTabShown(key)) return tab;
        }
        return home;
    }

    /** Shows or hides the Home icon on every layout pass; see {@link #isHomeHidden}. */
    static final class HomeTabHider implements ViewTreeObserver.OnGlobalLayoutListener {
        private final ViewGroup root;

        HomeTabHider(ViewGroup root) {
            this.root = root;
        }

        @Override
        public void onGlobalLayout() {
            ViewGroup bar = tabBar(root);
            if (bar == null) return;
            int visibility = isHomeHidden(bar) ? View.GONE : View.VISIBLE;
            for (int i = 0; i < bar.getChildCount(); i++) {
                View tab = bar.getChildAt(i);
                if ("feed_tab".equals(tabName(tab)) && tab.getVisibility() != visibility) {
                    tab.setVisibility(visibility);
                }
            }
        }
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
            // Nav icons go through Config, which knows the ones a mode forces.
            boolean pref = key.startsWith("nav_show_") ? Config.isNavTabShown(key)
                    : Config.getBlocked(key, defaultValue);
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
     * tab-bar build, then detaches. "home" needs no redirect, unless the Home icon
     * is hidden: the app would open on a page that is off the bar, so it moves on
     * to the first tab that is shown.
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
            } else if (Config.isHomeTabShown()) {
                detach(); // "home" or unknown: nothing to do
                return;
            } else {
                target = null; // Home is hidden: the first tab still shown
            }

            View view;
            if (target == null) {
                ViewGroup bar = tabBar(root);
                if (bar == null) {
                    if (++attempts >= MAX_ATTEMPTS) detach();
                    return; // not laid out yet; retry up to the bound
                }
                if (!isHomeHidden(bar)) {
                    detach(); // Home kept: no other tab of this bar is shown
                    return;
                }
                view = firstLandableTab(bar);
                if (view == null) {
                    detach();
                    return;
                }
            } else {
                int id = resolveId(context, target);
                if (id == 0) {
                    detach(); // not present in this build
                    return;
                }
                // Search the whole window so action-bar entries are reachable too.
                view = root.getRootView().findViewById(id);
            }
            if (view == null) {
                if (++attempts >= MAX_ATTEMPTS) detach();
                return; // not laid out yet; retry up to the bound
            }

            view.performClick();
            done = true;
            detach();
        }

        /** First tab shown on the bar that opens a page (Home and Create excluded). */
        private View firstLandableTab(ViewGroup bar) {
            for (int i = 0; i < bar.getChildCount(); i++) {
                View tab = bar.getChildAt(i);
                String name = tabName(tab);
                if ("feed_tab".equals(name) || "creation_tab".equals(name)) continue;
                String key = Config.navKeyForTab(name);
                if (key != null && Config.isNavTabShown(key)) return tab;
            }
            return null;
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

package com.feurstagram.extension;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Quoicouview mode (experimental, off by default): Messages becomes the app's
 * main page and the stories tray sits at the top of it, under its search bar,
 * with Home turned off;
 * the notifications button of Home's header moves to the inbox's header.
 *
 * The Home tab and the landing page are handled by {@link Config} (Home counts
 * as hidden, Messages as shown, the landing page is Messages). The stories go
 * through Instagram's own code, hooked by the Quoicouview patch:
 *
 * <h3>A row of the inbox</h3>
 * The inbox is an {@code IgRecyclerViewAdapter}: a list of models, each drawn
 * by the row type registered for its class. The patch registers one more row
 * type, {@link StoriesTrayRow}, when the adapter is built, and every list
 * update the inbox submits goes through {@link #onListUpdate}, which puts the
 * stories row's model right after the search bar's ({@link #isSearchRow}), or
 * first when there is none. The row then lives like any other: it scrolls
 * with the inbox, is recycled and rebound by the list.
 *
 * The row shows Instagram's stories tray itself, not a copy: the feed's
 * {@code reels_tray_container}. Instagram keeps every tab's page alive in the
 * main pager, so the feed, though off screen, keeps feeding its tray (new
 * stories, seen rings, "Your story"), wherever the tray is drawn. The tray is
 * placed in the row when the row is bound, and again if the feed ever builds a
 * new one.
 *
 * <h3>Opening a story</h3>
 * A tap on the tray goes to the feed's tray controller, which opens the viewer
 * as it does on Home: grown out of the tapped avatar, stacked in the current
 * tab, so Back and the swipe-down close it back into the inbox. Two things
 * stand in the way, both handled through the patch:
 * <ul>
 *   <li>{@link #isStoryTrayVisible}: the controller's two "is the feed what the
 *       user is looking at" checks (otherwise it logs "story_pog_blocked" and
 *       drops the tap) are answered for the tray, which is on screen;</li>
 *   <li>the controller is a lifecycle listener of the feed fragment: as its
 *       page resumes it shrinks the closing viewer back into the avatar and
 *       refreshes the tray, as it pauses it stops what runs. Here the feed
 *       never resumes; the inbox's resume and pause are passed on to it
 *       instead ({@link #trayControllerToResume},
 *       {@link #trayControllerToPause}).</li>
 * </ul>
 * The viewer is pushed into the tab with a replace, which tears the page
 * beneath down and builds it again on close: cheap for the feed, but the
 * inbox's rebuild held the closing animation back. A viewer opened from the
 * tray is added over the inbox instead ({@link #stacksStoryViewer}), and the
 * controller gets the pause and resume the replace would have given it as the
 * viewer comes ({@link #trayControllerToCover}) and goes
 * ({@link #trayControllerToUncover}).
 *
 * <h3>The notifications button</h3>
 * Home's header has the notifications ("heart") button, with its unread badge;
 * the inbox's header has none. Like the tray, Instagram's own button is moved,
 * first among the inbox header's end buttons: the feed keeps its badge up to
 * date and its tap opens the notifications in the current tab, here Messages.
 * It stays in Home's header, hidden, when the settings block it.
 *
 * <h3>The camera</h3>
 * The story camera is the panel left of the tab pages, in Instagram's swipe
 * navigation: Home's swipe to the right drags it in, and the "Your story" (+)
 * of the tray slides it in. Instagram only turns the panel on, and only lets a
 * swipe from the main page reach it, while Home is the current tab: on
 * Messages, the panel is turned off, so the tray's (+) did nothing. Both
 * checks ask the tab controller whether Home is current; the patch has them
 * accept Messages too ({@link #messagesIsMainPage}). A swipe is only taken by
 * the swipe navigation when nothing under the finger can scroll that way, and
 * the tab pager always can, onto the hidden pages left of Messages: the pager
 * says it cannot when every tab on that side is hidden
 * ({@link #canScrollHorizontally}). The tray and the notes, which are checked first,
 * still scroll as they do on Home.
 */
public final class Quoicouview {

    private Quoicouview() {}

    /** The tab-bar root of the current window, to find the feed's tray and the inbox. */
    private static WeakReference<ViewGroup> sRoot = new WeakReference<>(null);

    /**
     * The tray last placed in a row. The list may bind the row to a new holder
     * while the old one, tray inside, sits detached in its recycling pool: the
     * tray is then out of the window, and this is how it is found again.
     */
    private static WeakReference<View> sTray = new WeakReference<>(null);

    /** The notifications button last placed in the inbox's header (see {@link #sTray}). */
    private static WeakReference<View> sHeart = new WeakReference<>(null);

    /** The feed's tray controller, as it is built: the latest one is the live feed's. */
    private static WeakReference<Object> sTrayController = new WeakReference<>(null);

    /** The controller resumed along with the inbox, due a pause with it. */
    private static WeakReference<Object> sResumedController = new WeakReference<>(null);

    /** Whether the inbox is resumed, as its own resume and pause say. */
    private static boolean sInboxResumed;

    /** The parameters of the tray's last story launch. */
    private static WeakReference<Object> sTrayLaunch = new WeakReference<>(null);

    /** The viewer being launched comes from the tray in the inbox: add it over the inbox. */
    private static boolean sStackNextViewer;

    /** A viewer was added over the inbox, and its fragment is yet to be created. */
    private static boolean sAwaitingViewer;

    /** The viewer added over the inbox, covering the tray's page while it lives. */
    private static WeakReference<Object> sStackedViewer = new WeakReference<>(null);

    /** The inbox's adapter once recognised, so later updates skip the lookup. */
    private static WeakReference<Object> sInboxAdapter = new WeakReference<>(null);

    /** The list field of the list-update class, found once. */
    private static Field sUpdateList;

    /** Install on the tab-bar root; inert unless the mode is on. */
    static void install(ViewGroup root) {
        if (root == null || !Config.isQuoicouviewEnabled()) return;
        sRoot = new WeakReference<>(root);
        // The feed builds its tray after the inbox may have bound the row, and
        // rebuilds it now and then: place it in the row whenever it shows up.
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                try {
                    adoptTray(null);
                    adoptHeart();
                    resumeLateController();
                } catch (Throwable ignored) {
                    // a layout pass Instagram reshaped: leave everything as it is
                }
            }
        });
    }

    // --- Called from Instagram's code (Quoicouview patch) -------------------------

    /**
     * Called as each {@code IgRecyclerViewAdapter} is built: the stories row type
     * to register in it, or null. Every adapter gets it while the mode is on (a
     * row type no list uses costs nothing); only the inbox's list ever holds the
     * row's model.
     */
    public static Object storiesRowDefinition() {
        return Config.isQuoicouviewEnabled() ? new StoriesTrayRow() : null;
    }

    /**
     * Called with every list update an {@code IgRecyclerViewAdapter} applies.
     * For the inbox, puts the stories row right after the search bar, or first
     * when there is none.
     */
    public static void onListUpdate(Object adapter, Object update) {
        try {
            if (!Config.isQuoicouviewEnabled() || adapter == null || update == null) return;
            if (!isInboxAdapter(adapter)) return;
            Field field = updateList(update);
            if (field == null) return;
            @SuppressWarnings("unchecked")
            List<Object> rows = (List<Object>) field.get(update);
            if (rows == null) return;
            List<Object> withTray = new ArrayList<>(rows.size() + 1);
            int at = 0;
            for (Object row : rows) {
                if (row == StoriesTrayRow.Model.INSTANCE) continue;
                withTray.add(row);
                if (at == 0 && isSearchRow(row)) at = withTray.size();
            }
            withTray.add(at, StoriesTrayRow.Model.INSTANCE);
            // Already in place: leave the update as it is.
            if (rows.size() == withTray.size() && rows.get(at) == StoriesTrayRow.Model.INSTANCE) return;
            field.set(update, withTray);
        } catch (Throwable ignored) {
            // the list goes through as Instagram built it
        }
    }

    /**
     * Whether a row of the inbox is its search bar, which the stories row goes
     * under. The search bar's list models are obfuscated, so the patch rewrites
     * this body with a check for each of them.
     */
    static boolean isSearchRow(Object row) {
        return false;
    }

    /** The stories row type's view: an empty frame the tray is placed in when bound. */
    public static View createStoriesRow(LayoutInflater inflater, ViewGroup parent) {
        StoriesTrayRow.Host host = new StoriesTrayRow.Host(parent.getContext());
        host.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return host;
    }

    /** The stories row is bound: make sure the tray is in it. */
    public static void bindStoriesRow(View row) {
        try {
            if (row instanceof StoriesTrayRow.Host) adoptTray((StoriesTrayRow.Host) row);
        } catch (Throwable ignored) {
            // the row stays empty until the next layout pass places the tray
        }
    }

    /**
     * Called by the feed's tray controller with the feed fragment's visibility
     * before it opens a story: also true when the tray is on screen in the inbox.
     */
    public static boolean isStoryTrayVisible(boolean feedVisible) {
        if (feedVisible || !Config.isQuoicouviewEnabled()) return feedVisible;
        View tray = tray();
        return tray != null && tray.isShown() && tray.getParent() instanceof StoriesTrayRow.Host;
    }

    /** Called as the feed's tray controller is built. */
    public static void onTrayController(Object controller) {
        sTrayController = new WeakReference<>(controller);
    }

    /** Called as the inbox resumes: the tray controller to resume with it, or null. */
    public static Object trayControllerToResume() {
        sInboxResumed = true;
        return resumeController();
    }

    /** Called as the inbox pauses: the tray controller to pause with it, or null. */
    public static Object trayControllerToPause() {
        sInboxResumed = false;
        return pauseController();
    }

    /** The controller to resume: never twice in a row, nor while a viewer covers the inbox. */
    private static Object resumeController() {
        if (sResumedController.get() != null || sStackedViewer.get() != null || sAwaitingViewer
                || !Config.isQuoicouviewEnabled()) {
            return null;
        }
        Object controller = sTrayController.get();
        if (controller == null || !isFeedAttached(controller)) return null;
        sResumedController = new WeakReference<>(controller);
        return controller;
    }

    /** The controller to pause: the one resumed, if any. */
    private static Object pauseController() {
        Object controller = sResumedController.get();
        sResumedController = new WeakReference<>(null);
        return controller;
    }

    /**
     * The inbox can resume before the feed page is built: its controller does not
     * exist yet, or its fragment is not attached (and it would crash asking for its
     * activity). It gets the resume it missed on a later layout pass.
     */
    private static void resumeLateController() throws ReflectiveOperationException {
        if (!sInboxResumed) return;
        Object controller = resumeController();
        if (controller != null) controller.getClass().getMethod("onResume").invoke(controller);
    }

    /** Whether the fragment a tray controller belongs to is attached. */
    private static boolean isFeedAttached(Object controller) {
        try {
            Class<?> fragment = Class.forName("androidx.fragment.app.Fragment", false,
                    controller.getClass().getClassLoader());
            for (Field field : controller.getClass().getDeclaredFields()) {
                if (!fragment.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object owner = field.get(controller);
                if (owner != null) return (Boolean) fragment.getMethod("isAdded").invoke(owner);
            }
        } catch (Throwable ignored) {
            // a controller Instagram reshaped: its own checks are all there is
        }
        return true;
    }

    /** Called by the tray controller with the parameters of each story launch it makes. */
    public static void onTrayLaunch(Object params) {
        sTrayLaunch = new WeakReference<>(params);
    }

    /** Called by the story launcher as it opens a viewer, with the launch's parameters. */
    public static void onStoryViewerLaunch(Object params) {
        sStackNextViewer = params != null && params == sTrayLaunch.get()
                && Config.isQuoicouviewEnabled() && isStoryTrayVisible(false);
    }

    /**
     * Called as the launcher commits the viewer into the tab: whether to add it
     * over the page rather than replace the page.
     */
    public static boolean stacksStoryViewer() {
        boolean stack = sStackNextViewer;
        sStackNextViewer = false;
        if (stack) sAwaitingViewer = true;
        return stack;
    }

    /** Called once a viewer was added over the inbox: the controller to pause, its page now covered. */
    public static Object trayControllerToCover() {
        return pauseController();
    }

    /** Called as each story viewer is created. */
    public static void onStoryViewerCreated(Object viewer) {
        if (!sAwaitingViewer) return;
        sAwaitingViewer = false;
        sStackedViewer = new WeakReference<>(viewer);
    }

    /**
     * Called as each story viewer is destroyed: when it is the one added over
     * the inbox, the controller to resume, its page uncovered.
     */
    public static Object trayControllerToUncover(Object viewer) {
        if (viewer == null || viewer != sStackedViewer.get()) return null;
        sStackedViewer = new WeakReference<>(null);
        return sInboxResumed ? resumeController() : null;
    }

    // --- The feed page behind the tray ---------------------------------------------

    /**
     * Called by every check of the main navigation's variant, which reads this
     * override first: the variant to force, or null to let the server decide.
     * Instagram has three layouts, picked per account and sometimes per launch: the
     * one with a tab pager and a Messages tab, an older one with no Messages tab
     * and each page torn down as it is left, and one whose Home page holds no
     * stories tray. This mode only stands on the first, so it is forced.
     */
    public static Object forcedNavigationVariant(Class<?> variants) {
        Boolean forced = sForcesVariant;
        if (forced == null) {
            if (Config.getAppContext() == null) return null;
            forced = Config.isQuoicouviewEnabled();
            sForcesVariant = forced;
        }
        if (!forced) return null;
        if (sForcedVariant == null) {
            for (Object variant : variants.getEnumConstants()) {
                if ("REELS_SECOND".equals(((Enum<?>) variant).name())) sForcedVariant = variant;
            }
        }
        return sForcedVariant;
    }

    /** Settings changes restart the process, and the variant is asked all over the app. */
    private static Boolean sForcesVariant;

    private static Object sForcedVariant;

    /**
     * Called by each tab page of the pager as it is created: whether it loads its
     * content right away, when Instagram says not to. The pages are built empty
     * and fill up when first shown, and Home's is never shown here if the app did
     * not start on it (a notification, a link): it holds the stories tray and has
     * to load anyway.
     */
    public static boolean loadsTabEagerly(boolean eager, android.os.Bundle arguments) {
        try {
            if (eager || arguments == null || !Config.isQuoicouviewEnabled()) return eager;
            String tab = arguments.getString("TAB_FRAGMENT_TAG");
            return tab != null && tab.startsWith("fragment_feed");
        } catch (Throwable ignored) {
            return eager;
        }
    }

    // --- The camera ----------------------------------------------------------------

    /**
     * Called where the tab controller decides whether the camera panel is on and
     * whether a swipe from the main page may reach it, each time it found that
     * Home is not the current tab: whether Messages counts as the main page.
     */
    public static boolean messagesIsMainPage() {
        return Config.isQuoicouviewEnabled();
    }

    /**
     * Stands in for {@link View#canScrollHorizontally} in the swipe navigation's
     * check of what lies under the finger. The tab pager answers no towards the
     * start when every tab on that side is hidden, so that swipe drags the
     * camera in, as from Home.
     */
    public static boolean canScrollHorizontally(View view, int direction) {
        boolean canScroll = view.canScrollHorizontally(direction);
        try {
            if (!canScroll || direction >= 0 || !Config.isQuoicouviewEnabled()
                    || view.getLayoutDirection() != View.LAYOUT_DIRECTION_LTR) {
                return canScroll;
            }
            // Asked of the pager and of the list inside it, which the pager's
            // own answer comes from.
            Context context = view.getContext();
            int pagerId = Hiders.resolveId(context, "swipeable_tab_view_pager");
            boolean pager = pagerId != 0 && (view.getId() == pagerId
                    || (view.getParent() instanceof View && ((View) view.getParent()).getId() == pagerId));
            if (!pager) return canScroll;
            ViewGroup tabBar = Hiders.tabBar(view);
            if (tabBar == null) return canScroll;
            for (int i = 0; i < tabBar.getChildCount(); i++) {
                View tab = tabBar.getChildAt(i);
                if (tab.isSelected()) return false; // no page to reach before it
                if (isPageTab(tab)) return canScroll;
            }
        } catch (Throwable ignored) {
            // a pager Instagram reshaped: let it scroll
        }
        return canScroll;
    }

    /** A tab shown in the bar with a page behind it (Create opens the camera, not a page). */
    private static boolean isPageTab(View tab) {
        if (tab.getVisibility() != View.VISIBLE) return false;
        int create = Hiders.resolveId(tab.getContext(), "creation_tab");
        return create == 0 || tab.getId() != create;
    }

    // --- The stories row -----------------------------------------------------------

    /**
     * Move the tray into a stories row: {@code row} if given (the row being
     * bound), else the one laid out in the inbox.
     */
    private static void adoptTray(StoriesTrayRow.Host row) {
        View tray = tray();
        if (tray == null) return;
        ViewParent parent = tray.getParent();
        if (row == null) {
            // A tray in a row that is on screen is where it belongs.
            if (parent instanceof StoriesTrayRow.Host && ((View) parent).isAttachedToWindow()) return;
            row = attachedRow();
            if (row == null) return;
        }
        if (parent == row) return;
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(tray);
        row.removeAllViews();
        row.addView(tray, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sTray = new WeakReference<>(tray);
    }

    /**
     * The tray: the one in the window (the feed's, or the one in the row on
     * screen), else the one last placed in a row. findViewById walks the tree in
     * order and the feed page comes before the inbox in the pager, so a tray the
     * feed (re)built is the one found.
     */
    private static View tray() {
        ViewGroup root = sRoot.get();
        if (root == null) return null;
        int id = Hiders.resolveId(root.getContext(), "reels_tray_container");
        View tray = id == 0 ? null : root.getRootView().findViewById(id);
        return tray != null ? tray : sTray.get();
    }

    /** The stories row currently laid out in the inbox list, if any. */
    private static StoriesTrayRow.Host attachedRow() {
        ViewGroup list = inboxList();
        if (list == null) return null;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child instanceof StoriesTrayRow.Host) return (StoriesTrayRow.Host) child;
        }
        return null;
    }

    private static ViewGroup inboxList() {
        ViewGroup root = sRoot.get();
        if (root == null) return null;
        Context context = root.getContext();
        int id = Hiders.resolveId(context, "inbox_refreshable_thread_list_recyclerview");
        View list = id == 0 ? null : root.getRootView().findViewById(id);
        return list instanceof ViewGroup ? (ViewGroup) list : null;
    }

    // --- The notifications button --------------------------------------------------

    /**
     * Move the feed header's notifications button to the inbox header's end
     * buttons, before the new-message one. A button the feed (re)built is
     * preferred, else the one last placed, which the inbox header may have
     * dropped while rebuilding its buttons.
     */
    private static void adoptHeart() {
        if (Config.isNotificationsButtonBlocked()) return;
        ViewGroup root = sRoot.get();
        if (root == null) return;
        Context context = root.getContext();
        View window = root.getRootView();
        ViewGroup buttons = child(window, context, "direct_inbox_action_bar", "action_bar_end_action_buttons");
        if (buttons == null) return;
        View placed = sHeart.get();
        ViewGroup feedButtons = child(window, context, "main_feed_action_bar", "action_bar_buttons_container_right");
        View heart = feedButtons == null ? null : childById(context, feedButtons, "notification");
        if (heart == null) heart = placed;
        if (heart == null || heart.getParent() == buttons) return;
        if (placed != null && placed != heart && placed.getParent() instanceof ViewGroup) {
            ((ViewGroup) placed.getParent()).removeView(placed);
        }
        if (heart.getParent() instanceof ViewGroup) ((ViewGroup) heart.getParent()).removeView(heart);
        stopHeartBubble(heart);
        buttons.addView(heart, 0);
        sHeart = new WeakReference<>(heart);
        fitHeaderSides(context, buttons);
    }

    /**
     * No red bubble under the moved button, its unread dot stays. The bubble closes
     * when the feed pauses, and the feed stays alive off screen here, so it stayed
     * up, misplaced, after the notifications were read. The flag is read when the
     * button attaches to the window, so this goes between its removal and its adding.
     */
    private static void stopHeartBubble(View heart) {
        try {
            heart.getClass().getMethod("setShouldToast", boolean.class).invoke(heart, false);
        } catch (Throwable ignored) {
            // a renamed setter: the bubble stays as Instagram draws it
        }
    }

    /**
     * The header sizes its end buttons' container once, when configured, to the
     * buttons it has; with a centred title it gives the start container the
     * same width. Redo that for the button added.
     */
    private static void fitHeaderSides(Context context, ViewGroup buttons) {
        ViewGroup.LayoutParams endParams = buttons.getLayoutParams();
        if (endParams == null || endParams.width < 0) return; // sized by its content already
        buttons.measure(0, 0);
        int width = buttons.getMeasuredWidth();
        View start = buttons.getParent() instanceof ViewGroup
                ? childById(context, (View) buttons.getParent(), "action_bar_start_action_buttons") : null;
        ViewGroup.LayoutParams startParams = start == null ? null : start.getLayoutParams();
        if (startParams != null && startParams.width == endParams.width) {
            start.measure(0, 0);
            width = Math.max(width, start.getMeasuredWidth());
            startParams.width = width;
            start.setLayoutParams(startParams);
        }
        endParams.width = width;
        buttons.setLayoutParams(endParams);
    }

    /** The ViewGroup {@code inner} inside the view {@code outer} of the window. */
    private static ViewGroup child(View window, Context context, String outer, String inner) {
        View container = childById(context, window, outer);
        View found = container == null ? null : childById(context, container, inner);
        return found instanceof ViewGroup ? (ViewGroup) found : null;
    }

    private static View childById(Context context, View parent, String name) {
        int id = Hiders.resolveId(context, name);
        return id == 0 ? null : parent.findViewById(id);
    }

    // --- Recognising the inbox's adapter --------------------------------------------

    /**
     * Whether {@code adapter} is the one the inbox list shows: the list view holds
     * it in one of its (obfuscated) fields.
     */
    private static boolean isInboxAdapter(Object adapter) {
        if (sInboxAdapter.get() == adapter) return true;
        ViewGroup list = inboxList();
        if (list == null) return false;
        for (Class<?> c = list.getClass(); c != null && c != View.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.getType().isPrimitive()
                        || java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        || !field.getType().isInstance(adapter)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    if (field.get(list) == adapter) {
                        sInboxAdapter = new WeakReference<>(adapter);
                        return true;
                    }
                } catch (Throwable ignored) {
                    // not readable: not ours to look at
                }
            }
        }
        return false;
    }

    /** The list update's single list field (its rows). */
    static Field updateList(Object update) {
        Field field = sUpdateList;
        if (field != null && field.getDeclaringClass() == update.getClass()) return field;
        for (Field candidate : update.getClass().getDeclaredFields()) {
            if (List.class.isAssignableFrom(candidate.getType())
                    && !java.lang.reflect.Modifier.isStatic(candidate.getModifiers())) {
                candidate.setAccessible(true);
                sUpdateList = candidate;
                return candidate;
            }
        }
        return null;
    }
}

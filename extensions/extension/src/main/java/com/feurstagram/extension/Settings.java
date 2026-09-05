package com.feurstagram.extension;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Feurstagram settings, shown as a full-screen page when the Home tab is
 * long-pressed. Built entirely in code (no bundled resources) and persisted
 * through {@link Config}.
 *
 * <h3>The look: Material 3 Expressive, monochrome</h3>
 * White is the only accent — no hue anywhere, so the panel reads as part of a
 * dark OS rather than as a themed app. The Expressive part is in the shapes and
 * the type: large corner radii, options grouped into "connected" lists where the
 * group's outer corners are round and the joints between rows are nearly square,
 * pill-shaped buttons, sentence-case section labels, and a tight-tracked headline.
 * This class also owns the palette, type and shape helpers the other Feurstagram
 * surfaces ({@link UpdateChecker}, {@link Onboarding}) draw with, so the design
 * only lives in one place.
 *
 * <h3>Fitting any screen</h3>
 * Instagram targets a recent SDK, so its windows are edge-to-edge: system bars
 * and display cutouts overlap the dialog and nothing is inset for us. Every
 * Feurstagram window therefore goes through {@link #applyWindowInsets} and pads
 * itself — otherwise the action buttons sit under a three-button navigation bar
 * and the scroll area runs off the bottom of the screen. Content that can grow
 * (the settings list, a release-notes block) lives in a scroller bounded by what
 * is actually on screen, so a short screen or landscape shrinks it instead of
 * pushing the buttons away.
 */
public final class Settings {

    // --- Colour: a neutral dark scheme with white as the single accent --------

    /** Page background. */
    public static final int SURFACE = 0xFF0E0E0E;
    /** Cards, list rows. */
    public static final int SURFACE_CONTAINER = 0xFF1B1B1B;
    /** One step up: tonal buttons, the raised confirm card. */
    public static final int SURFACE_CONTAINER_HIGH = 0xFF262626;
    public static final int ON_SURFACE = 0xFFF4F4F4;
    public static final int ON_SURFACE_VARIANT = 0xFFABABAB;
    public static final int OUTLINE = 0xFF757575;
    public static final int OUTLINE_VARIANT = 0xFF3A3A3A;
    /** The accent. Monochrome by design: white. */
    public static final int PRIMARY = 0xFFFFFFFF;
    public static final int ON_PRIMARY = 0xFF101010;
    /**
     * Destructive actions. A monochrome scheme has no red to lean on, so they
     * carry the same accent and are distinguished by their wording instead.
     */
    public static final int ERROR = 0xFFFFFFFF;
    public static final int ON_ERROR = 0xFF101010;
    public static final int DIVIDER = 0xFF2A2A2A;
    public static final int RIPPLE = 0x26FFFFFF;

    // --- Shape: the M3 Expressive corner scale --------------------------------

    /** Dialog / card corners. */
    private static final float CORNER_XL = 28f;
    /** The outer corners of a connected list group. */
    private static final float CORNER_L = 24f;
    /** The joints inside a connected list group. */
    private static final float CORNER_JOINT = 4f;
    /** Larger than half a view's height, so it renders as a pill. */
    private static final float CORNER_FULL = 200f;
    /** Gap between the rows of a connected group. */
    private static final int ROW_GAP_DP = 3;

    private Settings() {}

    /**
     * Install the Feurstagram entry points off the bottom tab bar: the long-press
     * settings gesture, the UI hiders, and the launch update check. The tab bar is
     * just a stable handle into the window's view tree.
     */
    public static void installHomeTabWatcher(ViewGroup tabBar) {
        if (tabBar == null) return;
        tabBar.getViewTreeObserver().addOnGlobalLayoutListener(new HomeTabWatcher(tabBar));
        Hiders.installAll(tabBar);
        Context activity = getActivityContext(tabBar);
        UpdateChecker.checkWhatsNew(activity);
        UpdateChecker.check(activity);
    }

    /** Unwrap a view's context down to the hosting Activity when possible. */
    public static Context getActivityContext(View view) {
        if (view == null) return null;
        Context context = view.getContext();
        Context cursor = context;
        while (cursor != null && !(cursor instanceof Activity) && cursor instanceof ContextWrapper) {
            cursor = ((ContextWrapper) cursor).getBaseContext();
        }
        return cursor != null ? cursor : context;
    }

    public static void show(Context context) {
        if (context == null) return;
        try {
            Dialog dialog = new Dialog(context, android.R.style.Theme_Material_NoActionBar);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setContentView(buildContent(context, dialog));

            // Intercept Back (incl. predictive-back gesture) to restart on change.
            dialog.setOnCancelListener(d -> {
                if (Config.isRestartPending()) {
                    CacheCleaner.clearAndRestart(context);
                }
            });

            styleWindow(dialog, SURFACE, 0f);
            dialog.show();
        } catch (Throwable t) {
            Toast.makeText(context, "Feurstagram settings unavailable here", Toast.LENGTH_LONG).show();
        }
    }

    // --- Window plumbing ------------------------------------------------------

    /**
     * Make a dialog window fill the screen edge to edge with light system-bar
     * icons, so the content underneath can pad itself against the bars and the
     * cutout. Every Feurstagram dialog goes through here.
     *
     * @param background window background colour, or 0 for a transparent scrim
     * @param dim        how much to darken what is behind (0 for the full-screen page)
     */
    public static void styleWindow(Dialog dialog, int background, float dim) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(background));
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        window.setDimAmount(dim);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);

        // Draw behind the bars and let the content pad itself. Without this the
        // window may not receive insets at all, and the padding below would be
        // computed from zeros.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        }
        window.getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        // Deprecated (and ignored) from API 35, but still what tints the bars on
        // everything older.
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        // The surface underneath is dark, so the bar icons must be light — the
        // host activity may have asked for the opposite.
        View decor = window.getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    /** Receives the space taken by system bars and display cutouts, in pixels. */
    public interface InsetTarget {
        void onInsets(int left, int top, int right, int bottom);
    }

    /**
     * Report the window's system-bar + cutout insets to {@code target}, now and on
     * every change (rotation, switching to three-button navigation, a foldable
     * unfolding).
     */
    public static void applyWindowInsets(View view, InsetTarget target) {
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            target.onInsets(left, top, right, bottom);
            return insets;
        });
        view.requestApplyInsets();
    }

    /**
     * Widest a column of text and controls is allowed to get. Past this a tablet,
     * an unfolded foldable or a landscape phone gives rows so wide the label and
     * its switch end up at opposite edges of the screen.
     */
    private static final int CONTENT_MAX_WIDTH_DP = 620;

    /**
     * Pad a full-screen root against the system bars and the cutout, and keep its
     * content centred within {@code maxWidthDp}.
     *
     * The width part has to run on every layout pass rather than only when the
     * insets change: {@code requestApplyInsets()} is asynchronous, so the first
     * layout happens with no insets at all, and a root that only listened for
     * insets would keep whatever it measured then.
     */
    public static void applyPageInsets(final View root, final int sideDp, final int verticalDp,
                                       final int maxWidthDp) {
        final Context context = root.getContext();
        final int[] insets = new int[4];
        Runnable apply = () -> {
            int usable = root.getWidth() - insets[0] - insets[2];
            int extra = Math.max(0, usable - dp(context, maxWidthDp)) / 2;
            int left = dp(context, sideDp) + insets[0] + extra;
            int top = dp(context, verticalDp) + insets[1];
            int right = dp(context, sideDp) + insets[2] + extra;
            int bottom = dp(context, verticalDp) + insets[3];
            if (root.getPaddingLeft() == left && root.getPaddingTop() == top
                    && root.getPaddingRight() == right && root.getPaddingBottom() == bottom) {
                return;
            }
            root.setPadding(left, top, right, bottom);
        };
        applyWindowInsets(root, (l, t, r, b) -> {
            insets[0] = l;
            insets[1] = t;
            insets[2] = r;
            insets[3] = b;
            apply.run();
        });
        root.getViewTreeObserver().addOnGlobalLayoutListener(apply::run);
    }

    // --- The full-screen page scaffold ----------------------------------------

    /**
     * The shape every Feurstagram full-screen surface takes: a headline block, a
     * scrolling middle that absorbs whatever space is left, and an action row
     * pinned above the navigation bar. Settings and both update screens are built
     * on it, so they inset, centre and shrink identically.
     */
    public static final class Page {
        public final LinearLayout root;
        public final LinearLayout header;
        public final ScrollView scroll;
        public final LinearLayout content;
        public final LinearLayout actions;

        Page(LinearLayout root, LinearLayout header, ScrollView scroll,
             LinearLayout content, LinearLayout actions) {
            this.root = root;
            this.header = header;
            this.scroll = scroll;
            this.content = content;
            this.actions = actions;
        }

        /** Add a pill that shares the action row equally with its siblings. */
        public Button addAction(Button button) {
            Context context = actions.getContext();
            if (actions.getChildCount() > 0) {
                View spacer = new View(context);
                actions.addView(spacer, new LinearLayout.LayoutParams(dp(context, 8), 1));
            }
            actions.addView(button, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return button;
        }
    }

    public static Page newPage(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SURFACE);
        applyPageInsets(root, 20, 12, CONTENT_MAX_WIDTH_DP);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(context, 4), dp(context, 12), dp(context, 4), dp(context, 4));
        root.addView(header);

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(context, 12));
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(context, 28));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.setMargins(0, dp(context, 12), 0, 0);
        root.addView(actions, actionsLp);

        return new Page(root, header, scroll, content, actions);
    }

    /** Headline plus supporting line, in a page's header block. */
    public static void addPageTitle(Page page, String titleText, String subtitleText) {
        Context context = page.root.getContext();
        TextView title = new TextView(context);
        title.setText(titleText);
        headline(title);
        page.header.addView(title);

        if (subtitleText != null) {
            TextView subtitle = new TextView(context);
            subtitle.setText(subtitleText);
            body(subtitle);
            subtitle.setPadding(0, dp(context, 6), 0, 0);
            page.header.addView(subtitle);
        }
    }

    /** A full-screen page dialog, styled and edge-to-edge. */
    public static Dialog newPageDialog(Context context, View content) {
        Dialog dialog = new Dialog(context, android.R.style.Theme_Material_NoActionBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(content);
        styleWindow(dialog, SURFACE, 0f);
        return dialog;
    }

    // --- The settings page ----------------------------------------------------

    private static View buildContent(Context context, Dialog dialog) {
        // Snapshot block state so the permanent lock freezes only what's blocked now.
        Config.captureBaseline();
        boolean hardcore = Config.isHardcoreMode();

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SURFACE);
        applyPageInsets(root, 20, 12, CONTENT_MAX_WIDTH_DP);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(context, 4), dp(context, 12), dp(context, 4), dp(context, 4));
        root.addView(header);

        TextView title = new TextView(context);
        title.setText("Feurstagram");
        headline(title);
        header.addView(title);

        TextView subtitle = new TextView(context);
        subtitle.setText(hardcore
                ? "Permanent lock is on. You can tighten blocks but not loosen them. "
                        + "Reinstall to fully unlock."
                : "Choose what to hide. Tap Done to clear the cache and restart.");
        body(subtitle);
        subtitle.setPadding(0, dp(context, 6), 0, 0);
        header.addView(subtitle);

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(context, 12));
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(context, 28));
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(column);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout surfaces = addSection(context, column, "Blocked surfaces");
        addRow(context, surfaces, "Home feed", "block_feed", Config.isFeedBlocked());
        addRow(context, surfaces, "Explore", "block_explore", Config.isExploreBlocked());
        addRow(context, surfaces, "Reels", "block_reels", Config.isReelsBlocked());
        addRow(context, surfaces, "Friends in Reels", "block_friends_lane", Config.isFriendsLaneBlocked());
        addRow(context, surfaces, "Stories", "block_stories", Config.isStoriesBlocked());
        addRow(context, surfaces, "Instants", "block_instants", Config.isInstantsBlocked());
        addRow(context, surfaces, "Notes", "block_notes", Config.isNotesBlocked());
        addRow(context, surfaces, "Suggested accounts", "block_suggested", Config.isSuggestedBlocked());
        addRow(context, surfaces, "Ads", "block_ads", Config.isAdsBlocked());
        addRow(context, surfaces, "Notifications button", "block_notifications",
                Config.isNotificationsButtonBlocked());
        sealGroup(context, surfaces);

        // Hiding a nav icon also takes its surface out of the landing-page picker,
        // so the two sections have to stay in step while the panel is open.
        final Runnable[] landingSync = new Runnable[1];
        Runnable onNavChanged = () -> {
            if (landingSync[0] != null) landingSync[0].run();
        };

        LinearLayout nav = addSection(context, column, "Navigation bar");
        addRow(context, nav, "Search", "nav_show_search", Config.getBlocked("nav_show_search", true), onNavChanged);
        addRow(context, nav, "Reels", "nav_show_reels", Config.getBlocked("nav_show_reels", false), onNavChanged);
        addRow(context, nav, "Create", "nav_show_create", Config.getBlocked("nav_show_create", true), onNavChanged);
        addRow(context, nav, "Messages", "nav_show_direct", Config.getBlocked("nav_show_direct", true), onNavChanged);
        addRow(context, nav, "Profile", "nav_show_profile", Config.getBlocked("nav_show_profile", true), onNavChanged);
        sealGroup(context, nav);

        LinearLayout feed = addSection(context, column, "Feed");
        addRow(context, feed, "Following feed only", "limit_following_feed", Config.isFollowingFeedOnly());
        sealGroup(context, feed);

        LinearLayout popups = addSection(context, column, "Popups");
        addRow(context, popups, "Instagram popups", "hide_toasts", Config.arePopupsHidden());
        sealGroup(context, popups);

        LinearLayout display = addSection(context, column, "Display");
        addRow(context, display, "Force dark (disable HDR)", "force_sdr", Config.isForceSdr());
        sealGroup(context, display);

        LinearLayout landing = addSection(context, column, "Landing page");
        buildLandingOptions(context, landing, landingSync);
        sealGroup(context, landing);

        LinearLayout updates = addSection(context, column, "Updates");
        addRow(context, updates, "Automatic update check", "auto_update", Config.isAutoUpdateEnabled());
        sealGroup(context, updates);

        Button checkUpdate = makeButton(context, "Check for updates", SURFACE_CONTAINER_HIGH, ON_SURFACE, true);
        checkUpdate.setOnClickListener(v -> UpdateChecker.checkNow(context));
        column.addView(checkUpdate, stackedButtonParams(context, 12));

        addSectionLabel(context, column, "Support");

        Button sponsors = makeButton(context, "Donate on GitHub Sponsors", 0, ON_SURFACE, false);
        sponsors.setBackground(outlined(context, OUTLINE_VARIANT));
        sponsors.setOnClickListener(v -> openUrl(context, "https://github.com/sponsors/jean-voila"));
        column.addView(sponsors, stackedButtonParams(context, 0));

        Button coffee = makeButton(context, "☕  Buy me a coffee", SURFACE_CONTAINER_HIGH, ON_SURFACE, true);
        coffee.setOnClickListener(v -> openUrl(context, "https://buymeacoffee.com/jean_voila"));
        column.addView(coffee, stackedButtonParams(context, 10));

        // Pinned action bar. Two equal pills rather than end-aligned buttons: it
        // cannot overflow, however narrow the screen or long the label.
        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.setMargins(0, dp(context, 12), 0, 0);
        root.addView(actions, actionsLp);

        Button lock = makeButton(context, "Permanent lock", SURFACE_CONTAINER_HIGH, ON_SURFACE, true);
        lock.setOnClickListener(v -> {
            dialog.dismiss();
            confirmHardcore(context);
        });
        if (hardcore) {
            lock.setEnabled(false);
            lock.setAlpha(0.38f);
        }
        actions.addView(lock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View spacer = new View(context);
        actions.addView(spacer, new LinearLayout.LayoutParams(dp(context, 8), 1));

        Button done = makeButton(context, "Done", PRIMARY, ON_PRIMARY, true);
        done.setOnClickListener(v -> {
            dialog.dismiss();
            onDone(context);
        });
        actions.addView(done, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        return root;
    }

    private static LinearLayout.LayoutParams stackedButtonParams(Context context, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(context, topDp), 0, 0);
        return lp;
    }

    private static void onDone(Context context) {
        if (context == null) return;
        // A pending change restarts straight away (no dialog to back out of).
        if (Config.isRestartPending()) {
            CacheCleaner.clearAndRestart(context);
            return;
        }
        showConfirm(context, "Restart Instagram?",
                "Feurstagram will clear the cache and restart the app to apply your changes.",
                "Restart", PRIMARY, ON_PRIMARY,
                () -> CacheCleaner.clearAndRestart(context));
    }

    private static void confirmHardcore(Context context) {
        if (context == null) return;
        if (Config.isHardcoreMode()) {
            Toast.makeText(context, "Permanent lock is already enabled", Toast.LENGTH_LONG).show();
            return;
        }
        showConfirm(context, "Enable permanent lock?",
                "This is permanent for this installation. You will no longer be able to "
                        + "re-enable Home feed, Explore, Reels or Stories without reinstalling the app.",
                "Enable lock", ERROR, ON_ERROR,
                () -> {
                    Config.enableHardcoreMode();
                    Toast.makeText(context,
                            "Permanent lock enabled. Reinstall the app to unlock content.",
                            Toast.LENGTH_LONG).show();
                });
    }

    // --- Confirmation cards ---------------------------------------------------

    /**
     * A centred card with a title, a body and two actions. Shared by the restart
     * and permanent-lock prompts, and shaped like {@link UpdateChecker}'s cards.
     */
    public static void showConfirm(Context context, String titleText, String bodyText,
                                   String confirmText, int confirmBg, int confirmFg,
                                   Runnable onConfirm) {
        try {
            Dialog dialog = new Dialog(context);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            FrameLayout frame = cardFrame(context);
            LinearLayout card = addCard(context, frame);

            TextView title = new TextView(context);
            title.setText(titleText);
            titleLarge(title);
            card.addView(title);

            // Scrollable, and the one thing allowed to shrink: a long warning must
            // not push the buttons off a short screen or a landscape phone.
            ScrollView bodyScroll = new ScrollView(context);
            bodyScroll.setVerticalScrollBarEnabled(false);
            TextView body = new TextView(context);
            body.setText(bodyText);
            body(body);
            body.setPadding(0, dp(context, 10), 0, 0);
            bodyScroll.addView(body);
            card.addView(bodyScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            boundToFrame(frame, card, bodyScroll);

            LinearLayout buttons = new LinearLayout(context);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            buttonsLp.setMargins(0, dp(context, 24), 0, 0);
            card.addView(buttons, buttonsLp);

            Button cancel = makeButton(context, "Cancel", 0, ON_SURFACE, false);
            cancel.setBackground(outlined(context, OUTLINE_VARIANT));
            cancel.setOnClickListener(v -> dialog.dismiss());
            buttons.addView(cancel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            View spacer = new View(context);
            buttons.addView(spacer, new LinearLayout.LayoutParams(dp(context, 8), 1));

            Button confirm = makeButton(context, confirmText, confirmBg, confirmFg, true);
            confirm.setOnClickListener(v -> {
                dialog.dismiss();
                onConfirm.run();
            });
            buttons.addView(confirm, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            dialog.setContentView(frame);
            dialog.setCanceledOnTouchOutside(true);
            styleWindow(dialog, 0, 0.6f);
            dialog.show();
        } catch (Throwable t) {
            Toast.makeText(context, "Unable to open confirmation", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * The full-window frame a centred card sits in: padded for the system bars and
     * the cutout, so a card never slips under them however the device is held.
     */
    public static FrameLayout cardFrame(Context context) {
        final FrameLayout frame = new FrameLayout(context);
        applyPageInsets(frame, 20, 20, CARD_MAX_WIDTH_DP);
        return frame;
    }

    /** M3 keeps dialogs narrow; past this they stop reading as a dialog. */
    private static final int CARD_MAX_WIDTH_DP = 520;

    /** Add the card itself, centred, to a {@link #cardFrame}. */
    public static LinearLayout addCard(Context context, FrameLayout frame) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedRect(SURFACE_CONTAINER, CORNER_XL, context));
        int pad = dp(context, 24);
        card.setPadding(pad, pad, pad, pad);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        frame.addView(card, lp);
        return card;
    }

    /**
     * Keep {@code card} inside {@code frame} by shrinking {@code growable} — the one
     * child allowed to give up height — when the card would otherwise be taller than
     * the space actually available. This is what lets a long release-notes block
     * survive landscape, a short screen or a three-button navigation bar instead of
     * pushing the buttons off the bottom.
     */
    public static void boundToFrame(final FrameLayout frame, final View card, final View growable) {
        // A global-layout listener, not frame.addOnLayoutChangeListener: the frame
        // fills the window and so never changes bounds, while the things that
        // matter — insets arriving a frame late, the card growing once the notes
        // are measured, a rotation — all show up as ordinary layout passes.
        frame.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            int available = frame.getHeight() - frame.getPaddingTop() - frame.getPaddingBottom();
            if (available <= 0 || card.getHeight() == 0) return;
            ViewGroup.LayoutParams lp = growable.getLayoutParams();
            int overflow = card.getHeight() - available;
            if (overflow > 0) {
                int height = Math.max(dp(frame.getContext(), 72), growable.getHeight() - overflow);
                if (lp.height != height) {
                    lp.height = height;
                    growable.setLayoutParams(lp);
                }
            } else if (lp.height != ViewGroup.LayoutParams.WRAP_CONTENT
                    && overflow < -dp(frame.getContext(), 8)) {
                // Room came back (rotation, bars hidden): let it grow and re-measure.
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                growable.setLayoutParams(lp);
            }
        });
    }

    private static void openUrl(Context context, String url) {
        if (context == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(context, "No browser available", Toast.LENGTH_LONG).show();
        }
    }

    // --- Grouped lists --------------------------------------------------------

    /** A sentence-case section label, M3 Expressive style (not all-caps). */
    private static void addSectionLabel(Context context, LinearLayout parent, String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        label.setTextColor(ON_SURFACE_VARIANT);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setLetterSpacing(0.01f);
        label.setPadding(dp(context, 16), dp(context, 24), dp(context, 16), dp(context, 8));
        parent.addView(label);
    }

    /** Section label plus the container its rows go into. */
    private static LinearLayout addSection(Context context, LinearLayout parent, String text) {
        addSectionLabel(context, parent, text);
        LinearLayout group = new LinearLayout(context);
        group.setOrientation(LinearLayout.VERTICAL);
        parent.addView(group);
        return group;
    }

    /**
     * Give a finished group its connected shape: round on the outside, nearly
     * square where rows meet, with a hairline gap between them.
     */
    private static void sealGroup(Context context, LinearLayout group) {
        int count = group.getChildCount();
        for (int i = 0; i < count; i++) {
            View row = group.getChildAt(i);
            float top = i == 0 ? CORNER_L : CORNER_JOINT;
            float bottom = i == count - 1 ? CORNER_L : CORNER_JOINT;
            row.setBackground(ripple(RIPPLE,
                    roundedRect(SURFACE_CONTAINER, context, top, top, bottom, bottom)));
            if (i < count - 1) {
                ViewGroup.LayoutParams lp = row.getLayoutParams();
                if (lp instanceof LinearLayout.LayoutParams) {
                    ((LinearLayout.LayoutParams) lp).bottomMargin = dp(context, ROW_GAP_DP);
                    row.setLayoutParams(lp);
                }
            }
        }
    }

    /** The shared skeleton of a list row: label, supporting text, trailing control. */
    private static LinearLayout makeRow(Context context, LinearLayout parent, String label, String support) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 16));
        row.setMinimumHeight(dp(context, 64));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        labelView.setTextColor(ON_SURFACE);
        texts.addView(labelView);

        if (support != null) {
            TextView supportView = new TextView(context);
            supportView.setText(support);
            supportView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            supportView.setTextColor(ON_SURFACE_VARIANT);
            supportView.setLineSpacing(0f, 1.1f);
            supportView.setPadding(0, dp(context, 2), 0, 0);
            texts.addView(supportView);
        }

        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private static void addRow(Context context, LinearLayout parent, String label, String key, boolean value) {
        addRow(context, parent, label, key, value, null);
    }

    /**
     * @param onChanged run after the preference is written, for rows another
     *                  section depends on (the nav icons drive the landing picker)
     */
    private static void addRow(Context context, LinearLayout parent, String label, String key,
                               boolean value, Runnable onChanged) {
        LinearLayout row = makeRow(context, parent, label, supportText(key));

        Switch toggle = new Switch(context);
        toggle.setChecked(value);
        toggle.setShowText(false);
        // The platform track drawable is alpha-blended, so tinting it white gives
        // the grey M3 track; the thumb is what carries the accent.
        toggle.setTrackTintList(buildStateList(PRIMARY, OUTLINE_VARIANT));
        toggle.setThumbTintList(buildStateList(PRIMARY, OUTLINE));
        toggle.setOnCheckedChangeListener((btn, isChecked) -> {
            // Hardcore: a frozen surface cannot be revealed; snap it back to hidden.
            if (Config.isHardcoreMode() && Config.isLockable(key)
                    && !Config.hidesSurface(key, isChecked) && Config.wasHiddenAtBaseline(key)) {
                btn.setChecked(!isChecked);
                return;
            }
            Config.setBlocked(key, isChecked);
            Config.setNeedsRestart();
            if (onChanged != null) onChanged.run();
        });
        row.addView(toggle);
        row.setOnClickListener(v -> {
            if (toggle.isEnabled()) toggle.toggle();
        });

        // Freeze rows whose surface is already hidden under the permanent lock.
        if (Config.isLockable(key) && Config.isHardcoreMode() && Config.hidesSurface(key, value)) {
            toggle.setEnabled(false);
            row.setEnabled(false);
            row.setClickable(false);
            row.setAlpha(0.38f);
        }
    }

    private static String supportText(String key) {
        if (key.equals("auto_update")) return "Check GitHub for a new version on launch.";
        if (key.equals("block_ads")) return "Block sponsored ads across Instagram.";
        if (key.equals("limit_following_feed")) return "Show only accounts you follow (needs the feed unblocked).";
        if (key.equals("hide_toasts")) return "Hide every Instagram popup, including “couldn’t refresh feed”.";
        if (key.equals("force_sdr")) return "Keep blacks deep by stopping Instagram forcing HDR on the UI.";
        if (key.equals("block_friends_lane")) return "Hide the Friends tab and its avatars in the Reels header.";
        if (key.equals("block_notifications")) return "Hide the notifications (heart) button in the feed header.";
        if (key.startsWith("nav_show_")) return "Show this icon in the navigation bar.";
        return "Hide this surface in Instagram.";
    }

    // --- Landing page picker --------------------------------------------------

    private static final String[] LANDING_VALUES = {"home", "search", "direct", "profile"};
    private static final String[] LANDING_LABELS = {"Home feed", "Search", "Direct messages", "Profile"};

    /**
     * @param landingSync out-parameter: receives a runnable that re-checks which
     *                    options are still selectable, for the nav rows to call
     *                    when they hide or show a tab
     */
    private static void buildLandingOptions(Context context, LinearLayout group, Runnable[] landingSync) {
        final List<View> rows = new ArrayList<>();
        final List<RadioButton> marks = new ArrayList<>();

        for (int i = 0; i < LANDING_VALUES.length; i++) {
            final String value = LANDING_VALUES[i];
            LinearLayout row = makeRow(context, group, LANDING_LABELS[i], null);

            RadioButton mark = new RadioButton(context);
            mark.setClickable(false);
            mark.setFocusable(false);
            mark.setButtonTintList(buildStateList(PRIMARY, OUTLINE));
            row.addView(mark);

            rows.add(row);
            marks.add(mark);

            row.setOnClickListener(v -> {
                if (!Config.isLandingAvailable(value)) return;
                Config.setLandingPage(value);
                Config.setNeedsRestart();
                if (landingSync[0] != null) landingSync[0].run();
            });
        }

        // A surface whose nav icon is hidden is not a landing page you can choose:
        // it would hand back exactly what was hidden, and under the permanent lock
        // the picker was the one way left to reach a frozen surface (issue #116).
        landingSync[0] = () -> {
            String current = Config.getLandingPage();
            for (int i = 0; i < LANDING_VALUES.length; i++) {
                boolean available = Config.isLandingAvailable(LANDING_VALUES[i]);
                View row = rows.get(i);
                row.setEnabled(available);
                row.setAlpha(available ? 1f : 0.38f);
                marks.get(i).setChecked(LANDING_VALUES[i].equals(current));
            }
        };
        landingSync[0].run();
    }

    // --- Type, shape and control helpers --------------------------------------

    /** The page headline: large, tight-tracked, medium weight. */
    public static void headline(TextView view) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f);
        view.setTextColor(ON_SURFACE);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setLetterSpacing(-0.02f);
    }

    /** A card or dialog title. */
    public static void titleLarge(TextView view) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        view.setTextColor(ON_SURFACE);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setLetterSpacing(-0.01f);
    }

    /** Supporting text. */
    public static void body(TextView view) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        view.setTextColor(ON_SURFACE_VARIANT);
        view.setLineSpacing(0f, 1.15f);
    }

    public static int statusBarHeight(Context context) {
        int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id == 0 ? 0 : context.getResources().getDimensionPixelSize(id);
    }

    public static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static GradientDrawable roundedRect(int color, float radiusDp, Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    /** Per-corner variant, for the connected list groups. */
    public static GradientDrawable roundedRect(int color, Context context,
                                               float topLeftDp, float topRightDp,
                                               float bottomRightDp, float bottomLeftDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        float tl = dp(context, topLeftDp);
        float tr = dp(context, topRightDp);
        float br = dp(context, bottomRightDp);
        float bl = dp(context, bottomLeftDp);
        drawable.setCornerRadii(new float[]{tl, tl, tr, tr, br, br, bl, bl});
        return drawable;
    }

    /** A transparent pill with a hairline border, for low-emphasis buttons. */
    public static Drawable outlined(Context context, int strokeColor) {
        GradientDrawable shape = roundedRect(Color.TRANSPARENT, CORNER_FULL, context);
        shape.setStroke(dp(context, 1), strokeColor);
        return ripple(RIPPLE, shape);
    }

    public static Drawable ripple(int color, Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(color), content, null);
    }

    public static ColorStateList buildStateList(int checkedColor, int uncheckedColor) {
        int[][] states = {
                new int[]{android.R.attr.state_checked},
                new int[]{},
        };
        int[] colors = {checkedColor, uncheckedColor};
        return new ColorStateList(states, colors);
    }

    /** A pill button. Expressive metrics: 52dp tall, generous horizontal padding. */
    public static Button makeButton(Context context, String text, int bgColor, int textColor, boolean filled) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(textColor);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setLetterSpacing(0f);
        button.setMinHeight(dp(context, 52));
        button.setMinimumHeight(dp(context, 52));
        button.setPadding(dp(context, 20), 0, dp(context, 20), 0);
        button.setStateListAnimator(null);
        button.setBackground(filled
                ? ripple(RIPPLE, roundedRect(bgColor, CORNER_FULL, context))
                : ripple(RIPPLE, roundedRect(Color.TRANSPARENT, CORNER_FULL, context)));
        return button;
    }

    public static View makeDivider(Context context, int color) {
        View divider = new View(context);
        divider.setBackgroundColor(color);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)));
        return divider;
    }
}

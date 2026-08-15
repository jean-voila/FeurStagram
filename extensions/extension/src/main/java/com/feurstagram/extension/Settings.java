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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Feurstagram settings, shown as a full-screen dark dialog when the Home tab is
 * long-pressed. Builds its UI entirely in code (no bundled resources) and
 * persists every change through {@link Config}.
 */
public final class Settings {

    // Material-3 dark palette (ARGB). Shared with other Feurstagram dialogs.
    public static final int SURFACE = 0xFF1C1B1F;
    public static final int SURFACE_CONTAINER = 0xFF2B2A30;
    public static final int ON_SURFACE = 0xFFE6E1E5;
    public static final int ON_SURFACE_VARIANT = 0xFFCAC4D0;
    public static final int OUTLINE = 0xFF938F99;
    public static final int DIVIDER = 0xFF36353B;
    public static final int PRIMARY = 0xFFD0BCFF;
    public static final int ON_PRIMARY = 0xFF371E73;
    public static final int ERROR = 0xFFF2B8B5;
    public static final int ON_ERROR = 0xFF601410;
    public static final int RIPPLE = 0x33FFFFFF;

    private Settings() {}

    /**
     * Install the Feurstagram entry points off the bottom tab bar: the settings
     * button in the feed's top action bar, the UI hiders, and the launch update
     * check. The tab bar is just a stable handle into the window's view tree.
     */
    public static void installHomeTabWatcher(ViewGroup tabBar) {
        if (tabBar == null) return;
        // Settings open on a long-press of the Home tab. (An injected action-bar
        // button was tried but failed to appear on many devices, so it was
        // dropped in favour of this tab-bar-only entry point.)
        tabBar.getViewTreeObserver().addOnGlobalLayoutListener(new HomeTabWatcher(tabBar));
        Hiders.installAll(tabBar);
        UpdateChecker.check(getActivityContext(tabBar));
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
            // Non-floating full-screen dark theme so the system bars can be tinted.
            Dialog dialog = new Dialog(context, android.R.style.Theme_Material_NoActionBar);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setContentView(buildContent(context, dialog));

            // Intercept Back (incl. predictive-back gesture) to restart on change.
            dialog.setOnCancelListener(d -> {
                if (Config.isRestartPending()) {
                    CacheCleaner.clearAndRestart(context);
                }
            });

            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(SURFACE));
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                window.setDimAmount(0f);
                window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                        | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                window.setStatusBarColor(SURFACE);
                window.setNavigationBarColor(SURFACE);
            }
            dialog.show();
        } catch (Throwable t) {
            Toast.makeText(context, "Feurstagram settings unavailable here", Toast.LENGTH_LONG).show();
        }
    }

    private static View buildContent(Context context, Dialog dialog) {
        // Snapshot block state so the permanent lock freezes only what's blocked now.
        Config.captureBaseline();
        boolean hardcore = Config.isHardcoreMode();

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 24);
        int top = statusBarHeight(context) + dp(context, 24);
        root.setPadding(pad, top, pad, pad);

        ScrollView scroll = new ScrollView(context);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setText("Feurstagram");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        title.setTextColor(ON_SURFACE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        column.addView(title);

        TextView subtitle = new TextView(context);
        subtitle.setText(hardcore
                ? "Permanent lock on. You can tighten blocks but not loosen them. Reinstall to fully unlock."
                : "Choose what to hide. Tap Done to clear cache and restart.");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        subtitle.setTextColor(ON_SURFACE_VARIANT);
        subtitle.setPadding(0, dp(context, 4), 0, 0);
        column.addView(subtitle);

        addSectionHeader(context, column, "BLOCKED SURFACES");
        LinearLayout surfaces = makeSectionCard(context);
        column.addView(surfaces);
        addRow(context, surfaces, "Home Feed", "block_feed", Config.isFeedBlocked());
        addRow(context, surfaces, "Explore", "block_explore", Config.isExploreBlocked());
        addRow(context, surfaces, "Reels", "block_reels", Config.isReelsBlocked());
        addRow(context, surfaces, "Friends in Reels", "block_friends_lane", Config.isFriendsLaneBlocked());
        addRow(context, surfaces, "Stories", "block_stories", Config.isStoriesBlocked());
        addRow(context, surfaces, "Instants", "block_instants", Config.isInstantsBlocked());
        addRow(context, surfaces, "Notes", "block_notes", Config.isNotesBlocked());
        addRow(context, surfaces, "Suggested accounts", "block_suggested", Config.isSuggestedBlocked());
        addRow(context, surfaces, "Ads", "block_ads", Config.isAdsBlocked());
        addRow(context, surfaces, "Notifications button", "block_notifications", Config.isNotificationsButtonBlocked());

        addSectionHeader(context, column, "NAVIGATION BAR");
        LinearLayout nav = makeSectionCard(context);
        column.addView(nav);
        addRow(context, nav, "Search", "nav_show_search", Config.getBlocked("nav_show_search", true));
        addRow(context, nav, "Reels", "nav_show_reels", Config.getBlocked("nav_show_reels", false));
        addRow(context, nav, "Create", "nav_show_create", Config.getBlocked("nav_show_create", true));
        addRow(context, nav, "Messages", "nav_show_direct", Config.getBlocked("nav_show_direct", true));
        addRow(context, nav, "Profile", "nav_show_profile", Config.getBlocked("nav_show_profile", true));

        addSectionHeader(context, column, "FEED");
        LinearLayout feed = makeSectionCard(context);
        column.addView(feed);
        addRow(context, feed, "Following feed only", "limit_following_feed", Config.isFollowingFeedOnly());

        addSectionHeader(context, column, "POPUPS");
        LinearLayout popups = makeSectionCard(context);
        column.addView(popups);
        addRow(context, popups, "Instagram popups", "hide_toasts", Config.arePopupsHidden());

        addSectionHeader(context, column, "LANDING PAGE");
        column.addView(buildLandingCard(context));

        addSectionHeader(context, column, "UPDATES");
        LinearLayout updates = makeSectionCard(context);
        column.addView(updates);
        addRow(context, updates, "Automatic update check", "auto_update", Config.isAutoUpdateEnabled());

        Button checkUpdate = makeButton(context, "Check for updates", SURFACE_CONTAINER, ON_SURFACE, true);
        checkUpdate.setOnClickListener(v -> UpdateChecker.checkNow(context));
        LinearLayout.LayoutParams checkLp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        checkLp.setMargins(0, dp(context, 12), 0, 0);
        column.addView(checkUpdate, checkLp);

        // GitHub Sponsors: a calm, neutral button (not the flashy Sponsors pink).
        Button sponsors = makeButton(context, "Donate on Github Sponsors", SURFACE_CONTAINER, ON_SURFACE, true);
        sponsors.setOnClickListener(v -> openUrl(context, "https://github.com/sponsors/jean-voila"));
        LinearLayout.LayoutParams sponsorsLp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sponsorsLp.setMargins(0, dp(context, 20), 0, 0);
        column.addView(sponsors, sponsorsLp);

        // Buy Me a Coffee: brand yellow (#FFDD00) with black text, coffee-cup glyph.
        Button coffee = makeButton(context, "☕  Buy me a coffee!",
                Color.parseColor("#FFDD00"), Color.parseColor("#000000"), true);
        coffee.setOnClickListener(v -> openUrl(context, "https://buymeacoffee.com/jean_voila"));
        LinearLayout.LayoutParams coffeeLp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        coffeeLp.setMargins(0, dp(context, 12), 0, 0);
        column.addView(coffee, coffeeLp);

        scroll.addView(column);
        LinearLayout.LayoutParams scrollLp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollLp);

        // Pinned bottom action bar (always visible).
        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams actionsLp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.setMargins(0, dp(context, 16), 0, 0);
        root.addView(actions, actionsLp);

        Button lock = makeButton(context, "Permanent lock", 0xFF4F3787, 0xFFEADDFF, true);
        lock.setOnClickListener(v -> {
            dialog.dismiss();
            confirmHardcore(context);
        });
        if (hardcore) {
            lock.setEnabled(false);
            lock.setAlpha(0.6f);
        }
        actions.addView(lock);

        View spacer = new View(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(dp(context, 8), 1));
        actions.addView(spacer);

        Button done = makeButton(context, "Done", PRIMARY, ON_PRIMARY, true);
        done.setOnClickListener(v -> {
            dialog.dismiss();
            onDone(context);
        });
        actions.addView(done);

        return root;
    }

    private static void onDone(Context context) {
        if (context == null) return;
        // A pending change restarts straight away (no dialog to back out of).
        if (Config.isRestartPending()) {
            CacheCleaner.clearAndRestart(context);
            return;
        }
        try {
            Dialog dialog = new Dialog(context);
            dialog.setContentView(confirmContent(context, dialog,
                    "Restart Instagram?",
                    "Feurstagram will clear cache and restart the app to apply your changes.",
                    "Restart", PRIMARY, ON_PRIMARY,
                    () -> CacheCleaner.clearAndRestart(context)));
            dialog.setCanceledOnTouchOutside(true);
            styleConfirmWindow(dialog);
            dialog.show();
        } catch (Throwable t) {
            Toast.makeText(context, "Unable to open restart confirmation", Toast.LENGTH_LONG).show();
        }
    }

    private static void confirmHardcore(Context context) {
        if (context == null) return;
        if (Config.isHardcoreMode()) {
            Toast.makeText(context, "Permanent lock is already enabled", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Dialog dialog = new Dialog(context);
            dialog.setContentView(confirmContent(context, dialog,
                    "Enable Permanent lock?",
                    "This is permanent for this installation. You will no longer be able to "
                            + "re-enable Home Feed, Explore, Reels, or Stories without reinstalling the app.",
                    "Enable", ERROR, ON_ERROR,
                    () -> {
                        Config.enableHardcoreMode();
                        Toast.makeText(context,
                                "Permanent lock enabled. Reinstall app to unlock content.",
                                Toast.LENGTH_LONG).show();
                    }));
            dialog.setCanceledOnTouchOutside(true);
            styleConfirmWindow(dialog);
            dialog.show();
        } catch (Throwable t) {
            Toast.makeText(context, "Unable to open Permanent lock confirmation", Toast.LENGTH_LONG).show();
        }
    }

    private static void styleConfirmWindow(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(0));
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        window.setDimAmount(0.6f);
    }

    private static View confirmContent(Context context, Dialog dialog, String titleText,
                                       String bodyText, String confirmText, int confirmBg,
                                       int confirmFg, Runnable onConfirm) {
        FrameLayout frame = new FrameLayout(context);
        int pad = dp(context, 24);
        frame.setPadding(pad, pad, pad, pad);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedRect(SURFACE, 28, context));
        card.setPadding(pad, pad, pad, pad);
        frame.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(context);
        title.setText(titleText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        title.setTextColor(ON_SURFACE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(title);

        TextView body = new TextView(context);
        body.setText(bodyText);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        body.setTextColor(ON_SURFACE_VARIANT);
        body.setPadding(0, dp(context, 12), 0, 0);
        card.addView(body);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        LinearLayout.LayoutParams buttonsLp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonsLp.setMargins(0, dp(context, 24), 0, 0);
        card.addView(buttons, buttonsLp);

        Button cancel = makeButton(context, "Cancel", 0, ON_SURFACE_VARIANT, false);
        cancel.setOnClickListener(v -> dialog.dismiss());
        buttons.addView(cancel);

        View spacer = new View(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(dp(context, 8), 1));
        buttons.addView(spacer);

        Button confirm = makeButton(context, confirmText, confirmBg, confirmFg, true);
        confirm.setOnClickListener(v -> {
            dialog.dismiss();
            onConfirm.run();
        });
        buttons.addView(confirm);

        return frame;
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

    private static void addSectionHeader(Context context, LinearLayout parent, String text) {
        TextView header = new TextView(context);
        header.setText(text);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        header.setTextColor(ON_SURFACE_VARIANT);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.setPadding(0, dp(context, 20), 0, dp(context, 10));
        parent.addView(header);
    }

    private static LinearLayout makeSectionCard(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedRect(SURFACE_CONTAINER, 20, context));
        card.setPadding(0, dp(context, 4), 0, dp(context, 4));
        return card;
    }

    private static View buildLandingCard(Context context) {
        LinearLayout card = makeSectionCard(context);
        RadioGroup group = new RadioGroup(context);
        group.setOrientation(RadioGroup.VERTICAL);
        group.setPadding(dp(context, 8), 0, dp(context, 8), 0);

        String current = Config.getLandingPage();
        addLandingOption(context, group, "Home feed", 1, current.equals("home"));
        addLandingOption(context, group, "Search", 2, current.equals("search"));
        addLandingOption(context, group, "Direct messages", 3, current.equals("direct"));
        addLandingOption(context, group, "Profile", 4, current.equals("profile"));

        group.setOnCheckedChangeListener((g, checkedId) -> {
            String value;
            switch (checkedId) {
                case 1: value = "home"; break;
                case 2: value = "search"; break;
                case 3: value = "direct"; break;
                default: value = "profile"; break;
            }
            Config.setLandingPage(value);
            Config.setNeedsRestart();
        });

        card.addView(group);
        return card;
    }

    private static void addLandingOption(Context context, RadioGroup group, String label, int id, boolean checked) {
        RadioButton option = new RadioButton(context);
        option.setText(label);
        option.setId(id);
        option.setChecked(checked);
        option.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        option.setTextColor(ON_SURFACE);
        option.setPadding(dp(context, 16), dp(context, 12), 0, dp(context, 12));
        option.setMinimumHeight(dp(context, 56));
        option.setButtonTintList(buildStateList(PRIMARY, OUTLINE));
        group.addView(option, new RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static void addRow(Context context, LinearLayout parent, String label, String key, boolean value) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 20), dp(context, 14), dp(context, 20), dp(context, 14));
        row.setMinimumHeight(dp(context, 64));
        row.setBackground(ripple(RIPPLE, roundedRect(SURFACE_CONTAINER, 16, context)));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        labelView.setTextColor(ON_SURFACE);
        labelView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        texts.addView(labelView);

        TextView sub = new TextView(context);
        if (key.equals("auto_update")) {
            sub.setText("Check GitHub for a new version on launch.");
        } else if (key.equals("block_ads")) {
            sub.setText("Block sponsored ads across Instagram.");
        } else if (key.equals("limit_following_feed")) {
            sub.setText("Show only accounts you follow (needs the feed unblocked).");
        } else if (key.equals("hide_toasts")) {
            sub.setText("Hide every Instagram popup, including “couldn’t refresh feed”.");
        } else if (key.equals("block_friends_lane")) {
            sub.setText("Hide the Friends tab and its avatars in the Reels header.");
        } else if (key.equals("block_notifications")) {
            sub.setText("Hide the notifications (heart) button in the feed header.");
        } else if (key.startsWith("nav_show_")) {
            sub.setText("Show this icon in the navigation bar.");
        } else {
            sub.setText("Hide this surface in Instagram.");
        }
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        sub.setTextColor(ON_SURFACE_VARIANT);
        texts.addView(sub);

        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(context);
        toggle.setChecked(value);
        toggle.setShowText(false);
        toggle.setTrackTintList(buildStateList(PRIMARY, OUTLINE));
        toggle.setThumbTintList(buildStateList(ON_PRIMARY, OUTLINE));
        toggle.setOnCheckedChangeListener((btn, isChecked) -> {
            // Hardcore: a frozen surface cannot be revealed; snap it back to hidden.
            if (Config.isHardcoreMode() && Config.isLockable(key)
                    && !Config.hidesSurface(key, isChecked) && Config.wasHiddenAtBaseline(key)) {
                btn.setChecked(!isChecked);
                return;
            }
            Config.setBlocked(key, isChecked);
            Config.setNeedsRestart();
        });

        // Freeze rows whose surface is already hidden under the permanent lock.
        if (Config.isLockable(key) && Config.isHardcoreMode() && Config.hidesSurface(key, value)) {
            toggle.setEnabled(false);
            row.setAlpha(0.38f);
        } else {
            row.setAlpha(1f);
        }

        row.addView(toggle);
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(makeDivider(context, DIVIDER));
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

    public static Button makeButton(Context context, String text, int bgColor, int textColor, boolean filled) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(textColor);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinimumHeight(dp(context, 40));
        button.setPadding(dp(context, 24), 0, dp(context, 24), 0);
        Drawable background = filled
                ? ripple(RIPPLE, roundedRect(bgColor, 20, context))
                : ripple(RIPPLE, roundedRect(0, 20, context));
        button.setBackground(background);
        return button;
    }

    public static View makeDivider(Context context, int color) {
        View divider = new View(context);
        divider.setBackgroundColor(color);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1));
        lp.setMargins(dp(context, 20), 0, dp(context, 20), 0);
        divider.setLayoutParams(lp);
        return divider;
    }
}

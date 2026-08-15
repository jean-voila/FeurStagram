package com.feurstagram.extension;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;

/**
 * Runtime configuration backed by SharedPreferences ("feurstagram_prefs").
 *
 * Independent block toggles (feed, explore, reels, stories, instants, notes,
 * suggested, ads) plus the permanent "hardcore" lock. The application Context
 * is resolved reflectively so no patched class needs to hand us one.
 */
public final class Config {

    private static final String PREFS = "feurstagram_prefs";

    /**
     * Set once any setting changes in this process. While true, leaving the
     * settings page forces a clean restart instead of returning to a stale UI.
     * Cleared implicitly on every process start.
     */
    private static boolean sNeedsRestart;

    /**
     * Snapshot of every block_* toggle captured when the settings page opens.
     * The permanent lock only freezes surfaces that were already blocked at this
     * snapshot, so a mis-toggle made during the current session can still be
     * undone until Done restarts the app. Null until captureBaseline() runs.
     */
    private static HashMap<String, Boolean> sBaseline;

    private Config() {}

    /** Resolve the process Application context via ActivityThread, or null. */
    public static Context getAppContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object app = activityThread.getMethod("currentApplication").invoke(null);
            return (Context) app;
        } catch (Throwable t) {
            return null;
        }
    }

    private static SharedPreferences prefs() {
        Context context = getAppContext();
        return context == null ? null : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean getBlocked(String key, boolean defaultValue) {
        SharedPreferences prefs = prefs();
        return prefs == null ? defaultValue : prefs.getBoolean(key, defaultValue);
    }

    public static void setBlocked(String key, boolean value) {
        // Hardcore lock only forbids *relaxing* a block (turning it off). Turning
        // a block on is always allowed so users can still tighten. A surface may
        // be turned off only when it was already unblocked at the start of the
        // current settings session (undo of a mis-toggle); a surface blocked at
        // session open stays frozen until a reinstall.
        if (isHardcoreMode()
                && key != null
                && key.startsWith("block_")
                && !value
                && isBaselineBlocked(key)) {
            return;
        }
        SharedPreferences prefs = prefs();
        if (prefs == null) return;
        prefs.edit().putBoolean(key, value).apply();
    }

    public static boolean isHardcoreMode() {
        return getBlocked("hardcore_mode", false);
    }

    public static void enableHardcoreMode() {
        SharedPreferences prefs = prefs();
        if (prefs == null) return;
        prefs.edit().putBoolean("hardcore_mode", true).apply();
    }

    /** Whether to check GitHub for a newer release on launch. On by default. */
    public static boolean isAutoUpdateEnabled() {
        return getBlocked("auto_update", true);
    }

    /**
     * Whether the home feed is restricted to accounts you follow (chronological
     * "Following" feed) instead of the recommended feed. Off by default. Not a
     * block_* surface, so the permanent lock never freezes it.
     */
    public static boolean isFollowingFeedOnly() {
        return getBlocked("limit_following_feed", false);
    }

    public static boolean isFeedBlocked()      { return getBlocked("block_feed", true); }
    public static boolean isExploreBlocked()   { return getBlocked("block_explore", true); }
    public static boolean isReelsBlocked()     { return getBlocked("block_reels", true); }
    public static boolean isStoriesBlocked()   { return getBlocked("block_stories", false); }
    public static boolean isInstantsBlocked()  { return getBlocked("block_instants", true); }
    public static boolean isNotesBlocked()     { return getBlocked("block_notes", true); }
    public static boolean isSuggestedBlocked() { return getBlocked("block_suggested", true); }
    public static boolean isAdsBlocked()       { return getBlocked("block_ads", true); }

    /**
     * Whether the notifications ("heart") button in the feed header is hidden.
     * Off by default: it's an opt-in distraction cut, and people expect their
     * notifications entry-point to still be there after an update.
     */
    public static boolean isNotificationsButtonBlocked() { return getBlocked("block_notifications", false); }

    /**
     * Bottom-navigation icon visibility, stored per tab as {@code nav_show_<tab>}
     * (true = shown). Independent of the content blocks, so hiding the Reels
     * *icon* is decoupled from blocking Reels *content*. Home is deliberately not
     * configurable: long-pressing it is the only Settings entry point, so it must
     * stay present and tappable. Reels defaults to hidden to preserve the previous
     * behaviour (blocking reels used to also hide the tab).
     */
    public static boolean isReelsTabShown() { return getBlocked("nav_show_reels", false); }

    /**
     * Whether the first-run coach mark (long-press Home to open settings) has
     * already been shown on this installation. Set once, never cleared: the guide
     * is a one-time thing, not a setting.
     */
    public static boolean isOnboardingDone() { return getBlocked("onboarding_done", false); }

    public static void setOnboardingDone() { setBlocked("onboarding_done", true); }

    /** Snapshot the current value of every block_* toggle for the permanent lock. */
    public static void captureBaseline() {
        HashMap<String, Boolean> baseline = new HashMap<>();
        baseline.put("block_feed", isFeedBlocked());
        baseline.put("block_explore", isExploreBlocked());
        baseline.put("block_reels", isReelsBlocked());
        baseline.put("block_stories", isStoriesBlocked());
        baseline.put("block_instants", isInstantsBlocked());
        baseline.put("block_notes", isNotesBlocked());
        baseline.put("block_suggested", isSuggestedBlocked());
        baseline.put("block_ads", isAdsBlocked());
        sBaseline = baseline;
    }

    /**
     * True if the given block_* key was already blocked at the start of the
     * current settings session. Falls back to the live persisted value when no
     * baseline was captured (calls outside the settings page).
     */
    public static boolean isBaselineBlocked(String key) {
        if (sBaseline == null) return getBlocked(key, false);
        Boolean value = sBaseline.get(key);
        return value != null && value;
    }

    /**
     * The surface the app should jump to on cold start: one of "home" (default),
     * "search", "direct", "profile".
     */
    public static String getLandingPage() {
        SharedPreferences prefs = prefs();
        return prefs == null ? "home" : prefs.getString("landing_page", "home");
    }

    public static void setLandingPage(String value) {
        SharedPreferences prefs = prefs();
        if (prefs == null) return;
        prefs.edit().putString("landing_page", value).apply();
    }

    public static void setNeedsRestart() { sNeedsRestart = true; }

    public static boolean isRestartPending() { return sNeedsRestart; }
}

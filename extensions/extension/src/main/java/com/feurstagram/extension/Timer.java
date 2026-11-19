package com.feurstagram.extension;

import java.util.Calendar;

/**
 * The unlock timer: an allowance of time (per day or per week) during which the
 * surfaces picked for it work again, used in sessions the user starts, pauses,
 * resumes or skips.
 *
 * <h2>State</h2>
 * Everything is a preference, so it survives restarts and works on the wall clock
 * whether the app is open or not:
 * <ul>
 *   <li>{@code timer_enabled}, {@code timer_unlock_<block key>}, {@code timer_quota_min},
 *   {@code timer_refill} ("day" or "week"): the configuration.</li>
 *   <li>{@code timer_state}: idle, running, paused or skipped; {@code timer_used}: the
 *   allowance spent in this period before the current run; {@code timer_since}: when
 *   the current run started; {@code timer_period}: which day or week the allowance
 *   belongs to.</li>
 * </ul>
 * A new day (or week) refills the allowance once no session is running. Skipping a
 * session gives up what was left of it until the next refill.
 *
 * <h2>Applying it</h2>
 * The block getters of {@link Config} ask {@link #unlocks} for each surface, so a
 * running session reads as "not blocked" everywhere at once. Instagram keeps what it
 * loaded, so every change of state (start, pause, resume, skip, the end of the
 * allowance) clears the cache and restarts the app, like Done in the settings.
 *
 * <h2>Under the permanent lock</h2>
 * The timer set up before the lock keeps working, but can only be narrowed: fewer
 * surfaces, less time, a slower refill, or off. Never widened.
 */
public final class Timer {

    private Timer() {}

    public static final String IDLE = "idle";
    public static final String RUNNING = "running";
    public static final String PAUSED = "paused";
    public static final String SKIPPED = "skipped";

    public static final String DAY = "day";
    public static final String WEEK = "week";

    static final String UNLOCK_PREFIX = "timer_unlock_";
    static final int DEFAULT_QUOTA_MIN = 60;

    // --- Configuration -------------------------------------------------------------

    public static boolean isEnabled() {
        return Config.getBlocked("timer_enabled", false);
    }

    static boolean isPicked(String blockKey) {
        return Config.getBlocked(UNLOCK_PREFIX + blockKey, false);
    }

    public static int quotaMinutes() {
        long value = Config.getLong("timer_quota_min", DEFAULT_QUOTA_MIN);
        return (int) Math.max(1, Math.min(7 * 24 * 60, value));
    }

    public static String refill() {
        return WEEK.equals(Config.getString("timer_refill", DAY)) ? WEEK : DAY;
    }

    /** Write a timer setting; false when the permanent lock refuses a widening. */
    static boolean setEnabled(boolean on) {
        if (on && Config.isHardcoreMode() && !isEnabled()) return false;
        Config.putBoolean("timer_enabled", on);
        return true;
    }

    static boolean setPicked(String blockKey, boolean on) {
        if (on && Config.isHardcoreMode() && !isPicked(blockKey)) return false;
        Config.putBoolean(UNLOCK_PREFIX + blockKey, on);
        return true;
    }

    static boolean setQuotaMinutes(int minutes) {
        if (minutes <= 0) return false;
        if (Config.isHardcoreMode() && minutes > quotaMinutes()) return false;
        Config.putLong("timer_quota_min", minutes);
        return true;
    }

    static boolean setRefill(String value) {
        String next = WEEK.equals(value) ? WEEK : DAY;
        // A daily refill gives more time than a weekly one.
        if (Config.isHardcoreMode() && DAY.equals(next) && WEEK.equals(refill())) return false;
        Config.putString("timer_refill", next);
        return true;
    }

    // --- State -----------------------------------------------------------------------

    public static String state() {
        refreshPeriod();
        String state = Config.getString("timer_state", IDLE);
        return state == null ? IDLE : state;
    }

    /** Allowance left in this period, now. */
    public static long remainingMs() {
        refreshPeriod();
        long quota = quotaMinutes() * 60_000L;
        long used = Config.getLong("timer_used", 0L);
        if (RUNNING.equals(Config.getString("timer_state", IDLE))) {
            used += Math.max(0L, System.currentTimeMillis() - Config.getLong("timer_since", 0L));
        }
        return Math.max(0L, quota - used);
    }

    /** A session is under way, running or paused, with time left: the pill shows. */
    public static boolean hasSession() {
        if (!isEnabled()) return false;
        String state = state();
        return (RUNNING.equals(state) || PAUSED.equals(state)) && remainingMs() > 0;
    }

    public static boolean isRunning() {
        return isEnabled() && RUNNING.equals(state()) && remainingMs() > 0;
    }

    /** Whether a new session can start now. */
    public static boolean canStart() {
        return isEnabled() && IDLE.equals(state()) && remainingMs() > 0 && hasPickedSurface();
    }

    static boolean hasPickedSurface() {
        for (String key : Settings.SURFACE_KEYS) {
            if (isPicked(key) && Config.storedBlocked(key)) return true;
        }
        return false;
    }

    /**
     * Hook for {@link Config}'s block getters: true while a running session unlocks
     * this surface. Cheap: a few preference reads and the clock.
     */
    static boolean unlocks(String blockKey) {
        try {
            return isPicked(blockKey) && isRunning();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Start or resume a session. The caller restarts the app. */
    static boolean start() {
        String state = state();
        if (!isEnabled() || remainingMs() <= 0) return false;
        if (!IDLE.equals(state) && !PAUSED.equals(state)) return false;
        Config.putLong("timer_since", System.currentTimeMillis());
        Config.putString("timer_state", RUNNING);
        return true;
    }

    /** Pause the running session, keeping what is left. The caller restarts the app. */
    static boolean pause() {
        if (!RUNNING.equals(state())) return false;
        bank();
        Config.putString("timer_state", PAUSED);
        return true;
    }

    /** End the session and give up what was left until the next refill. */
    static boolean skip() {
        String state = state();
        if (!RUNNING.equals(state) && !PAUSED.equals(state)) return false;
        if (RUNNING.equals(state)) bank();
        Config.putString("timer_state", SKIPPED);
        return true;
    }

    /** The allowance ran out during a run. The caller restarts the app. */
    static boolean expireIfDone() {
        if (!RUNNING.equals(Config.getString("timer_state", IDLE)) || remainingMs() > 0) return false;
        Config.putLong("timer_used", quotaMinutes() * 60_000L);
        Config.putString("timer_state", IDLE);
        return true;
    }

    /** Move the time spent in the current run into "used". */
    private static void bank() {
        long used = Config.getLong("timer_used", 0L)
                + Math.max(0L, System.currentTimeMillis() - Config.getLong("timer_since", 0L));
        Config.putLong("timer_used", Math.min(used, quotaMinutes() * 60_000L));
        Config.putLong("timer_since", 0L);
    }

    /** A new day or week refills the allowance, once no session is running. */
    private static void refreshPeriod() {
        String current = periodId();
        String stored = Config.getString("timer_period", null);
        if (current.equals(stored)) return;
        if (RUNNING.equals(Config.getString("timer_state", IDLE))) return;
        Config.putString("timer_period", current);
        Config.putLong("timer_used", 0L);
        Config.putString("timer_state", IDLE);
    }

    private static String periodId() {
        Calendar now = Calendar.getInstance();
        if (WEEK.equals(refill())) {
            now.setFirstDayOfWeek(Calendar.MONDAY);
            now.setMinimalDaysInFirstWeek(4);
            return "w" + now.getWeekYear() + "-" + now.get(Calendar.WEEK_OF_YEAR);
        }
        return "d" + now.get(Calendar.YEAR) + "-" + now.get(Calendar.DAY_OF_YEAR);
    }

    /** "1 h 05 min", "45 min", "12:34" style durations. */
    public static String formatLong(long ms) {
        long minutes = (ms + 59_999L) / 60_000L;
        long hours = minutes / 60;
        minutes %= 60;
        if (hours == 0) return minutes + " min";
        return minutes == 0 ? hours + " h" : hours + " h " + (minutes < 10 ? "0" : "") + minutes;
    }

    /** "1:05:09" or "5:09", for the pill. */
    public static String formatClock(long ms) {
        long seconds = Math.max(0L, ms / 1000L);
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        String mm = (m < 10 ? "0" : "") + m;
        String ss = (s < 10 ? "0" : "") + s;
        return h > 0 ? h + ":" + mm + ":" + ss : m + ":" + ss;
    }
}

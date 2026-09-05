package com.feurstagram.extension;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One-time coach mark shown the first time the bottom tab bar appears — i.e. the
 * first launch in which the user is actually signed in to Instagram, since the
 * tab bar does not exist on the login screens.
 *
 * It dims the whole screen behind a scrim, punches a hole around the Home tab
 * and tells the user to long-press it, because that gesture is the only entry
 * point to the Feurstagram settings and is otherwise undiscoverable (people do
 * not read the README). Long-pressing inside the highlighted circle dismisses
 * the overlay and opens the settings straight away, so the lesson ends where it
 * was pointing. A discreet "Skip" escape hatch avoids trapping anyone.
 *
 * <h2>Waiting for a clear screen</h2>
 * Instagram runs its own gauntlet right after a login — "save your login info",
 * the swipe-navigation tip, whatever it adds next release — and any of it can sit
 * on top of the Home tab. Rather than guessing at those screens (which would
 * break the next time Instagram changes them), the overlay waits for evidence
 * that the Home tab is genuinely front-most, re-checked on a timer:
 *
 * <ul>
 *   <li>the tab is laid out and {@code isShown()};
 *   <li>its window has focus — any dialog or popup window on top takes it away;
 *   <li>it is (almost) entirely within its visible rect, so it is not clipped;
 *   <li>a hit-test at its centre through the whole window reaches the tab itself,
 *       which catches in-window sheets and scrims that steal no window focus.
 * </ul>
 *
 * All four must hold for several consecutive checks before the overlay appears,
 * and if something later pops <em>over</em> the overlay it steps aside and waits
 * again instead of arguing with Instagram over z-order.
 *
 * "Only once" is persisted in {@link Config} once the overlay has actually held
 * the screen (or the user has acted on it), so it never comes back.
 */
public final class Onboarding {

    /** How often the "is the Home tab really front-most?" check runs. */
    private static final long POLL_INTERVAL_MS = 400L;

    /** Consecutive good checks required before showing (~1.2 s of calm). */
    private static final int REQUIRED_STABLE_CHECKS = 3;

    /** Give up after this many checks (~60 s) and retry on the next launch. */
    private static final int MAX_ATTEMPTS = 150;

    /**
     * After this many checks (~30 s) the hit-test requirement is dropped, keeping
     * only the window-focus and visibility ones. It is the least predictable of
     * the checks, so it must never be the reason the guide is never shown.
     */
    private static final int RELAX_HIT_TEST_AFTER = 75;

    /** Once the overlay has held the screen this long, count it as delivered. */
    private static final long SETTLED_MS = 2500L;

    /** Fraction of the Home tab that must be unclipped for it to count as visible. */
    private static final float MIN_VISIBLE_FRACTION = 0.9f;

    /** Dimming applied to everything but the highlighted Home tab. */
    private static final int SCRIM = 0xE6000000;

    private static Poller active;

    private Onboarding() {}

    /**
     * Start watching for a good moment to show the coach mark, anchored on the
     * Home tab inside the given tab bar. Safe to call repeatedly: it is a no-op
     * once the guide has been delivered, or while a watch is already running.
     */
    public static void maybeShow(ViewGroup tabBar) {
        if (tabBar == null) return;
        if (Config.isOnboardingDone()) return;
        if (active != null && active.isRunning()) return;
        active = new Poller(tabBar);
        active.start();
    }

    /**
     * Waits for the Home tab to be genuinely front-most, then puts the overlay up.
     * Holds the tab <em>bar</em> rather than the tab itself and re-resolves the tab
     * on every check, so an Instagram screen that rebuilds the bottom navigation
     * does not leave us anchored to a detached view.
     */
    private static final class Poller implements Runnable {

        private final Handler handler = new Handler(Looper.getMainLooper());
        private ViewGroup tabBar;
        private int attempts;
        private int stableChecks;
        private boolean stopped;
        private boolean showing;

        Poller(ViewGroup tabBar) {
            this.tabBar = tabBar;
        }

        boolean isRunning() {
            return !stopped && tabBar != null && (showing || tabBar.isAttachedToWindow());
        }

        void start() {
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }

        private void stop() {
            stopped = true;
            tabBar = null;
            handler.removeCallbacksAndMessages(null);
        }

        @Override
        public void run() {
            try {
                if (stopped || showing) return;
                if (Config.isOnboardingDone()) {
                    stop();
                    return;
                }
                ViewGroup bar = tabBar;
                if (bar == null || !bar.isAttachedToWindow()) {
                    // The tab bar was torn down; a rebuild calls maybeShow() again.
                    stop();
                    return;
                }
                if (++attempts > MAX_ATTEMPTS) {
                    // Never delivered, never marked done: the next launch retries.
                    stop();
                    return;
                }

                View homeTab = findHomeTab(bar);
                if (homeTab != null && isUnobstructed(homeTab, attempts > RELAX_HIT_TEST_AFTER)) {
                    stableChecks++;
                } else {
                    stableChecks = 0;
                }

                if (stableChecks >= REQUIRED_STABLE_CHECKS && present(homeTab)) return;
                handler.postDelayed(this, POLL_INTERVAL_MS);
            } catch (Throwable ignored) {
                // A failing guide must never take Instagram down with it.
                stop();
            }
        }

        private View findHomeTab(ViewGroup bar) {
            Context context = bar.getContext();
            if (context == null) return null;
            int id = Hiders.resolveId(context, "feed_tab");
            if (id == 0) return null;
            View root = bar.getRootView();
            return root == null ? null : root.findViewById(id);
        }

        /** Build and show the overlay. Returns true once it is on screen. */
        private boolean present(View homeTab) {
            Context context = Settings.getActivityContext(homeTab);
            if (!(context instanceof Activity)) return false;
            Activity activity = (Activity) context;
            if (activity.isFinishing() || activity.isDestroyed()) return false;

            int[] location = new int[2];
            homeTab.getLocationOnScreen(location);
            float centerX = location[0] + homeTab.getWidth() / 2f;
            float centerY = location[1] + homeTab.getHeight() / 2f;
            float radius = Math.max(homeTab.getWidth(), homeTab.getHeight()) / 2f
                    + Settings.dp(context, 14);

            Dialog dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            SpotlightView spotlight = new SpotlightView(activity, centerX, centerY, radius);
            spotlight.onLongPressInside = () -> {
                deliver();
                dialog.dismiss();
                Settings.show(activity);
            };
            spotlight.onSkip = () -> {
                deliver();
                dialog.dismiss();
            };
            // Held the screen unbothered for a moment: the user has seen it.
            spotlight.onSettled = this::deliver;
            // Something (an Instagram popup, the notification shade, a task switch)
            // took the screen. Step aside and wait for the next calm moment rather
            // than leaving the guide stranded behind it.
            spotlight.onCovered = () -> {
                if (stopped) return;
                dialog.dismiss();
                showing = false;
                stableChecks = 0;
                handler.postDelayed(this, POLL_INTERVAL_MS);
            };
            dialog.setContentView(spotlight);
            dialog.setCanceledOnTouchOutside(false);
            // Back dismisses it (never trap the user) and counts as delivered.
            dialog.setOnCancelListener(d -> deliver());

            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                window.setDimAmount(0f);
                // The content view only paints its own bounds, which stop at the
                // system bars — so tint those with the same scrim, or they stay
                // bright and the screen is not really "all dimmed but the circle".
                window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                        | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                window.setStatusBarColor(SCRIM);
                window.setNavigationBarColor(SCRIM);
            }

            dialog.show();
            showing = true;
            return true;
        }

        private void deliver() {
            Config.setOnboardingDone();
            stop();
        }
    }

    /**
     * Whether the Home tab is laid out, on screen, in the focused window and not
     * covered by anything — the closest we can get to "Instagram is done throwing
     * popups at the user" without naming a single Instagram screen.
     *
     * @param skipHitTest drop the covered-by-a-sibling test, used as a late
     *                    fallback so an unexpected always-on-top view in Instagram's
     *                    own hierarchy cannot suppress the guide forever
     */
    private static boolean isUnobstructed(View homeTab, boolean skipHitTest) {
        if (!homeTab.isShown()) return false;
        int width = homeTab.getWidth();
        int height = homeTab.getHeight();
        if (width == 0 || height == 0) return false;
        // Any dialog or popup window on top of the activity takes window focus
        // away, which also covers the app being backgrounded entirely.
        if (!homeTab.hasWindowFocus()) return false;

        // Clipped away by a scrolling parent or a collapsing bar?
        Rect visible = new Rect();
        if (!homeTab.getGlobalVisibleRect(visible)) return false;
        long visibleArea = (long) visible.width() * visible.height();
        if (visibleArea < MIN_VISIBLE_FRACTION * width * height) return false;

        if (skipHitTest) return true;

        // Covered by a sibling drawn later — an in-window bottom sheet, a tooltip,
        // a full-screen fragment — none of which cost the window its focus.
        int[] location = new int[2];
        homeTab.getLocationOnScreen(location);
        int centerX = location[0] + width / 2;
        int centerY = location[1] + height / 2;
        View root = homeTab.getRootView();
        if (root == null) return true;
        View topmost = topmostAt(root, centerX, centerY);
        return topmost != null && isSelfOrDescendant(homeTab, topmost);
    }

    /**
     * The front-most view containing the given screen point that would actually
     * obstruct it. Children are walked back-to-front, which is the order they are
     * drawn in, so the first hit is the one the user would really touch.
     *
     * Empty, transparent containers are looked straight through: Instagram keeps
     * a full-screen {@code IgFrameLayout} host layer permanently above the tab bar
     * to mount its popups into, and treating that as an obstruction would suppress
     * the guide forever. It only counts as covering once something is mounted in
     * it — at which point that child is the hit instead.
     */
    private static View topmostAt(View view, int x, int y) {
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() < 0.01f) return null;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        if (x < location[0] || x >= location[0] + view.getWidth()
                || y < location[1] || y >= location[1] + view.getHeight()) {
            return null;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View hit = topmostAt(group.getChildAt(i), x, y);
                if (hit != null) return hit;
            }
        }
        return obstructs(view) ? view : null;
    }

    /**
     * Whether a view with nothing of ours under it at that point really blocks it.
     * A leaf draws something by definition; a container only counts when it paints
     * (an opaque scrim) or takes touches (a modal catcher).
     */
    private static boolean obstructs(View view) {
        if (!(view instanceof ViewGroup)) return true;
        if (view.isClickable() || view.isLongClickable()) return true;
        Drawable background = view.getBackground();
        return background != null && background.getAlpha() > 0;
    }

    /** True if {@code candidate} is the Home tab itself or something inside it. */
    private static boolean isSelfOrDescendant(View homeTab, View candidate) {
        View cursor = candidate;
        while (cursor != null) {
            if (cursor == homeTab) return true;
            if (!(cursor.getParent() instanceof View)) return false;
            cursor = (View) cursor.getParent();
        }
        return false;
    }

    /**
     * Full-screen scrim with a transparent circular cut-out over the Home tab, a
     * pulsing highlight ring, an instruction card above it and a Skip affordance.
     * Consumes every touch so nothing behind it can be tapped; a long-press inside
     * the circle is the one gesture that goes anywhere.
     */
    private static final class SpotlightView extends FrameLayout {

        private static final long PULSE_PERIOD_MS = 1600L;

        private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint holePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final float screenCenterX;
        private final float screenCenterY;
        private final float radius;
        private final float pulseSpread;
        private final long startedAt = SystemClock.uptimeMillis();
        private final int[] selfLocation = new int[2];

        private final View card;
        private final TextView arrow;
        private final TextView skip;

        Runnable onLongPressInside;
        Runnable onSkip;
        /** Fired once the overlay has held the focused screen long enough to count. */
        Runnable onSettled;
        /** Fired when something else takes the screen while the overlay is up. */
        Runnable onCovered;

        private final Runnable settleRunnable = () -> {
            if (onSettled != null) onSettled.run();
        };
        private boolean everFocused;
        private final int touchSlop;
        private final Runnable longPressRunnable = () -> {
            longPressFired = true;
            if (onLongPressInside != null) onLongPressInside.run();
        };
        private float downX;
        private float downY;
        private boolean longPressArmed;
        private boolean longPressFired;

        SpotlightView(Context context, float screenCenterX, float screenCenterY, float radius) {
            super(context);
            this.screenCenterX = screenCenterX;
            this.screenCenterY = screenCenterY;
            this.radius = radius;
            this.pulseSpread = Settings.dp(context, 16);
            this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

            // PorterDuff.CLEAR needs an offscreen buffer; a software layer is the
            // reliable way to get one across the whole device range.
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            setWillNotDraw(false);

            scrimPaint.setColor(SCRIM);
            holePaint.setColor(Color.TRANSPARENT);
            holePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(Settings.dp(context, 2));
            ringPaint.setColor(Settings.PRIMARY);
            pulsePaint.setStyle(Paint.Style.STROKE);
            pulsePaint.setStrokeWidth(Settings.dp(context, 2));
            pulsePaint.setColor(Settings.PRIMARY);

            card = buildCard(context);
            addView(card, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

            arrow = new TextView(context);
            arrow.setText("▼");
            arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
            arrow.setTextColor(Settings.PRIMARY);
            addView(arrow, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

            skip = new TextView(context);
            skip.setText("Skip");
            skip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            skip.setTextColor(Settings.ON_SURFACE_VARIANT);
            skip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            skip.setBackground(Settings.outlined(context, Settings.OUTLINE_VARIANT));
            int skipPadX = Settings.dp(context, 20);
            int skipPadY = Settings.dp(context, 12);
            skip.setPadding(skipPadX, skipPadY, skipPadX, skipPadY);
            skip.setOnClickListener(v -> {
                if (onSkip != null) onSkip.run();
            });
            addView(skip, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }

        private static View buildCard(Context context) {
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setBackground(Settings.roundedRect(Settings.SURFACE_CONTAINER, 28, context));
            int pad = Settings.dp(context, 20);
            column.setPadding(pad, pad, pad, pad);

            TextView title = new TextView(context);
            title.setText("Your settings live here");
            Settings.titleLarge(title);
            column.addView(title);

            TextView body = new TextView(context);
            body.setText("Press and hold the Home button below to open Feurstagram "
                    + "and choose what to block.\n\nTry it now — it's the only way in.");
            Settings.body(body);
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            body.setPadding(0, Settings.dp(context, 10), 0, 0);
            column.addView(body);

            return column;
        }

        /** Circle centre in this view's own coordinate space. */
        private float localCenterX() {
            return screenCenterX - selfLocation[0];
        }

        private float localCenterY() {
            return screenCenterY - selfLocation[1];
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            getLocationOnScreen(selfLocation);
            int width = right - left;
            float centerX = localCenterX();
            float centerY = localCenterY();
            int margin = Settings.dp(getContext(), 24);

            measureChild(card, MeasureSpec.makeMeasureSpec(width - 2 * margin, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(bottom - top, MeasureSpec.AT_MOST));
            measureChild(arrow, MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(bottom - top, MeasureSpec.AT_MOST));
            measureChild(skip, MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(bottom - top, MeasureSpec.AT_MOST));

            // Arrow sits just above the highlighted circle, pointing at it, and is
            // centred on the Home tab (which is at the left end of the tab bar).
            int arrowLeft = clamp((int) (centerX - arrow.getMeasuredWidth() / 2f),
                    margin, width - margin - arrow.getMeasuredWidth());
            int arrowBottom = (int) (centerY - radius - pulseSpread - Settings.dp(getContext(), 8));
            arrow.layout(arrowLeft, arrowBottom - arrow.getMeasuredHeight(),
                    arrowLeft + arrow.getMeasuredWidth(), arrowBottom);

            // Card above the arrow, horizontally centred on screen.
            int cardLeft = (width - card.getMeasuredWidth()) / 2;
            int cardBottom = arrowBottom - arrow.getMeasuredHeight() - Settings.dp(getContext(), 12);
            int cardTop = Math.max(margin, cardBottom - card.getMeasuredHeight());
            card.layout(cardLeft, cardTop, cardLeft + card.getMeasuredWidth(),
                    cardTop + card.getMeasuredHeight());

            // Skip in the top-right corner, clear of the status bar.
            int skipTop = Settings.statusBarHeight(getContext()) + Settings.dp(getContext(), 8);
            int skipRight = width - Settings.dp(getContext(), 12);
            skip.layout(skipRight - skip.getMeasuredWidth(), skipTop,
                    skipRight, skipTop + skip.getMeasuredHeight());
        }

        private static int clamp(int value, int min, int max) {
            if (max < min) return min;
            return Math.max(min, Math.min(max, value));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            getLocationOnScreen(selfLocation);
            float centerX = localCenterX();
            float centerY = localCenterY();

            canvas.drawRect(0, 0, getWidth(), getHeight(), scrimPaint);
            canvas.drawCircle(centerX, centerY, radius, holePaint);
            canvas.drawCircle(centerX, centerY, radius, ringPaint);

            // Expanding ring that fades out, to read as "press and hold here".
            float phase = ((SystemClock.uptimeMillis() - startedAt) % PULSE_PERIOD_MS) / (float) PULSE_PERIOD_MS;
            pulsePaint.setAlpha((int) (200 * (1f - phase)));
            canvas.drawCircle(centerX, centerY, radius + phase * pulseSpread, pulsePaint);
            postInvalidateOnAnimation();
        }

        @Override
        public void onWindowFocusChanged(boolean hasWindowFocus) {
            super.onWindowFocusChanged(hasWindowFocus);
            if (hasWindowFocus) {
                everFocused = true;
                // Uninterrupted on a focused screen for a moment = actually seen.
                postDelayed(settleRunnable, SETTLED_MS);
                return;
            }
            removeCallbacks(settleRunnable);
            // Focus lost before we ever had it is just the window handing over;
            // losing it afterwards means something came up in front of us.
            if (everFocused && onCovered != null) onCovered.run();
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(settleRunnable);
            removeCallbacks(longPressRunnable);
            super.onDetachedFromWindow();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float centerX = localCenterX();
            float centerY = localCenterY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    longPressFired = false;
                    longPressArmed = distance(downX, downY, centerX, centerY) <= radius;
                    if (longPressArmed) {
                        postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (longPressArmed && distance(event.getX(), event.getY(), downX, downY) > touchSlop) {
                        cancelLongPress();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (longPressArmed && !longPressFired) cancelLongPress();
                    longPressArmed = false;
                    break;
                default:
                    break;
            }
            // Always consume: nothing behind the scrim may be tapped.
            return true;
        }

        @Override
        public void cancelLongPress() {
            super.cancelLongPress();
            removeCallbacks(longPressRunnable);
            longPressArmed = false;
        }

        private static float distance(float x1, float y1, float x2, float y2) {
            float dx = x1 - x2;
            float dy = y1 - y2;
            return (float) Math.sqrt(dx * dx + dy * dy);
        }
    }
}

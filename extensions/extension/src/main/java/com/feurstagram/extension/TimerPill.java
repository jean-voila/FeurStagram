package com.feurstagram.extension;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The pill shown while an unlock session exists (running or paused): the time
 * left, at the top centre at first, and draggable anywhere like an Android bubble
 * (it remembers where it was left). A tap grows it into a card with Pause/Resume
 * and Skip; a tap outside or on the card's top shrinks it back.
 *
 * It is a view of ours added on top of each Instagram activity's window while that
 * activity is in front: no Instagram code is touched, touches outside the pill go
 * through to Instagram untouched, and nothing shows when there is no session.
 */
final class TimerPill {

    private TimerPill() {}

    private static boolean registered;
    private static final int BLACK = 0xFF000000;
    private static final int RUNNING_DOT = 0xFF34C759;
    private static final int PAUSED_DOT = 0xFFFFB020;

    /** Called once the main window exists; also ends a session that ran out while closed. */
    static void install(Context context) {
        Activity activity = Activities.unwrap(context);
        if (activity == null || !Timer.isEnabled()) return;
        if (Timer.expireIfDone()) {
            // Unlocked content may still sit in the cache: clear it now.
            CacheCleaner.clearAndRestart(activity);
            return;
        }
        if (!registered && activity.getApplication() != null) {
            registered = true;
            activity.getApplication().registerActivityLifecycleCallbacks(new Callbacks());
        }
        attach(activity);
    }

    private static final class Callbacks implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(Activity a) {
            if (Timer.isEnabled() && Timer.expireIfDone()) {
                CacheCleaner.clearAndRestart(a);
                return;
            }
            attach(a);
        }
        @Override public void onActivityPaused(Activity a) { detach(a); }
        @Override public void onActivityCreated(Activity a, Bundle state) {}
        @Override public void onActivityStarted(Activity a) {}
        @Override public void onActivityStopped(Activity a) {}
        @Override public void onActivitySaveInstanceState(Activity a, Bundle state) {}
        @Override public void onActivityDestroyed(Activity a) { detach(a); }
    }

    private static void attach(Activity activity) {
        try {
            if (!Timer.hasSession() || activity.getWindow() == null) return;
            View decor = activity.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) return;
            ViewGroup root = (ViewGroup) decor;
            for (int i = 0; i < root.getChildCount(); i++) {
                if (root.getChildAt(i) instanceof Overlay) return;
            }
            root.addView(new Overlay(activity), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable ignored) {
            // no pill; the timer itself still runs
        }
    }

    private static void detach(Activity activity) {
        try {
            if (activity.getWindow() == null) return;
            View decor = activity.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) return;
            ViewGroup root = (ViewGroup) decor;
            for (int i = root.getChildCount() - 1; i >= 0; i--) {
                if (root.getChildAt(i) instanceof Overlay) root.removeViewAt(i);
            }
        } catch (Throwable ignored) {
            // nothing attached
        }
    }

    /**
     * Full-window and transparent to touches; holds the island and, while it is
     * open, a scrim that closes it.
     */
    private static final class Overlay extends FrameLayout {
        private final Activity activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final View scrim;
        private final FrameLayout island;
        private final GradientDrawable shape = new GradientDrawable();
        private final LinearLayout compact;
        private final View dot;
        private final TextView compactTime;
        private final LinearLayout expanded;
        private final TextView bigTime;
        private final TextView detail;
        private final Button pauseButton;
        private boolean open;
        private ValueAnimator animator;
        private ValueAnimator settle;
        private int topInset;
        private int bottomInset;
        /** The pill's centre, in this window's pixels; -1 until first placed. */
        private float centerX = -1f;
        private float centerY = -1f;
        // Dragging, like an Android bubble: the pill follows the finger, keeps its
        // momentum when let go and settles inside the screen.
        private VelocityTracker velocity;
        private float grabX, grabY, downRawX, downRawY;
        private boolean dragging;
        private final int touchSlop;

        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!isAttachedToWindow()) return;
                if (Timer.RUNNING.equals(Timer.state()) && Timer.remainingMs() <= 0) {
                    if (Timer.expireIfDone()) CacheCleaner.clearAndRestart(activity);
                    return;
                }
                if (!Timer.hasSession()) {
                    setVisibility(GONE);
                    return;
                }
                refresh();
                handler.postDelayed(this, 1000L);
            }
        };

        Overlay(Activity activity) {
            super(activity);
            this.activity = activity;
            Context context = activity;
            setClipChildren(false);

            scrim = new View(context);
            scrim.setBackgroundColor(0x66000000);
            scrim.setAlpha(0f);
            scrim.setVisibility(GONE);
            scrim.setOnClickListener(v -> toggle());
            addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            island = new FrameLayout(context);
            shape.setColor(BLACK);
            island.setBackground(shape);
            island.setClickable(true);
            island.setElevation(Settings.dp(context, 6));
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            island.setOnTouchListener(this::onIslandTouch);
            addView(island, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.LEFT));
            float[] saved = savedCenter();
            centerX = saved[0];
            centerY = saved[1];

            // Collapsed: a dot and the time left.
            compact = new LinearLayout(context);
            compact.setOrientation(LinearLayout.HORIZONTAL);
            compact.setGravity(Gravity.CENTER);
            compact.setPadding(Settings.dp(context, 12), 0, Settings.dp(context, 14), 0);
            dot = new View(context);
            GradientDrawable round = new GradientDrawable();
            round.setShape(GradientDrawable.OVAL);
            round.setColor(RUNNING_DOT);
            dot.setBackground(round);
            int dotSize = Settings.dp(context, 8);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dotSize, dotSize);
            dotLp.rightMargin = Settings.dp(context, 8);
            compact.addView(dot, dotLp);
            compactTime = text(context, 14f, Typeface.DEFAULT_BOLD);
            compact.addView(compactTime);
            island.addView(compact, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT,
                    Gravity.CENTER));

            // Open: what is unlocked, the time, Pause/Resume and Skip.
            expanded = new LinearLayout(context);
            expanded.setOrientation(LinearLayout.VERTICAL);
            int pad = Settings.dp(context, 20);
            expanded.setPadding(pad, Settings.dp(context, 18), pad, pad);
            expanded.setAlpha(0f);
            expanded.setVisibility(GONE);
            TextView title = text(context, 13f, Typeface.DEFAULT);
            title.setText("FeurStagram unlock");
            title.setTextColor(0xFFABABAB);
            title.setOnClickListener(v -> toggle());
            expanded.addView(title);
            bigTime = text(context, 34f, Typeface.create("sans-serif-medium", Typeface.NORMAL));
            bigTime.setPadding(0, Settings.dp(context, 2), 0, 0);
            expanded.addView(bigTime);
            detail = text(context, 13f, Typeface.DEFAULT);
            detail.setTextColor(0xFFABABAB);
            detail.setPadding(0, Settings.dp(context, 2), 0, Settings.dp(context, 16));
            expanded.addView(detail);
            LinearLayout buttons = new LinearLayout(context);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            pauseButton = Settings.makeButton(context, "Pause", 0xFF2C2C2E, Color.WHITE, true);
            pauseButton.setOnClickListener(v -> pauseOrResume());
            buttons.addView(pauseButton, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            View gap = new View(context);
            buttons.addView(gap, new LinearLayout.LayoutParams(Settings.dp(context, 8), 1));
            Button skip = Settings.makeButton(context, "Skip", Color.WHITE, BLACK, true);
            skip.setOnClickListener(v -> confirmSkip());
            buttons.addView(skip, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            expanded.addView(buttons);
            island.addView(expanded, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
                    Gravity.TOP));

            refresh();
            applyGeometry(0f);
        }

        private static TextView text(Context context, float sp, Typeface face) {
            TextView view = new TextView(context);
            view.setTextColor(Color.WHITE);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            view.setTypeface(face);
            view.setSingleLine(true);
            view.setIncludeFontPadding(false);
            return view;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            handler.removeCallbacks(tick);
            handler.post(tick);
        }

        @Override
        protected void onDetachedFromWindow() {
            handler.removeCallbacks(tick);
            if (animator != null) animator.cancel();
            super.onDetachedFromWindow();
        }

        @Override
        public WindowInsets onApplyWindowInsets(WindowInsets insets) {
            int top;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                top = insets.getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()).top;
            } else {
                top = insets.getSystemWindowInsetTop();
            }
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            } else {
                bottom = insets.getSystemWindowInsetBottom();
            }
            if (top != topInset || bottom != bottomInset) {
                topInset = top;
                bottomInset = bottom;
                applyGeometry(open ? 1f : 0f);
            }
            return insets;
        }

        private void refresh() {
            boolean running = Timer.RUNNING.equals(Timer.state());
            long left = Timer.remainingMs();
            compactTime.setText(Timer.formatClock(left));
            ((GradientDrawable) dot.getBackground()).setColor(running ? RUNNING_DOT : PAUSED_DOT);
            bigTime.setText(Timer.formatClock(left));
            detail.setText((running ? "Unlocked: " : "Paused: ") + unlockedNames());
            pauseButton.setText(running ? "Pause" : "Resume");
            island.setContentDescription("FeurStagram unlock, " + Timer.formatLong(left) + " left"
                    + (running ? "" : ", paused"));
        }

        private static String unlockedNames() {
            List<String> names = new ArrayList<>();
            for (String key : Settings.SURFACE_KEYS) {
                if (Timer.isPicked(key) && Config.storedBlocked(key)) names.add(Settings.surfaceLabel(key));
            }
            return names.isEmpty() ? "nothing" : android.text.TextUtils.join(", ", names);
        }

        private void toggle() {
            open = !open;
            if (animator != null) animator.cancel();
            float from = open ? 0f : 1f;
            float to = open ? 1f : 0f;
            if (open) {
                expanded.setVisibility(VISIBLE);
                scrim.setVisibility(VISIBLE);
            }
            animator = ValueAnimator.ofFloat(from, to);
            animator.setDuration(open ? 420L : 300L);
            // A soft overshoot on the way out, the island's "spring".
            animator.setInterpolator(open ? new PathInterpolator(0.2f, 1.25f, 0.35f, 1f)
                    : new PathInterpolator(0.4f, 0f, 0.2f, 1f));
            animator.addUpdateListener(a -> applyGeometry((float) a.getAnimatedValue()));
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    if (!open) {
                        expanded.setVisibility(GONE);
                        scrim.setVisibility(GONE);
                    }
                }
            });
            animator.start();
        }

        // --- Where the pill is ---------------------------------------------------------

        private int pillHeight() { return Settings.dp(getContext(), 28); }

        private int pillWidth() {
            compact.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.makeMeasureSpec(pillHeight(), MeasureSpec.EXACTLY));
            return Math.max(Settings.dp(getContext(), 96), compact.getMeasuredWidth());
        }

        private int screenWidth() { return getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels; }

        private int screenHeight() { return getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels; }

        /**
         * The area the pill may sit in: under the status bar (Android gives every
         * touch on the bar itself to the system), above the navigation bar.
         */
        private float clampX(float x) {
            float half = pillWidth() / 2f + Settings.dp(getContext(), 6);
            return Math.max(half, Math.min(screenWidth() - half, x));
        }

        private float clampY(float y) {
            int statusBar = topInset > 0 ? topInset : Settings.statusBarHeight(getContext());
            float half = pillHeight() / 2f;
            float min = statusBar + Settings.dp(getContext(), 4) + half;
            float max = screenHeight() - bottomInset - Settings.dp(getContext(), 8) - half;
            return Math.max(min, Math.min(Math.max(min, max), y));
        }

        /** The last spot the pill was left at, as fractions of the screen; default top centre. */
        private float[] savedCenter() {
            long fx = Config.getLong("timer_pill_x", -1L);
            long fy = Config.getLong("timer_pill_y", -1L);
            if (fx < 0 || fy < 0) return new float[] {-1f, -1f};
            return new float[] {fx / 10000f * screenWidth(), fy / 10000f * screenHeight()};
        }

        private void saveCenter() {
            Config.putLong("timer_pill_x", (long) (centerX / screenWidth() * 10000f));
            Config.putLong("timer_pill_y", (long) (centerY / screenHeight() * 10000f));
        }

        private boolean onIslandTouch(View view, MotionEvent event) {
            if (open) {
                // The open card does not move; taps go to its buttons.
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (settle != null) settle.cancel();
                    if (velocity != null) velocity.recycle();
                    velocity = VelocityTracker.obtain();
                    velocity.addMovement(event);
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    grabX = centerX - downRawX;
                    grabY = centerY - downRawY;
                    dragging = false;
                    island.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120L).start();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (velocity != null) velocity.addMovement(event);
                    if (!dragging && Math.hypot(event.getRawX() - downRawX, event.getRawY() - downRawY) > touchSlop) {
                        dragging = true;
                    }
                    if (dragging) {
                        centerX = clampX(event.getRawX() + grabX);
                        centerY = clampY(event.getRawY() + grabY);
                        place(pillWidth(), pillHeight(), 0f);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    island.animate().scaleX(1f).scaleY(1f).setDuration(160L).start();
                    if (dragging) {
                        float vx = 0f, vy = 0f;
                        if (velocity != null) {
                            velocity.addMovement(event);
                            velocity.computeCurrentVelocity(1000);
                            vx = velocity.getXVelocity();
                            vy = velocity.getYVelocity();
                        }
                        settleWithMomentum(vx, vy);
                    } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        toggle();
                    }
                    if (velocity != null) {
                        velocity.recycle();
                        velocity = null;
                    }
                    dragging = false;
                    return true;
                default:
                    return false;
            }
        }

        /** Carry on in the throw's direction a little, then come to rest inside the screen. */
        private void settleWithMomentum(float vx, float vy) {
            final float fromX = centerX;
            final float fromY = centerY;
            final float toX = clampX(centerX + vx * 0.12f);
            final float toY = clampY(centerY + vy * 0.12f);
            settle = ValueAnimator.ofFloat(0f, 1f);
            settle.setDuration(320L);
            settle.setInterpolator(new PathInterpolator(0.2f, 1.1f, 0.4f, 1f));
            settle.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                centerX = fromX + (toX - fromX) * t;
                centerY = fromY + (toY - fromY) * t;
                place(pillWidth(), pillHeight(), 0f);
            });
            settle.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    saveCenter();
                }
            });
            settle.start();
        }

        /** 0 = the pill, 1 = the open card; values past 1 overshoot. */
        private void applyGeometry(float t) {
            Context context = getContext();
            int pillHeight = pillHeight();
            int pillWidth = pillWidth();
            if (centerX < 0 || centerY < 0) {
                float[] saved = savedCenter();
                centerX = saved[0] >= 0 ? saved[0] : screenWidth() / 2f;
                centerY = saved[1] >= 0 ? saved[1] : -1f;
            }
            if (centerY < 0) centerY = clampY(0f); // default: top centre, under the status bar
            centerX = clampX(centerX);
            centerY = clampY(centerY);

            int cardWidth = Math.min(screenWidth() - Settings.dp(context, 24), Settings.dp(context, 420));
            expanded.measure(MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY), MeasureSpec.UNSPECIFIED);
            int cardHeight = expanded.getMeasuredHeight();

            int width = (int) (pillWidth + (cardWidth - pillWidth) * t);
            int height = (int) (pillHeight + (cardHeight - pillHeight) * t);
            LayoutParams lp = (LayoutParams) island.getLayoutParams();
            lp.width = Math.max(pillWidth / 2, width);
            lp.height = Math.max(pillHeight / 2, height);
            island.setLayoutParams(lp);
            float clamped = Math.min(1f, Math.max(0f, t));
            shape.setCornerRadius(pillHeight / 2f + (Settings.dp(context, 34) - pillHeight / 2f) * clamped);
            compact.setAlpha(Math.max(0f, 1f - clamped * 3f));
            expanded.setAlpha(Math.max(0f, (clamped - 0.35f) / 0.65f));
            scrim.setAlpha(clamped);
            place(lp.width, lp.height, t);
        }

        /**
         * Position the island: the pill around its centre; the card grows from the
         * pill, downwards in the top half of the screen and upwards in the bottom one,
         * and is kept on screen.
         */
        private void place(int width, int height, float t) {
            Context context = getContext();
            float margin = Settings.dp(context, 12);
            float pillLeft = centerX - pillWidth() / 2f;
            float pillTop = centerY - pillHeight() / 2f;
            boolean upwards = centerY > screenHeight() / 2f;
            float cardLeft = Math.max(margin, Math.min(screenWidth() - margin - width, centerX - width / 2f));
            float cardTop = upwards ? centerY + pillHeight() / 2f - height : pillTop;
            int statusBar = topInset > 0 ? topInset : Settings.statusBarHeight(context);
            cardTop = Math.max(statusBar + Settings.dp(context, 4),
                    Math.min(screenHeight() - bottomInset - height - margin, cardTop));
            float clamped = Math.min(1f, Math.max(0f, t));
            island.setX(pillLeft + (cardLeft - pillLeft) * clamped);
            island.setY(t <= 0f ? pillTop : cardTop);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (oldw > 0 && oldh > 0 && centerX >= 0) {
                centerX = centerX / oldw * w;
                centerY = centerY / oldh * h;
            }
            applyGeometry(open ? 1f : 0f);
        }

        private void pauseOrResume() {
            boolean changed = Timer.RUNNING.equals(Timer.state()) ? Timer.pause() : Timer.start();
            if (changed) CacheCleaner.clearAndRestart(activity);
        }

        private void confirmSkip() {
            Settings.showConfirm(activity, "Skip this session?",
                    "Blocked surfaces come back now, and the " + Timer.formatLong(Timer.remainingMs())
                            + " left are lost until the next refill.",
                    "Skip", Settings.PRIMARY, Settings.ON_PRIMARY,
                    () -> { if (Timer.skip()) CacheCleaner.clearAndRestart(activity); });
        }
    }
}

package com.feurstagram.extension;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * A Feurstagram icon in the header of the signed-in user's own profile, just
 * before the menu button: a tap opens the settings, and a red dot on it says an
 * update is waiting.
 *
 * <h2>Why there</h2>
 * The profile's menu is where people look for settings, and the header is the
 * same on every visit to the Profile tab, whatever is blocked or the landing page.
 * It is discreet: nowhere else in the app changes. (An icon at the end of the
 * bottom bar was tried and found too intrusive; a button in the feed header
 * disappeared whenever the feed was blocked.)
 *
 * <h2>Robustness</h2>
 * A window layout listener re-checks on every pass that the button is there and
 * puts it back when Instagram rebuilds the header. The own profile is told from
 * anyone else's by the "Create" button on the left of its header. The button is
 * recognised by its class, never by a tag (Instagram owns its views' tags), and
 * is sized like the header's own buttons. When the header changes shape in a later
 * Instagram release, the button simply does not appear; the long-press on the
 * first tab still opens the settings.
 */
public final class ProfileSettingsButton {

    /** Instagram's badge red. */
    private static final int BADGE_RED = 0xFFFF3040;

    private static final List<WeakReference<ButtonView>> BUTTONS = new ArrayList<>();

    private ProfileSettingsButton() {}

    /** Keep the button in the profile header of {@code root}'s window for as long as it lives. */
    static void install(ViewGroup root) {
        if (root == null) return;
        root.getViewTreeObserver().addOnGlobalLayoutListener(new Keeper(root));
    }

    /** Redraw every live button's badge, after an update check changed what is known. */
    static void refreshBadges() {
        Runnable refresh = () -> {
            boolean pending = UpdateChecker.isUpdatePending();
            for (int i = BUTTONS.size() - 1; i >= 0; i--) {
                ButtonView button = BUTTONS.get(i).get();
                if (button == null) {
                    BUTTONS.remove(i);
                } else {
                    button.setBadge(pending);
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) refresh.run();
        else new Handler(Looper.getMainLooper()).post(refresh);
    }

    private static final class Keeper implements ViewTreeObserver.OnGlobalLayoutListener {
        private final WeakReference<ViewGroup> root;

        Keeper(ViewGroup root) {
            this.root = new WeakReference<>(root);
        }

        @Override
        public void onGlobalLayout() {
            try {
                ViewGroup scope = root.get();
                if (scope == null) return;
                Context context = scope.getContext();
                View window = scope.getRootView();
                int createId = Hiders.resolveId(context, "profile_header_create_button");
                int rightId = Hiders.resolveId(context, "right_action_bar_buttons");
                int barId = Hiders.resolveId(context, "action_bar_layout");
                if (window == null || createId == 0 || rightId == 0 || barId == 0) return;
                View create = window.findViewById(createId);
                if (create == null) return; // own profile not built yet
                View bar = create;
                while (bar != null && bar.getId() != barId) {
                    bar = bar.getParent() instanceof View ? (View) bar.getParent() : null;
                }
                if (bar == null) return;
                View right = bar.findViewById(rightId);
                if (!(right instanceof LinearLayout)) return;
                LinearLayout buttons = (LinearLayout) right;
                View menu = null;
                for (int i = 0; i < buttons.getChildCount(); i++) {
                    View child = buttons.getChildAt(i);
                    if (child instanceof ButtonView) return; // already there
                    if (child instanceof ImageView && child.getVisibility() == View.VISIBLE) menu = child;
                }
                if (menu == null || menu.getWidth() == 0 || menu.getHeight() == 0
                        || !(menu.getLayoutParams() instanceof LinearLayout.LayoutParams)) {
                    return; // not laid out yet
                }
                attach(buttons, menu);
            } catch (Throwable ignored) {
                // A missing shortcut is fine; taking Instagram down is not.
            }
        }
    }

    /** Add the button just before the menu (the group's last button), at its exact size. */
    private static void attach(LinearLayout buttons, View menu) {
        ButtonView button = new ButtonView(buttons.getContext(), iconColor(menu), iconSize(menu));
        button.setOnClickListener(ProfileSettingsButton::open);
        button.setOnLongClickListener(v -> {
            open(v);
            return true;
        });
        // A plain View asked to wrap its content takes all the room: give it the
        // menu button's own measured size.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                (LinearLayout.LayoutParams) menu.getLayoutParams());
        lp.width = menu.getWidth();
        lp.height = menu.getHeight();
        buttons.addView(button, buttons.indexOfChild(menu), lp);
        button.setBadge(UpdateChecker.isUpdatePending());
        BUTTONS.add(new WeakReference<>(button));
    }

    private static void open(View from) {
        Context activity = Settings.getActivityContext(from);
        if (activity != null) Settings.show(activity);
    }

    /** The colour of the header's own icons, read off the menu button. */
    private static int iconColor(View menu) {
        if (menu instanceof ImageView) {
            ColorStateList tint = ((ImageView) menu).getImageTintList();
            if (tint != null) return tint.getDefaultColor();
        }
        int night = menu.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_NO ? 0xFF000000 : 0xFFFFFFFF;
    }

    /** The size the header draws its icons at: the menu glyph's, not its padded button. */
    private static int iconSize(View menu) {
        int fallback = Settings.dp(menu.getContext(), 24);
        Drawable glyph = menu instanceof ImageView ? ((ImageView) menu).getDrawable() : null;
        if (glyph == null || glyph.getIntrinsicHeight() <= 0) return fallback;
        int inner = menu.getHeight() - menu.getPaddingTop() - menu.getPaddingBottom();
        return inner > 0 ? Math.min(glyph.getIntrinsicHeight(), inner) : glyph.getIntrinsicHeight();
    }

    /** The button itself: the logo centred in its slot, and the update dot. */
    static final class ButtonView extends View {
        private final Logo logo;
        private final int iconSize;
        private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean badge;

        ButtonView(Context context, int color, int iconSize) {
            super(context);
            this.iconSize = iconSize;
            logo = new Logo(color);
            badgePaint.setColor(BADGE_RED);
            setClickable(true);
            setFocusable(true);
            setContentDescription("FeurStagram settings");
            setBackground(new RippleDrawable(ColorStateList.valueOf(0x33808080), null, null));
        }

        void setBadge(boolean show) {
            if (badge == show) return;
            badge = show;
            setContentDescription(show ? "FeurStagram settings, update available" : "FeurStagram settings");
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int left = (getWidth() - iconSize) / 2;
            int top = (getHeight() - iconSize) / 2;
            logo.setBounds(left, top, left + iconSize, top + iconSize);
            logo.draw(canvas);
            if (badge) {
                // Where Instagram puts its own dots: over the icon's top-right corner.
                float radius = iconSize * 0.15f;
                canvas.drawCircle(left + iconSize - radius * 0.5f, top + radius * 0.5f, radius, badgePaint);
            }
        }
    }

    /**
     * The Feurstagram mark as a line icon, on Instagram's 24-unit grid with its
     * 2-unit stroke: a rounded diamond around a ring.
     */
    static final class Logo extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF square = new RectF();

        Logo(int color) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float unit = Math.min(b.width(), b.height()) / 24f;
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            paint.setStrokeWidth(2f * unit);

            float half = 8f * unit;
            square.set(cx - half, cy - half, cx + half, cy + half);
            canvas.save();
            canvas.rotate(45f, cx, cy);
            canvas.drawRoundRect(square, 5f * unit, 5f * unit, paint);
            canvas.restore();

            canvas.drawCircle(cx, cy, 3.6f * unit, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public void setTint(int color) { paint.setColor(color); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return 24; }
        @Override public int getIntrinsicHeight() { return 24; }
    }
}

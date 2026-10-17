package com.feurstagram.extension;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;

/**
 * The optional "Saved" button: the bottom bar's Reels slot, re-skinned with a
 * bookmark and pointed at the saved-posts screen. Instagram has no Saved tab of
 * its own, so rather than inserting a view into a tab bar whose layout we don't
 * own, this borrows a slot that already sits there hidden.
 *
 * Everything is re-applied on each layout pass by {@link Hiders.SavedTabHook}:
 * Instagram rebinds its tabs (icons on selection changes, listeners on resume),
 * and each step here is a no-op when it already holds.
 *
 * Taps are taken with a touch listener rather than only a click listener, so the
 * tab's own touch handling (which would page to Reels) never sees them. The click
 * listener and accessibility delegate cover the paths that bypass touch: a
 * TalkBack double-tap and {@code performClick()} from code.
 */
final class SavedButton {

    private SavedButton() {}

    static final String LABEL = "Saved";

    /** Default icon size when the slot's original drawable has no intrinsic size. */
    private static final int ICON_DP = 24;

    private static final View.OnTouchListener TOUCH = (v, event) -> {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                v.setPressed(true);
                break;
            case MotionEvent.ACTION_UP:
                v.setPressed(false);
                if (event.getX() >= 0 && event.getX() <= v.getWidth()
                        && event.getY() >= 0 && event.getY() <= v.getHeight()) {
                    v.playSoundEffect(SoundEffectConstants.CLICK);
                    open(v);
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                v.setPressed(false);
                break;
            default:
                break;
        }
        return true; // swallow everything: the tab's own handler would open Reels
    };

    private static final View.OnClickListener CLICK = SavedButton::open;

    private static final View.AccessibilityDelegate ACCESSIBILITY = new View.AccessibilityDelegate() {
        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName("android.widget.Button");
            info.setContentDescription(LABEL);
            info.setSelected(false);
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle args) {
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                open(host);
                return true;
            }
            return super.performAccessibilityAction(host, action, args);
        }
    };

    /** Turn the Reels slot into the Saved button. Safe to call on every layout pass. */
    static void apply(View tab) {
        if (tab.getVisibility() != View.VISIBLE) tab.setVisibility(View.VISIBLE);
        if (!LABEL.contentEquals(String.valueOf(tab.getContentDescription()))) {
            tab.setContentDescription(LABEL);
        }
        tab.setOnTouchListener(TOUCH);
        tab.setOnClickListener(CLICK);
        tab.setOnLongClickListener(null);
        tab.setLongClickable(false);
        tab.setAccessibilityDelegate(ACCESSIBILITY);

        // Every bottom tab holds its glyph in a "tab_icon" ImageView; the scan is
        // the fallback for a build that renames it.
        int iconId = Hiders.resolveId(tab.getContext(), "tab_icon");
        View named = iconId == 0 ? null : tab.findViewById(iconId);
        ImageView icon = named instanceof ImageView ? (ImageView) named : findIcon(tab);
        if (icon != null && !(icon.getDrawable() instanceof BookmarkDrawable)) {
            icon.setImageDrawable(new BookmarkDrawable(icon.getContext(), icon.getDrawable()));
        }
    }

    /**
     * The tab's icon: the largest visible ImageView in its subtree (or the tab
     * itself). Largest, so a badge or a dot sitting next to the icon is skipped.
     */
    private static ImageView findIcon(View view) {
        if (view instanceof ImageView) return (ImageView) view;
        if (!(view instanceof ViewGroup)) return null;
        ImageView best = null;
        long bestArea = -1;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            ImageView candidate = findIcon(child);
            if (candidate == null) continue;
            long area = (long) candidate.getWidth() * candidate.getHeight();
            if (area > bestArea) {
                best = candidate;
                bestArea = area;
            }
        }
        return best;
    }

    /**
     * Open the saved-posts screen through Instagram's own deep-link router. The
     * intent is pinned to our own package, so a {@code --clone} build opens itself
     * rather than the stock app, and it is sent from inside the app, which is what
     * the route's {@code SAME_APP} scope requires. Launching the handler activity
     * directly is not an option: it needs a session-token bundle only the router
     * builds, and finishes without one. If nothing handles the link, fall back to
     * the profile tab, where Saved is in the menu.
     */
    static void open(View v) {
        Context context = Settings.getActivityContext(v);
        if (context == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(Config.getSavedUri()));
            intent.setPackage(context.getPackageName());
            intent.putExtra(CALLER_IDENTITY_EXTRA, callerIdentity(context));
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            int id = Hiders.resolveId(context, "profile_tab");
            View profile = id == 0 ? null : v.getRootView().findViewById(id);
            if (profile != null && profile.getVisibility() == View.VISIBLE) {
                profile.performClick();
            } else {
                Toast.makeText(context, "Saved posts are unavailable", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * Intent extra Meta's deep-link router reads the caller's identity from. A
     * plain startActivity from inside the app carries none, so the router treats
     * it as a foreign (PUBLIC) caller and drops SAME_APP routes such as Saved.
     */
    private static final String CALLER_IDENTITY_EXTRA = "_ci_";

    /**
     * The app's own caller identity, in the shape the router parses: an immutable
     * PendingIntent created by this package, whose tag (its base intent's action)
     * is URL-safe, unpadded Base64 of {@code {"t": wall-clock ms, "r": elapsed
     * ms}}. The router takes package and signature from the PendingIntent's
     * creator, so this can only ever vouch for the app that builds it.
     */
    private static PendingIntent callerIdentity(Context context) {
        String meta = "{\"t\":\"" + System.currentTimeMillis()
                + "\",\"r\":\"" + SystemClock.elapsedRealtime() + "\"}";
        String tag = Base64.encodeToString(meta.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        Intent marker = new Intent(tag).setPackage(context.getPackageName());
        return PendingIntent.getBroadcast(context, 0, marker, PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * An outline bookmark, drawn in code (there is no {@code res/}). Sized like
     * the drawable it replaces so it lines up with the other tab icons, and it
     * honours the tint and colour filter Instagram applies to its tab icons, so
     * it follows the app's light/dark theme. Without either it falls back to
     * black or white from the night-mode flag.
     */
    static final class BookmarkDrawable extends Drawable {
        /** Viewport the outline is defined in (Instagram's 24-unit icon grid). */
        private static final float VIEWPORT = 24f;
        private static final float STROKE = 2f;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path outline = new Path();
        private final Path scaled = new Path();
        private final Matrix matrix = new Matrix();
        private final int width;
        private final int height;
        private final int fallbackColor;
        private ColorStateList tint;
        private ColorFilter colorFilter;

        BookmarkDrawable(Context context, Drawable original) {
            int fallback = Settings.dp(context, ICON_DP);
            int w = original == null ? -1 : original.getIntrinsicWidth();
            int h = original == null ? -1 : original.getIntrinsicHeight();
            width = w > 0 ? w : fallback;
            height = h > 0 ? h : fallback;

            int night = context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            fallbackColor = night == Configuration.UI_MODE_NIGHT_YES ? 0xFFFFFFFF : 0xFF000000;

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);

            outline.moveTo(20f, 21f);
            outline.lineTo(12f, 13.44f);
            outline.lineTo(4f, 21f);
            outline.lineTo(4f, 3f);
            outline.lineTo(20f, 3f);
            outline.close();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            float scale = Math.min(b.width(), b.height()) / VIEWPORT;
            float dx = b.left + (b.width() - VIEWPORT * scale) / 2f;
            float dy = b.top + (b.height() - VIEWPORT * scale) / 2f;
            matrix.setScale(scale, scale);
            matrix.postTranslate(dx, dy);
            outline.transform(matrix, scaled);

            paint.setStrokeWidth(STROKE * scale);
            if (colorFilter != null) {
                paint.setColor(fallbackColor);
                paint.setColorFilter(colorFilter);
            } else if (tint != null) {
                paint.setColor(tint.getColorForState(getState(), tint.getDefaultColor()));
                paint.setColorFilter(null);
            } else {
                paint.setColor(fallbackColor);
                paint.setColorFilter(null);
            }
            canvas.drawPath(scaled, paint);
        }

        @Override public int getIntrinsicWidth() { return width; }
        @Override public int getIntrinsicHeight() { return height; }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            colorFilter = filter;
            invalidateSelf();
        }

        @Override
        public void setTintList(ColorStateList tint) {
            this.tint = tint;
            invalidateSelf();
        }

        @Override public boolean isStateful() { return tint != null && tint.isStateful(); }

        @Override
        protected boolean onStateChange(int[] state) {
            if (tint == null) return false;
            invalidateSelf();
            return true;
        }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}

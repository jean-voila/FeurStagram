package com.feurstagram.extension;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

/**
 * Neutralises the Reels repost button when the "Disable reposting" toggle is on,
 * preventing accidental reposts while leaving the button (and the rest of the
 * action column) visually intact.
 *
 * <p>The repost control is an {@link android.widget.ImageView} with content
 * description "Repost". Instagram's reel action bar is a Litho component, so
 * hiding the icon would leave an empty gap (the layout doesn't reflow). Instead
 * we overwrite the button's click listener with a no-op, so tapping it does
 * nothing.
 *
 * <p>Instagram recycles reel views and re-binds the real click listener
 * asynchronously after a reel settles, so the no-op is re-applied every frame
 * via {@link ViewTreeObserver.OnPreDrawListener} (reels play video, so pre-draw
 * fires continuously — the rebind window is well under one frame).
 */
public final class RepostBlocker {

    private static final String REPOST_DESC = "Repost";

    /** Shared no-op listener installed on every repost button. */
    private static final View.OnClickListener BLOCK = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            // Intentionally empty — repost suppressed to avoid accidental taps.
        }
    };

    private RepostBlocker() {}

    /** Attach the watcher to the window root (DecorView). */
    public static void install(View decorView) {
        if (decorView == null) return;
        ViewTreeObserver observer = decorView.getViewTreeObserver();
        Watcher watcher = new Watcher(decorView);
        observer.addOnGlobalLayoutListener(watcher);
        observer.addOnPreDrawListener(watcher);
    }

    // -------------------------------------------------------------------------

    private static final class Watcher
            implements ViewTreeObserver.OnGlobalLayoutListener,
            ViewTreeObserver.OnPreDrawListener {

        private final View root;

        Watcher(View root) {
            this.root = root;
        }

        @Override
        public void onGlobalLayout() {
            apply();
        }

        @Override
        public boolean onPreDraw() {
            apply();
            return true;
        }

        private void apply() {
            if (!Config.isRepostBlocked()) return;
            block(root);
        }
    }

    private static void block(View view) {
        CharSequence desc = view.getContentDescription();
        if (desc != null && REPOST_DESC.contentEquals(desc) && view.isClickable()) {
            // Re-apply unconditionally: Instagram may have just re-bound its own
            // repost listener on this recycled view, and ours must win.
            view.setOnClickListener(BLOCK);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                block(group.getChildAt(i));
            }
        }
    }
}

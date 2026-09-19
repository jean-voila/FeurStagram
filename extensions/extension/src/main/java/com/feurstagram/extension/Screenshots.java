package com.feurstagram.extension;

import android.app.Activity;
import android.view.Window;

/**
 * Screenshot-bypass control.
 *
 * Instagram marks sensitive windows (view-once media, disappearing DMs) with
 * {@link android.view.WindowManager.LayoutParams#FLAG_SECURE} so the OS refuses screenshots
 * and screen recordings, disables recents thumbnails via
 * {@code Activity.setRecentsScreenshotEnabled(false)}, and reports a taken
 * screenshot to the sender through {@code direct_v2/.../ephemeral_screenshot/}
 * (blocked in {@link Block}).
 *
 * {@code ScreenshotBypassPatch} reroutes every {@code Window.addFlags(int)},
 * {@code Window.setFlags(int, int)} and
 * {@code Activity.setRecentsScreenshotEnabled(boolean)} call in Instagram to
 * these methods. When "Allow screenshots" is on we strip {@code FLAG_SECURE}
 * before it ever reaches the window manager and force recents thumbnails on;
 * when off every argument passes through untouched, restoring stock behaviour.
 */
public final class Screenshots {

    /** {@link android.view.WindowManager.LayoutParams#FLAG_SECURE}. */
    private static final int FLAG_SECURE = 0x00002000;

    private Screenshots() {}

    public static void addFlags(Window window, int flags) {
        if (window == null) return;
        if (Config.isScreenshotBypassEnabled()) flags &= ~FLAG_SECURE;
        window.addFlags(flags);
    }

    /**
     * {@code setFlags(flags, mask)} applies {@code flags} only to the bits in
     * {@code mask}, so forcing the {@code FLAG_SECURE} bit into the mask while
     * clearing it from {@code flags} guarantees the secure bit ends up off.
     */
    public static void setFlags(Window window, int flags, int mask) {
        if (window == null) return;
        if (Config.isScreenshotBypassEnabled()) {
            flags &= ~FLAG_SECURE;
            mask |= FLAG_SECURE;
        }
        window.setFlags(flags, mask);
    }

    public static void setRecentsScreenshotEnabled(Activity activity, boolean enabled) {
        if (activity == null) return;
        activity.setRecentsScreenshotEnabled(
                Config.isScreenshotBypassEnabled() ? true : enabled);
    }
}

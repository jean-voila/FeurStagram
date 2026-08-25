package com.feurstagram.extension;

import android.view.Window;

/**
 * Window colour-mode control.
 *
 * Instagram flips its main window into {@link android.content.pm.ActivityInfo#COLOR_MODE_HDR}
 * (desiredHdrHeadroom &gt; 1) at runtime, gated by a server-side experiment, so it
 * only kicks in on some accounts. On an HDR-capable OLED, engaging HDR headroom
 * shifts the panel's brightness/black handling and leaves the flat dark UI looking
 * washed out — with no benefit for a UI that has no HDR content of its own.
 *
 * {@code SetColorModePatch} reroutes every {@code Window.setColorMode(int)} call
 * in Instagram to this method. When "Force dark" is on (default) we pin the
 * window to {@link android.content.pm.ActivityInfo#COLOR_MODE_DEFAULT} (SDR) so
 * blacks stay deep; when off we pass Instagram's original argument through
 * untouched, restoring stock behaviour.
 */
public final class Display {

    /** {@link android.content.pm.ActivityInfo#COLOR_MODE_DEFAULT}. */
    private static final int COLOR_MODE_DEFAULT = 0;

    private Display() {}

    public static void setColorMode(Window window, int mode) {
        if (window == null) return;
        window.setColorMode(Config.isForceSdr() ? COLOR_MODE_DEFAULT : mode);
    }
}

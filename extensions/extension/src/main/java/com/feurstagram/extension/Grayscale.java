package com.feurstagram.extension;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;
import android.view.Window;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;

/**
 * "Grayscale" (Display, off by default): Instagram, and only Instagram, drawn
 * without colour. Display only: nothing that is saved or shared changes.
 *
 * <h2>How</h2>
 * Every window of the app (activities, dialogs, bottom sheets, popups) draws into
 * a hardware layer whose paint carries a zero-saturation colour matrix, so the GPU
 * turns the whole window grey as it composes it: interface, photos, TextureView
 * videos, all in one pass. New windows are caught as the window manager records
 * them; activities are also caught as they are created.
 *
 * <h2>The one exception: SurfaceView</h2>
 * A SurfaceView's pixels never go through the window: the compositor puts them
 * behind it. Instagram's video player uses one for Reels and feed videos, so while
 * the filter is on the player is asked for its TextureView path instead
 * ({@link #avoidSurfaceView}, Instagram's own "enforce texture view" switch).
 * Anything still drawn on a SurfaceView (a live broadcast, some camera previews)
 * keeps its colours.
 */
public final class Grayscale {

    private Grayscale() {}

    private static Paint paint;
    private static boolean installed;

    public static boolean isEnabled() {
        return Config.isGrayscale();
    }

    /**
     * Hook in the video player's choice of surface: true keeps Instagram's choice,
     * false sends the video to a TextureView, which the window's filter covers.
     */
    public static boolean avoidSurfaceView() {
        try {
            return isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Start filtering every window of the app; idempotent, a no-op when off. */
    static void install(Context context) {
        if (installed || !isEnabled()) return;
        installed = true;
        try {
            Activity activity = Activities.unwrap(context);
            if (activity != null) {
                apply(activity.getWindow());
                Application app = activity.getApplication();
                if (app != null) app.registerActivityLifecycleCallbacks(new Callbacks());
            }
            followNewWindows();
        } catch (Throwable ignored) {
            // colour stays; nothing else depends on this
        }
    }

    private static Paint paint() {
        if (paint == null) {
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(0f);
            Paint p = new Paint();
            p.setColorFilter(new ColorMatrixColorFilter(matrix));
            paint = p;
        }
        return paint;
    }

    private static void apply(Window window) {
        if (window != null) apply(window.peekDecorView());
    }

    static void apply(View root) {
        if (root == null) return;
        try {
            root.setLayerType(View.LAYER_TYPE_HARDWARE, paint());
        } catch (Throwable ignored) {
            // a window that refuses a layer keeps its colours
        }
    }

    /**
     * Windows other than activities (dialogs, bottom sheets, popups, the settings
     * page) are recorded in WindowManagerGlobal's list of root views before they
     * are attached; that list is swapped for one that filters each newcomer.
     */
    @SuppressWarnings("unchecked")
    private static void followNewWindows() throws Exception {
        Class<?> global = Class.forName("android.view.WindowManagerGlobal");
        Object instance = global.getMethod("getInstance").invoke(null);
        Field views = global.getDeclaredField("mViews");
        views.setAccessible(true);
        Field lock = global.getDeclaredField("mLock");
        lock.setAccessible(true);
        synchronized (lock.get(instance)) {
            Object current = views.get(instance);
            if (current instanceof RootViews) return;
            RootViews replacement = new RootViews((Collection<View>) current);
            for (View view : replacement) apply(view);
            views.set(instance, replacement);
        }
    }

    /** The window manager's list of root views, filtering each one as it is added. */
    private static final class RootViews extends ArrayList<View> {
        RootViews(Collection<View> existing) {
            super(existing);
        }

        @Override
        public boolean add(View view) {
            if (isEnabled()) apply(view);
            return super.add(view);
        }
    }

    private static final class Callbacks implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityCreated(Activity a, Bundle state) { apply(a.getWindow()); }
        @Override public void onActivityStarted(Activity a) { apply(a.getWindow()); }
        @Override public void onActivityResumed(Activity a) {}
        @Override public void onActivityPaused(Activity a) {}
        @Override public void onActivityStopped(Activity a) {}
        @Override public void onActivitySaveInstanceState(Activity a, Bundle state) {}
        @Override public void onActivityDestroyed(Activity a) {}
    }
}

package com.feurstagram.extension;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.Window;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the live Activity that owns a view, for Feurstagram's dialogs.
 *
 * A view's context is not always its Activity: Instagram inflates some layouts
 * ahead of time (profile header, inbox) with a wrapper around the application,
 * and a view can outlive the Activity it was built for (account switch, a
 * configuration change). A dialog built on such a context has no window token
 * and {@code show()} throws. So the owner is looked up by window token among the
 * Activities seen alive, with the latest resumed one as a last resort.
 */
final class Activities {

    private static final List<WeakReference<Activity>> ALIVE = new ArrayList<>();
    private static WeakReference<Activity> resumed = new WeakReference<>(null);
    private static boolean registered;

    private Activities() {}

    /** Start following the app's Activities; idempotent. */
    static void track(Context context) {
        Activity activity = unwrap(context);
        if (activity == null) return;
        remember(activity);
        if (!activity.isFinishing() && !activity.isDestroyed()) resumed = new WeakReference<>(activity);
        if (registered) return;
        Application app = activity.getApplication();
        if (app == null) return;
        registered = true;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle state) { remember(a); }
            @Override public void onActivityStarted(Activity a) { remember(a); }
            @Override public void onActivityResumed(Activity a) {
                remember(a);
                resumed = new WeakReference<>(a);
            }
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle state) {}
            @Override public void onActivityDestroyed(Activity a) { forget(a); }
        });
    }

    /** The Activity at the bottom of a context's wrapper chain, or null. */
    static Activity unwrap(Context context) {
        Context cursor = context;
        for (int depth = 0; cursor != null && depth < 32; depth++) {
            if (cursor instanceof Activity) return (Activity) cursor;
            if (!(cursor instanceof ContextWrapper)) return null;
            cursor = ((ContextWrapper) cursor).getBaseContext();
        }
        return null;
    }

    static boolean isUsable(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    /** Whether the Activity's window is attached, i.e. a dialog can go over it now. */
    static boolean hasWindow(Activity activity) {
        if (!isUsable(activity)) return false;
        Window window = activity.getWindow();
        return window != null && window.getDecorView().getWindowToken() != null;
    }

    /** The live Activity owning {@code view}'s window, or the best stand-in. */
    static Activity forView(View view) {
        if (view == null) return latest();
        Activity own = unwrap(view.getContext());
        IBinder token = view.getApplicationWindowToken();
        if (token == null) token = view.getWindowToken();
        if (token != null) {
            if (isUsable(own) && token == decorToken(own)) return own;
            synchronized (ALIVE) {
                for (int i = ALIVE.size() - 1; i >= 0; i--) {
                    Activity a = ALIVE.get(i).get();
                    if (isUsable(a) && token == decorToken(a)) return a;
                }
            }
        }
        if (isUsable(own)) return own;
        return latest();
    }

    /** A usable Activity for {@code context}: itself, or the latest resumed one. */
    static Activity forContext(Context context) {
        Activity own = unwrap(context);
        if (isUsable(own)) return own;
        return latest();
    }

    /** The last resumed Activity still alive. */
    static Activity latest() {
        Activity a = resumed.get();
        if (isUsable(a)) return a;
        synchronized (ALIVE) {
            for (int i = ALIVE.size() - 1; i >= 0; i--) {
                Activity candidate = ALIVE.get(i).get();
                if (hasWindow(candidate)) return candidate;
            }
        }
        return null;
    }

    private static IBinder decorToken(Activity activity) {
        Window window = activity.getWindow();
        return window == null ? null : window.getDecorView().getWindowToken();
    }

    private static void remember(Activity activity) {
        synchronized (ALIVE) {
            for (int i = ALIVE.size() - 1; i >= 0; i--) {
                Activity a = ALIVE.get(i).get();
                if (a == null) ALIVE.remove(i);
                else if (a == activity) return;
            }
            ALIVE.add(new WeakReference<>(activity));
        }
    }

    private static void forget(Activity activity) {
        synchronized (ALIVE) {
            for (int i = ALIVE.size() - 1; i >= 0; i--) {
                Activity a = ALIVE.get(i).get();
                if (a == null || a == activity) ALIVE.remove(i);
            }
        }
        if (resumed.get() == activity) resumed = new WeakReference<>(null);
    }
}

package com.feurstagram.extension;

import android.view.View;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Every window the app opens (activities, dialogs, sheets, popups, the story
 * viewer's modal activity), handed to whoever registered as it is recorded by the
 * window manager. WindowManagerGlobal's list of root views is swapped once for one
 * that reports each newcomer; windows already open are reported on registration.
 */
final class Windows {

    interface Listener {
        void onWindow(View root);
    }

    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static RootViews roots;

    private Windows() {}

    static synchronized void add(Listener listener) {
        if (listener == null) return;
        try {
            if (roots == null) roots = install();
            LISTENERS.add(listener);
            for (View view : new ArrayList<>(roots)) listener.onWindow(view);
        } catch (Throwable ignored) {
            // nothing depends on this beyond the feature asking
        }
    }

    @SuppressWarnings("unchecked")
    private static RootViews install() throws Exception {
        Class<?> global = Class.forName("android.view.WindowManagerGlobal");
        Object instance = global.getMethod("getInstance").invoke(null);
        Field views = global.getDeclaredField("mViews");
        views.setAccessible(true);
        Field lock = global.getDeclaredField("mLock");
        lock.setAccessible(true);
        synchronized (lock.get(instance)) {
            Object current = views.get(instance);
            if (current instanceof RootViews) return (RootViews) current;
            RootViews replacement = new RootViews((Collection<View>) current);
            views.set(instance, replacement);
            return replacement;
        }
    }

    private static void report(View view) {
        Listener[] listeners;
        synchronized (Windows.class) {
            listeners = LISTENERS.toArray(new Listener[0]);
        }
        for (Listener listener : listeners) {
            try {
                listener.onWindow(view);
            } catch (Throwable ignored) {
                // one feature failing must not stop a window from opening
            }
        }
    }

    private static final class RootViews extends ArrayList<View> {
        RootViews(Collection<View> existing) {
            super(existing);
        }

        @Override
        public boolean add(View view) {
            report(view);
            return super.add(view);
        }
    }
}

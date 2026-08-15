package com.feurstagram.extension;

/**
 * Popup suppression, invoked from Instagram's own Toast subclass (the rounded
 * pill) right before it reaches the window manager.
 *
 * Blocking a surface makes Instagram see a failed request, and it answers with a
 * popup ("Couldn't refresh feed", "Impossible d'actualiser le fil", ...) every
 * single time. Those are pure noise in a mod whose whole point is that the
 * surface stays empty, so they are dropped by default.
 *
 * Every popup goes, not just the error ones: telling them apart would mean
 * matching the text, and Instagram's user-facing strings are neither in the APK
 * resources nor exhaustively in its bundled language packs (those are ~1400-entry
 * bootstrap packs; the real ones are downloaded at runtime, per locale). Any
 * keyword list would therefore be a guess that silently misses whole languages,
 * so the choice is deliberately dumb and complete instead.
 */
public final class Toasts {

    private Toasts() {}

    /**
     * Hook: true drops the popup (Instagram's {@code show()} returns before
     * touching the window). Anything unexpected shows it, so a change in
     * Instagram's toast internals can never hide a working popup path.
     */
    public static boolean shouldSuppress() {
        try {
            return Config.arePopupsHidden();
        } catch (Throwable t) {
            return false;
        }
    }
}

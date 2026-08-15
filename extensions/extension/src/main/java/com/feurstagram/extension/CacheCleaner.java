package com.feurstagram.extension;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.widget.Toast;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Clears Instagram's media caches on a background thread, then kills every
 * process belonging to the app so it relaunches from a clean slate with the
 * newly chosen blocks applied. Auth/session state is deliberately preserved.
 */
public final class CacheCleaner implements Runnable {

    private final Context context;

    private CacheCleaner(Context context) {
        this.context = context;
    }

    /** Show a toast, then run the wipe + restart on a worker thread. */
    public static void clearAndRestart(Context context) {
        if (context == null) return;
        try {
            Toast.makeText(context, "Feurstagram: clearing cache...", Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
        new Thread(new CacheCleaner(context)).start();
    }

    @Override
    public void run() {
        try {
            wipeKnownVideoCaches(context);
            wipeKnownReelsArtifacts(context);

            deleteContents(context.getCacheDir());
            deleteContents(context.getCodeCacheDir());

            File ext = context.getExternalCacheDir();
            if (ext != null) deleteContents(ext);

            // filesDir holds pre-fetched media AND auth tokens, so only wipe
            // subdirectories whose name looks like a media/prefetch cache.
            wipeMediaCaches(context.getFilesDir());

            // Prefetched reels/stories survive in Room databases under databases/.
            // Without this they re-appear after restart even though the network
            // request that would refill them is blocked.
            wipeReelsDatabases(context);

            wipeKnownVideoCaches(context);
            wipeKnownReelsArtifacts(context);
        } catch (Throwable ignored) {
        }

        // Kill Instagram's other processes first — they hold prefetch state too —
        // but keep this one alive long enough to hand the restart to the relay.
        stopAppProcesses(context, true);

        if (startRestartRelay(context)) {
            // The relay kills this process from the outside and relaunches the app.
            // If it somehow never arrives, fall through rather than hang here.
            try {
                Thread.sleep(RELAY_TIMEOUT_MS);
            } catch (Throwable ignored) {
            }
        }

        stopAppProcesses(context, false);
        Process.killProcess(Process.myPid());
    }

    /** How long the relay gets to kill this process before we do it ourselves. */
    private static final long RELAY_TIMEOUT_MS = 5000L;

    /**
     * Hand the restart to {@link RestartActivity}, which the Restart relay patch
     * declares in a process of its own. Without it "clear cache and restart" only
     * ever did the first half: the app vanished and the system surfaced whatever
     * task sat behind it — on a device that also has stock Instagram installed,
     * confusingly the unpatched app.
     *
     * @return whether the relay was started (false falls back to the plain kill)
     */
    private static boolean startRestartRelay(Context context) {
        if (context == null) return false;
        try {
            context.startActivity(RestartActivity.intentFor(context, Process.myPid()));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // Reels video chunks live under cacheDir/filesDir ExoPlayer dirs.
    private static final String[] VIDEO_CACHE_PATHS = {
            "ExoPlayerCacheDir/videocache",
            "ExoPlayerCacheDir/videoprefetchcache",
            "ExoPlayerCacheDir/videocachemetadata",
            "videocache",
            "videoprefetchcache",
            "videocachemetadata",
            "ExoPlayerCacheDir",
    };

    private static final String[] REELS_ARTIFACT_PATHS = {
            "most_recent_reels_cache",
            "ig_pando_response_cache",
            "direct_background_prefetch_cache",
            "pending_reel_tray_seen_states",
            "pending_reel_seen_states",
            "pending_clips_seen_states",
            "pending_reel_quiz_responses",
            "pending_reel_slider_votes",
            "pending_reel_countdown_follow_requests",
            "ExoPlayerCacheDir/videoprefetchcache",
            "ExoPlayerCacheDir/videocachemetadata",
            "files/ExoPlayerCacheDir",
    };

    private static void wipeKnownVideoCaches(Context context) {
        wipeUnder(context.getCacheDir(), VIDEO_CACHE_PATHS);
        wipeUnder(context.getFilesDir(), VIDEO_CACHE_PATHS);
    }

    private static void wipeKnownReelsArtifacts(Context context) {
        wipeUnder(context.getCacheDir(), REELS_ARTIFACT_PATHS);
        wipeUnder(context.getFilesDir(), REELS_ARTIFACT_PATHS);
        File parent = context.getFilesDir() == null ? null : context.getFilesDir().getParentFile();
        wipeUnder(parent, REELS_ARTIFACT_PATHS);
    }

    /**
     * Room databases (in the app's databases/ dir, a sibling of files/ and cache/)
     * persist prefetched reel and story media across restarts. Instagram serves
     * those surfaces from these DBs without a network call, so the network block
     * never fires and already-prefetched content keeps showing. Wipe them — along
     * with their -wal/-shm/-journal companions, matched by name substring.
     */
    private static final String[] REELS_DB_MARKERS = {
            "delivery_media_room_db",        // delivery + reels_tray_delivery prefetch
            "user_reel_medias_room_db",      // reels media blobs
            "flash_media_",
            "clips_",
    };

    private static void wipeReelsDatabases(Context context) {
        if (context == null) return;
        try {
            File probe = context.getDatabasePath("delivery_media_room_db");
            File databasesDir = probe == null ? null : probe.getParentFile();
            if (databasesDir == null) return;
            File[] children = databasesDir.listFiles();
            if (children == null) return;
            for (File child : children) {
                if (child == null) continue;
                String name = child.getName().toLowerCase(Locale.ROOT);
                for (String marker : REELS_DB_MARKERS) {
                    if (name.contains(marker)) {
                        deleteRecursive(child);
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void wipeUnder(File root, String[] relativePaths) {
        if (root == null) return;
        for (String rel : relativePaths) {
            try {
                deleteRecursive(new File(root, rel));
            } catch (Throwable ignored) {
            }
        }
    }

    /** Delete the directory's contents but keep the directory itself. */
    private static void deleteContents(File dir) {
        if (dir == null) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            deleteRecursive(child);
        }
    }

    /** Wipe only filesDir subdirectories that look like media caches; keep auth. */
    private static void wipeMediaCaches(File dir) {
        if (dir == null) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.isDirectory()) continue; // loose files often hold session data
            String name = child.getName().toLowerCase(Locale.ROOT);
            if (looksLikeAuth(name)) continue;
            if (looksLikeMediaCache(name)) {
                deleteRecursive(child);
            }
        }
    }

    private static final String[] AUTH_MARKERS = {
            "auth", "session", "login", "token", "account", "cred",
            "cookie", "secure", "profile", "pref", "mqtt", "device", "user",
    };

    private static final String[] MEDIA_MARKERS = {
            "cache", "video", "media", "exo", "clip", "reel", "story",
            "prefetch", "thumb", "image", "feed", "blob", "temp", "tmp", "download",
    };

    private static boolean looksLikeAuth(String lower) {
        for (String marker : AUTH_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    private static boolean looksLikeMediaCache(String lower) {
        for (String marker : MEDIA_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        try {
            file.delete();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Best-effort force-close: kill every process belonging to this app.
     *
     * @param keepSelf spare this process (and, with it, the relay it is about to
     *                 start) so the restart has something left to run in
     */
    private static void stopAppProcesses(Context context, boolean keepSelf) {
        if (context == null) return;
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            String pkg = context.getPackageName();
            if (pkg == null) return;

            try {
                am.killBackgroundProcesses(pkg);
            } catch (Throwable ignored) {
            }

            int self = Process.myPid();
            String prefix = pkg + ":";
            List<ActivityManager.RunningAppProcessInfo> running = am.getRunningAppProcesses();
            if (running == null) return;
            for (ActivityManager.RunningAppProcessInfo info : running) {
                if (info == null || info.processName == null) continue;
                if (info.pid <= 0) continue;
                if (keepSelf && info.pid == self) continue;
                if (info.processName.equals(pkg) || info.processName.startsWith(prefix)) {
                    Process.killProcess(info.pid);
                }
            }
        } catch (Throwable ignored) {
        }
    }
}

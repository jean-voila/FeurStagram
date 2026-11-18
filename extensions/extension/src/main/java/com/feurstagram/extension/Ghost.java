package com.feurstagram.extension;

import java.net.URI;

/**
 * Ghost mode: three independent options, all off by default, under Privacy.
 *
 * <ul>
 *   <li><b>DM read receipts</b>: Instagram's "mark thread seen" mutation is never
 *   queued, so the other person does not get "Seen". The conversation stays unread
 *   on this phone too, since the same mutation is what marks it read.</li>
 *   <li><b>Typing indicator</b>: the composer's text watcher stops right after it
 *   records the text, before it tells the thread you are typing.</li>
 *   <li><b>Story views</b>: the batch that reports seen stories
 *   ({@code media/seen/?reel=…}) is cancelled like a network failure. The ring
 *   still turns grey here, Instagram's own local state; the owner never sees you.</li>
 * </ul>
 *
 * They are privacy choices, not distractions: the permanent lock does not touch
 * them, and they are independent of "Block telemetry".
 */
public final class Ghost {

    private Ghost() {}

    public static boolean hideDmSeen() {
        try {
            return Config.getBlocked("ghost_dm_seen", false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hideTyping() {
        try {
            return Config.getBlocked("ghost_typing", false);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean hideStoryViews() {
        return Config.getBlocked("ghost_story_views", false);
    }

    /** The request that reports which stories were watched. */
    static boolean isStorySeen(URI uri) {
        String path = uri.getPath();
        String query = uri.getRawQuery();
        return path != null && path.endsWith("/media/seen/") && query != null && query.contains("reel=");
    }
}

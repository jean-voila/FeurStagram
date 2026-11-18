package com.feurstagram.extension;

/**
 * "Comments" under Blocked surfaces (off by default), after piko's Disable comments.
 * Every hook leaves Instagram's value untouched while the toggle is off.
 *
 * <ul>
 *   <li>Posts: the post's action-bar state is built with comments disabled and no
 *   comment count, so the button and the "View all N comments" line go.</li>
 *   <li>Reels: the comment button state is read as Instagram's own "hidden" one.</li>
 *   <li>Stories: the reply bar's comment state, the self-story hint, the comment
 *   previews over a story and the viewer's comment button are turned off.</li>
 *   <li>Direct links and anything else that still asks: the comment list request
 *   ({@code /api/v1/media/<id>/comments/}) is cancelled like a network failure.</li>
 * </ul>
 */
public final class Comments {

    private Comments() {}

    public static boolean hidden() {
        try {
            return Config.isCommentsBlocked();
        } catch (Throwable t) {
            return false;
        }
    }

    /** A "shown" flag of a comment control: false while comments are blocked. */
    public static boolean shown(boolean shown) {
        return shown && !hidden();
    }

    /** "Is the comment button hidden": true while comments are blocked. */
    public static boolean hiddenOr(boolean hidden) {
        return hidden || hidden();
    }

    /** The request listing a media's comments. */
    static boolean isCommentList(String path) {
        return path != null && path.contains("/api/v1/media/") && path.endsWith("/comments/");
    }
}

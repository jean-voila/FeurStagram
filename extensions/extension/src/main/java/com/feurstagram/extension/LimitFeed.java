package com.feurstagram.extension;

import java.util.HashMap;
import java.util.Map;

/**
 * Rewrites the main-feed request's pagination header so the home feed returns
 * only posts from accounts you follow (the chronological "Following" feed)
 * instead of the recommended/ranked feed. Gated on the {@code limit_following_feed}
 * toggle in {@link Config}.
 *
 * Invoked from the feed-request constructor with the request's header map, just
 * before it is stored on the request object.
 */
public final class LimitFeed {

    private LimitFeed() {}

    private static final String PAGINATION_KEY = "pagination_source";

    public static Map<String, String> setFollowingHeader(Map<String, String> headers) {
        if (headers == null) return null;
        if (!Config.isFollowingFeedOnly() || Config.isFeedBlocked()) return headers;

        // Only rewrite the default ranked-feed fetch; leave other paginations
        // (load-more, etc.) untouched so scrolling still works.
        String current = headers.get(PAGINATION_KEY);
        if (current != null && !current.equals("feed_recs")) return headers;

        // The original map may be immutable, so copy before mutating.
        Map<String, String> patched = new HashMap<>(headers);
        patched.put(PAGINATION_KEY, "following");
        return patched;
    }
}

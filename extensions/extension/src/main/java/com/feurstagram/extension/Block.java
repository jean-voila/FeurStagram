package com.feurstagram.extension;

import android.util.Log;

import java.io.IOException;
import java.net.URI;

/** Content policy and parse-level filtering. Requests always use Instagram's transport. */
public final class Block {

    private Block() {}

    /**
     * Account/user recommendation surfaces ("Suggested for you", chaining, ...).
     * {@code /discover/surface_with_su/} is deliberately absent: the same response
     * carries a profile's mutual followers, and its "Mutual" tab never loads once
     * the suggestions in it are emptied.
     */
    private static final String[] SUGGESTED = {
            "/discover/ayml/",
            "/discover/sectioned_ayml/",
            "/discover/chaining/",
            "/discover/recommended_accounts_for_category/",
            "/discover/suggested_businesses/",
            "/discover/recs_from_friends_suggestions/",
            "/discover/recs_from_friends_user_info/",
            "/discover/fetch_suggestion_details/",
            "/discover/account_discovery/",
            "/discover/reshare_suggestions/",
            "/fbsearch/accounts_recs/",
            "/friendships/feed_favorites_suggestions/",
            "/friendships/share_to_friends_story_suggested_users/",
            "/direct_v2/search_friending_suggestions/",
            "/business/discovery/suggest_business/",
    };

    /**
     * Feed-item JSON type tokens for ad/promo units injected inline into the
     * timeline payload. Gated on the Ads toggle. {@code media_or_ad} (regular
     * posts) is deliberately absent — dropping it would empty the whole feed.
     */
    private static final String[] AD_FEED_UNITS = {
            "ad4ad",
            "intent_aware_ad_pivot",
            "stand_alone_multi_ad_pivot",
    };

    /**
     * Feed-item JSON type tokens for suggested / "netego" units injected inline
     * into the timeline payload. Gated on the Suggested toggle.
     */
    private static final String[] SUGGESTED_FEED_UNITS = {
            "clips_netego",
            "stories_netego",
            "bloks_netego",
            "in_feed_survey",
            "suggested_users",
            "suggested_top_accounts",
            "suggested_igd_channels",
    };

    /**
     * Garbage type token returned for a blocked unit. It matches no real case in
     * Instagram's feed-item parser, so the unit falls through to the parser's
     * "unknown FeedItem type" branch and is dropped without a crash.
     */
    private static final String INVALID_FEED_TYPE = "feurstagram_blocked";

    /** Ad-delivery surfaces injected into feed, stories, profile, DMs, explore, commerce. */
    private static final String[] ADS = {
            "/api/v1/ads/",
            // Story ads, injected between the stories of the people you follow. No
            // trailing slash: the family has a "_www" sibling.
            "/feed/injected_reels_media",
            "/feed/async_ads_ranking/",
            "/feed/contextual_multi_ads/",
            "/feed/shop_everything_feed_of_ads",
            "/feed/user_interests_contextual_feed_of_ads/",
            "/clips/ads_discover_sync_flow/",
            "/discover/chaining_experience_contextual_ads/",
            "/discover/chaining_experience_notification_ads/",
            "/direct_v2/ads_for_ctd_ads_thread_view/",
            "/direct_v2/should_show_ad_responses_tab/",
            "/profile_ads/get_profile_ads/",
            "/stories/stories_high_intent_discovery_ads/",
            "/stories/stories_intent_aware_ads/",
            "/commerce/product_collections/ads_collection_page/",
    };

    /**
     * Analytics sinks, cancelled while "Block telemetry" is on (the default). Only
     * the batches of client events and the ad-measurement script: login, two-factor,
     * integrity and security checks, configuration and every content request keep
     * going, and read receipts and story views are left to ghost mode.
     * Measured on 456: Pigeon/Falco event batches go to {@code <host>/pigeon_nest}
     * or {@code <host>/logging_client_events} (graph.instagram.com, graph.facebook.com).
     */
    private static final String[] TELEMETRY = {
            "/pigeon_nest",
            "/logging_client_events",
            // Which experiment flags the app read, uploaded as its own log.
            "/launcher/mobileconfiglogging/",
            // Impressions of the activity feed's rows.
            "/news/log/",
            // Meta's ad-measurement pixel, injected into pages of the in-app browser.
            "/fbevents.js",
    };

    static boolean isTelemetry(String path) {
        return path != null && containsAny(path, TELEMETRY);
    }

    /**
     * Called by Instagram's network layer as each request starts. Content requests
     * are never touched here (they are filtered once their response arrives); a
     * telemetry upload is cancelled with an IOException, which Instagram's uploader
     * treats as a network failure.
     */
    public static void onRequest(URI uri) throws IOException {
        if (uri == null) return;
        String path = uri.getPath();
        boolean telemetry = Config.isTelemetryBlocked() && isTelemetry(path);
        // Story views belong to ghost mode, never to the telemetry switch.
        boolean storySeen = !telemetry && Ghost.isStorySeen(uri) && Ghost.hideStoryViews();
        boolean comments = Comments.isCommentList(path) && Config.isCommentsBlocked();
        if (DebugBridge.isNetworkTraceEnabled()) {
            Log.i("FeurNet", (telemetry || storySeen || comments ? "cancel " : "request ") + uri.getHost() + " " + path);
        }
        if (comments) throw new IOException("Comments blocked by Feurstagram");
        if (telemetry) throw new IOException("Telemetry blocked by Feurstagram");
        if (storySeen) throw new IOException("Story view hidden by Feurstagram");
        if (isOpenedReel(uri)) openedReelStarted(uri);
    }

    // --- A reel opened on its own (a link, a share in a story or a DM) ------------

    /**
     * The reel viewer opened on one reel asks for that reel ({@code /clips/items/}
     * with its id) and, at the same time, for the reels to scroll to after it
     * ({@code /clips/discover/stream/}). With Reels blocked the second one comes back
     * empty, and after a cache wipe it can land first: the viewer then has nothing
     * at all to show and fails ("Something went wrong", issue #184), while a second
     * tap works from the cache. So an emptied stream waits for the opened reel's own
     * request to finish first, never longer than {@link #OPENED_REEL_WAIT_MS}.
     */
    private static final long OPENED_REEL_WAIT_MS = 2500L;
    /** How long a started request counts as in flight if its end is never seen. */
    private static final long OPENED_REEL_STALE_MS = 6000L;
    private static final java.util.Map<URI, Long> OPENED_REELS = new java.util.HashMap<>();

    static boolean isOpenedReel(URI uri) {
        String path = uri.getPath();
        String query = uri.getRawQuery();
        return path != null && path.contains("/clips/items/") && query != null && query.contains("clips_media_ids");
    }

    static boolean isReelChaining(URI uri) {
        return uri != null && uri.getPath() != null && uri.getPath().contains("/clips/discover/stream/");
    }

    private static void openedReelStarted(URI uri) {
        synchronized (OPENED_REELS) {
            OPENED_REELS.put(uri, System.currentTimeMillis());
        }
    }

    static void openedReelDone(URI uri) {
        synchronized (OPENED_REELS) {
            if (OPENED_REELS.remove(uri) != null) OPENED_REELS.notifyAll();
        }
    }

    /** Wait (bounded) until no opened-reel request is in flight. */
    static void awaitOpenedReels() {
        long deadline = System.currentTimeMillis() + OPENED_REEL_WAIT_MS;
        synchronized (OPENED_REELS) {
            while (true) {
                long now = System.currentTimeMillis();
                java.util.Iterator<Long> started = OPENED_REELS.values().iterator();
                while (started.hasNext()) {
                    if (now - started.next() > OPENED_REEL_STALE_MS) started.remove();
                }
                if (OPENED_REELS.isEmpty() || now >= deadline) return;
                try {
                    OPENED_REELS.wait(deadline - now);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Only content delivery is filtered; logging, seen, uploads and security are untouched. */
    static boolean hidesResponse(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getPath() == null) return false;
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (!host.equals("instagram.com") && !host.endsWith(".instagram.com")) return false;
        String path = uri.getPath();
        return (Config.isFeedBlocked() && path.contains("/feed/timeline/"))
                || (Config.isStoriesBlocked() && path.contains("/feed/reels_tray"))
                || (Config.isExploreBlocked() && path.contains("/discover/topical_explore"))
                || (Config.isReelsBlocked() && (path.contains("/clips/home/")
                    || path.contains("/clips/discover") || path.contains("/clips/get_blend_medias/")))
                || (Config.isSuggestedBlocked() && containsAny(path, SUGGESTED))
                || (Config.isAdsBlocked() && containsAny(path, ADS));
    }

    /**
     * Feed-item parse hook: invoked from Instagram's feed-item deserialiser with
     * each item's JSON type token. Returns an invalid token when that unit should
     * be hidden (so the parser skips it), or the token unchanged otherwise.
     */
    public static String replaceFeedItemType(String key) {
        if (key == null) return null;
        // A new unit starts: whatever media it wraps is parsed right after this.
        MEDIA_IS_AD.get()[0] = false;
        boolean drop = Config.isFeedBlocked()
                || (Config.isAdsBlocked() && equalsAny(key, AD_FEED_UNITS))
                || (Config.isSuggestedBlocked() && equalsAny(key, SUGGESTED_FEED_UNITS));
        // Debug builds only (see DebugBridge): shows every unit type the timeline
        // actually contains and whether it was dropped — the way to tell a patch
        // that no longer runs from one whose unit list is out of date.
        if (DebugBridge.isNetworkTraceEnabled()) {
            Log.i("FeurFeed", (drop ? "drop " : "keep ") + key);
        }
        return drop ? INVALID_FEED_TYPE : key;
    }

    /**
     * JSON fields only a sponsored media carries. A sponsored post reaches the
     * timeline in the same {@code media_or_ad} unit as an organic one, so the
     * unit type cannot tell them apart; these fields can. {@code injected} holds
     * the "Sponsored" label and is on every ad, including the "ad break" ones;
     * {@code dr_ad_type} (direct-response ads) backs it up should it be renamed.
     * Organic posts carry other ad-sounding fields ({@code should_request_ads},
     * {@code is_paid_partnership}), which is why the list is this narrow.
     */
    private static final String[] AD_MEDIA_FIELDS = {
            "injected",
            "dr_ad_type",
    };

    /**
     * Whether the media being parsed on this thread is sponsored. Reset by
     * {@link #replaceFeedItemType} as each feed unit starts, set by
     * {@link #onMediaField}, read by {@link #filterFeedMedia} once the unit's
     * media is built. Parsing is synchronous on one thread, so a ThreadLocal
     * keeps concurrent feed and non-feed parses apart.
     */
    private static final ThreadLocal<boolean[]> MEDIA_IS_AD = new ThreadLocal<boolean[]>() {
        @Override protected boolean[] initialValue() { return new boolean[1]; }
    };

    /**
     * Media parse hook: called with every JSON field name of every media Instagram
     * parses, carousel children included (a child of an ad is an ad too).
     */
    public static void onMediaField(String key) {
        if (key != null && equalsAny(key, AD_MEDIA_FIELDS)) MEDIA_IS_AD.get()[0] = true;
    }

    /**
     * Feed-unit hook: called with the media a feed unit wraps, once parsed.
     * Returns null for a sponsored one when ads are blocked, which leaves the
     * unit empty — the same state as an unknown unit type, which Instagram drops.
     */
    public static Object filterFeedMedia(Object media) {
        boolean drop = media != null && MEDIA_IS_AD.get()[0] && Config.isAdsBlocked();
        if (DebugBridge.isNetworkTraceEnabled()) {
            Log.i("FeurFeed", (drop ? "drop " : "keep ")
                    + (MEDIA_IS_AD.get()[0] ? "sponsored media" : "media"));
        }
        return drop ? null : media;
    }

    /**
     * Name of the story-tray model's getter for {@code reel_type}. Obfuscated and
     * renamed every release, so the patch rewrites this body with the name it
     * finds in the model's serializer.
     */
    static String trayEntryTypeGetter() {
        return "";
    }

    /**
     * Story-tray hook: called with every entry Instagram's tray parser builds.
     * Returns null for a suggested account ({@code reel_type} "suggested_user")
     * when suggestions are blocked; the tray loop already skips entries that
     * parse to null, so the suggestion never reaches the tray. These come inside
     * the tray response itself, so the network block cannot catch them.
     */
    public static Object filterTrayEntry(Object entry) {
        // Not gated on the Stories block: this parser builds every story model
        // (a profile's ring, search, highlights, links), not only the home tray's
        // entries. The tray itself is emptied at the response level, so dropping
        // here made every story "unavailable" once the tray was hidden (#180).
        if (entry == null || !Config.isSuggestedBlocked()) return entry;
        try {
            String getter = trayEntryTypeGetter();
            if (getter.isEmpty()) return entry;
            Object type = entry.getClass().getMethod(getter).invoke(entry);
            boolean drop = "suggested_user".equals(String.valueOf(type));
            if (DebugBridge.isNetworkTraceEnabled()) {
                Log.i("FeurTray", (drop ? "drop " : "keep ") + type);
            }
            return drop ? null : entry;
        } catch (Throwable t) {
            return entry; // never break the tray over a shape we did not expect
        }
    }

    private static boolean equalsAny(String value, String[] options) {
        for (String option : options) {
            if (option.equals(value)) return true;
        }
        return false;
    }

    private static boolean containsAny(String path, String[] needles) {
        for (String needle : needles) {
            if (path.contains(needle)) return true;
        }
        return false;
    }

}

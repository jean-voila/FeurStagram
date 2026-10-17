package com.feurstagram.extension;

import android.util.Log;

import java.io.IOException;
import java.net.URI;

/**
 * Network content blocking, invoked from Instagram's TigonServiceLayer just
 * after each request URI is materialised. Throwing an IOException there makes
 * Instagram treat the request as a network failure and the surface stays empty.
 *
 * Toggleable blocks are gated on {@link Config}; analytics and commerce
 * endpoints are always blocked.
 */
public final class Block {

    private Block() {}

    /** Account/user recommendation surfaces ("Suggested for you", chaining, ...). */
    private static final String[] SUGGESTED = {
            "/discover/ayml/",
            "/discover/sectioned_ayml/",
            "/discover/chaining/",
            "/discover/recommended_accounts_for_category/",
            "/discover/suggested_businesses/",
            "/discover/recs_from_friends_suggestions/",
            "/discover/recs_from_friends_user_info/",
            "/discover/surface_with_su/",
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

    /** Main hook: throw if this request should be blocked. */
    public static void throwIfBlocked(URI uri) throws IOException {
        if (uri == null) return;
        String path = uri.getPath();
        if (path == null) return;

        // Debug builds only (see DebugBridge): logs every path Instagram asks for,
        // which is how a surface that started arriving over a new endpoint gets
        // found. Always false in a release build.
        if (DebugBridge.isNetworkTraceEnabled()) Log.i("FeurNet", path);

        // --- Toggleable content blocks ---
        if (Config.isFeedBlocked() && path.contains("/feed/timeline/")) throw blocked();
        if (Config.isStoriesBlocked() && path.contains("/feed/reels_tray")) throw blocked();
        if (Config.isExploreBlocked() && path.contains("/discover/topical_explore")) throw blocked();
        if (Config.isReelsBlocked()
                && (path.contains("/clips/home/")
                || path.contains("/clips/discover")
                || path.contains("/clips/get_blend_medias/"))) {
            throw blocked();
        }
        if (Config.isSuggestedBlocked() && containsAny(path, SUGGESTED)) throw blocked();
        if (Config.isAdsBlocked() && containsAny(path, ADS)) throw blocked();

        // --- Always-blocked analytics / commerce ---
        // No trailing slash: the family has a "_www" sibling
        // (/feed/injected_reels_media_www/) that a slash-terminated match misses.
        if (path.contains("/feed/injected_reels_media")
                || path.contains("/logging/")
                || path.contains("/async_ads_privacy/")
                || path.contains("/async_critical_notices/")
                || isMediaSeen(path)
                || path.contains("/api/v1/fbupload/")
                || path.contains("/api/v1/stats/")
                || path.contains("/api/v1/commerce/")
                || path.contains("/api/v1/shopping/")
                || path.contains("/api/v1/sellable_items/")) {
            throw blocked();
        }
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
        boolean drop = (Config.isAdsBlocked() && equalsAny(key, AD_FEED_UNITS))
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

    private static boolean isMediaSeen(String path) {
        return path.contains("/api/v1/media/") && path.contains("/seen");
    }

    private static boolean containsAny(String path, String[] needles) {
        for (String needle : needles) {
            if (path.contains(needle)) return true;
        }
        return false;
    }

    private static IOException blocked() {
        return new IOException("Blocked by Feurstagram");
    }
}

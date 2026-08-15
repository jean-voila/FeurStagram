package com.feurstagram.extension;

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
        if (Config.isAdsBlocked() && equalsAny(key, AD_FEED_UNITS)) return INVALID_FEED_TYPE;
        if (Config.isSuggestedBlocked() && equalsAny(key, SUGGESTED_FEED_UNITS)) return INVALID_FEED_TYPE;
        return key;
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

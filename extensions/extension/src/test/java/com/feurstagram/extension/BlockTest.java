package com.feurstagram.extension;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.net.URI;

/**
 * Network and feed-parse blocking with the default toggles (no Application in a
 * unit test): feed, explore, reels, suggestions and ads blocked, stories shown.
 */
public class BlockTest {

    private static void assertBlocked(String path) {
        try {
            Block.throwIfBlocked(URI.create("https://i.instagram.com" + path));
            fail(path + " should be blocked");
        } catch (IOException expected) {
            // blocked
        }
    }

    private static void assertAllowed(String path) {
        try {
            Block.throwIfBlocked(URI.create("https://i.instagram.com" + path));
        } catch (IOException e) {
            fail(path + " should not be blocked");
        }
    }

    @Test
    public void defaultTogglesBlockTheDistractingSurfaces() {
        assertBlocked("/api/v1/feed/timeline/");
        assertBlocked("/api/v1/discover/topical_explore/");
        assertBlocked("/api/v1/clips/home/");
        assertBlocked("/api/v1/clips/discover/stream/");
        assertBlocked("/api/v1/discover/ayml/");
        assertBlocked("/api/v1/feed/async_ads_ranking/");
    }

    @Test
    public void analyticsAndCommerceAreAlwaysBlocked() {
        assertBlocked("/api/v1/logging/client_events/");
        assertBlocked("/api/v1/media/3456_789/seen/");
        assertBlocked("/api/v1/feed/injected_reels_media_www/");
        assertBlocked("/api/v1/commerce/products/");
    }

    @Test
    public void whatFeurstagramKeepsStillWorks() {
        assertAllowed("/api/v1/direct_v2/inbox/");
        assertAllowed("/api/v1/feed/reels_tray/"); // stories are shown by default
        assertAllowed("/api/v1/users/12345/info/");
        assertAllowed("/api/v1/fbsearch/topsearch_flat/");
        assertAllowed("/api/v1/news/inbox/");
    }

    @Test
    public void aMissingUriOrPathIsLeftAlone() throws IOException {
        Block.throwIfBlocked(null);
        Block.throwIfBlocked(URI.create("mailto:someone@example.com"));
    }

    @Test
    public void adAndSuggestedFeedUnitsAreDropped() {
        assertEquals("feurstagram_blocked", Block.replaceFeedItemType("ad4ad"));
        assertEquals("feurstagram_blocked", Block.replaceFeedItemType("clips_netego"));
        assertEquals("feurstagram_blocked", Block.replaceFeedItemType("suggested_users"));
        // Regular posts arrive as media_or_ad: dropping that would empty the feed.
        assertEquals("media_or_ad", Block.replaceFeedItemType("media_or_ad"));
        assertNull(Block.replaceFeedItemType(null));
    }

    @Test
    public void onlyMediaCarryingAnAdFieldIsDropped() {
        Object media = new Object();

        Block.replaceFeedItemType("media_or_ad");
        Block.onMediaField("caption");
        Block.onMediaField("is_paid_partnership"); // organic posts carry this one
        assertSame(media, Block.filterFeedMedia(media));

        Block.replaceFeedItemType("media_or_ad");
        Block.onMediaField("injected");
        assertNull(Block.filterFeedMedia(media));

        // The flag is per unit: the next unit starts clean.
        Block.replaceFeedItemType("media_or_ad");
        assertSame(media, Block.filterFeedMedia(media));
    }
}

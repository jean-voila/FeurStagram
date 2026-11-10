package com.feurstagram.extension;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * The permanent lock is a promise to the user: what it freezes must stay frozen,
 * and nothing else may be. These run without an Application, so every toggle
 * reads its default.
 */
public class ConfigTest {

    @Test
    public void lockCoversBlocksNavIconsAndFollowingFeedOnly() {
        assertTrue(Config.isLockable("block_feed"));
        assertTrue(Config.isLockable("block_notifications"));
        assertTrue(Config.isLockable("nav_show_direct"));
        assertTrue(Config.isLockable("limit_following_feed"));
        assertFalse(Config.isLockable("auto_update"));
        assertFalse(Config.isLockable("force_sdr"));
        assertFalse(Config.isLockable("quoicouview_mode"));
        assertFalse(Config.isLockable(null));
    }

    @Test
    public void hidesSurfaceFollowsEachFamilysPolarity() {
        assertTrue(Config.hidesSurface("block_reels", true));
        assertFalse(Config.hidesSurface("block_reels", false));
        assertTrue(Config.hidesSurface("limit_following_feed", true));
        assertTrue(Config.hidesSurface("nav_show_reels", false));
        assertFalse(Config.hidesSurface("nav_show_reels", true));
        assertFalse(Config.hidesSurface(null, true));
    }

    /**
     * Every surface the settings page offers must be in the lock's session
     * snapshot. One left out (block_notifications was) falls back to its live
     * value, so turning it on by mistake under the lock could not be undone,
     * unlike every other toggle.
     */
    @Test
    public void baselineCoversEverySurfaceTheSettingsPageOffers() throws Exception {
        Config.captureBaseline();
        Field baselineField = Config.class.getDeclaredField("sBaseline");
        baselineField.setAccessible(true);
        Map<?, ?> baseline = (Map<?, ?>) baselineField.get(null);

        Field keysField = Settings.class.getDeclaredField("SURFACE_KEYS");
        keysField.setAccessible(true);
        for (String key : (String[]) keysField.get(null)) {
            assertTrue(key + " should be lockable", Config.isLockable(key));
            assertTrue(key + " is missing from the lock baseline", baseline.containsKey(key));
        }
        assertTrue(baseline.containsKey("limit_following_feed"));
        for (String tab : new String[] {"feed_tab", "search_tab", "clips_tab", "creation_tab", "direct_tab", "profile_tab"}) {
            assertTrue(tab + " icon is missing from the lock baseline", baseline.containsKey(Config.navKeyForTab(tab)));
        }
    }

    @Test
    public void navKeysMatchTheTabs() {
        assertEquals("nav_show_home", Config.navKeyForTab("feed_tab"));
        assertEquals("nav_show_reels", Config.navKeyForTab("clips_tab"));
        assertNull(Config.navKeyForTab("news_tab"));
        assertFalse(Config.navDefault("nav_show_reels"));
        assertTrue(Config.navDefault("nav_show_direct"));
    }
}

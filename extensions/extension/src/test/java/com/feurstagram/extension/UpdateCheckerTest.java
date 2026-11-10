package com.feurstagram.extension;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class UpdateCheckerTest {

    /** The shape of GitHub's /releases/latest answer, trimmed to what is read. */
    private static final String RELEASE = "{"
            + "\"tag_name\":\"v454-0-0-54-77\","
            + "\"assets\":["
            + "{\"name\":\"feurstagram-454-0-0-54-77-clone.apk\",\"size\":162668494,"
            + "\"digest\":\"sha256:4b352008d795b5cd4805a3b4daec70bfde51df8c6377b7fe1c4a90e837fd74a7\","
            + "\"browser_download_url\":\"https://github.com/jean-voila/FeurStagram/releases/download/"
            + "v454-0-0-54-77/feurstagram-454-0-0-54-77-clone.apk\"},"
            + "{\"name\":\"feurstagram-454-0-0-54-77.apk\",\"size\":162181070,"
            + "\"digest\":\"sha256:354553FF5431766227BFDD7C932E95B651874D79BC43D9CBFF11C94E644533A5\","
            + "\"browser_download_url\":\"https://github.com/jean-voila/FeurStagram/releases/download/"
            + "v454-0-0-54-77/feurstagram-454-0-0-54-77.apk\"}"
            + "],"
            + "\"body\":\"## Fixed\\r\\n- \\\"Quoted\\\" \\u00e9 notes\""
            + "}";

    @Test
    public void normalizeTurnsATagIntoAVersionName() {
        assertEquals("454.0.0.54.77", UpdateChecker.normalize("v454-0-0-54-77"));
        assertEquals("425.0.0.47.61.1", UpdateChecker.normalize(" V425-0-0-47-61-1 "));
        assertEquals("", UpdateChecker.normalize(null));
    }

    @Test
    public void isNewerComparesEachComponentAsANumber() {
        assertTrue(UpdateChecker.isNewer("454.0.0.54.77", "453.0.0.57.79"));
        assertTrue(UpdateChecker.isNewer("454.0.0.54.77", "454.0.0.13.77"));
        assertTrue(UpdateChecker.isNewer("454.0.0.100.77", "454.0.0.54.77"));
        assertTrue(UpdateChecker.isNewer("425.0.0.47.61.1", "425.0.0.47.61"));
        assertFalse(UpdateChecker.isNewer("454.0.0.54.77", "454.0.0.54.77"));
        assertFalse(UpdateChecker.isNewer("453.0.0.57.79", "454.0.0.54.77"));
        assertTrue(UpdateChecker.isNewer("454.0.0.54.77", "0"));
    }

    @Test
    public void parsePicksThePlainApkForAClassicInstall() throws Exception {
        UpdateChecker.Release release = UpdateChecker.Release.parse(new JSONObject(RELEASE), false);
        assertEquals("v454-0-0-54-77", release.tag);
        assertTrue(release.apkUrl.endsWith("/feurstagram-454-0-0-54-77.apk"));
        assertEquals(162181070L, release.size);
        // GitHub's digest is accepted in either case and kept lowercase.
        assertEquals("354553ff5431766227bfdd7c932e95b651874d79bc43d9cbff11c94e644533a5", release.sha256);
    }

    @Test
    public void parsePicksTheCloneApkForACloneInstall() throws Exception {
        UpdateChecker.Release release = UpdateChecker.Release.parse(new JSONObject(RELEASE), true);
        assertTrue(release.apkUrl.endsWith("-clone.apk"));
        assertEquals(162668494L, release.size);
        assertEquals("4b352008d795b5cd4805a3b4daec70bfde51df8c6377b7fe1c4a90e837fd74a7", release.sha256);
    }

    @Test
    public void parseDecodesTheNotesAndDropsCarriageReturns() throws Exception {
        UpdateChecker.Release release = UpdateChecker.Release.parse(new JSONObject(RELEASE), false);
        assertEquals("## Fixed\n- \"Quoted\" \u00e9 notes", release.notes);
    }

    @Test
    public void parseWithoutAMatchingAssetHasNothingToDownload() throws Exception {
        JSONObject json = new JSONObject("{\"tag_name\":\"v1\",\"body\":null,\"assets\":["
                + "{\"browser_download_url\":\"https://example.com/notes.txt\",\"size\":3}]}");
        UpdateChecker.Release release = UpdateChecker.Release.parse(json, false);
        assertNull(release.apkUrl);
        assertNull(release.sha256);
        assertEquals(-1L, release.size);
        // JSON null is no notes, not the text "null".
        assertNull(release.notes);
    }

    @Test
    public void parseWithoutADigestSkipsTheCheck() throws Exception {
        JSONObject json = new JSONObject("{\"tag_name\":\"v1\",\"assets\":["
                + "{\"browser_download_url\":\"https://example.com/a.apk\",\"size\":3}]}");
        UpdateChecker.Release release = UpdateChecker.Release.parse(json, false);
        assertEquals("https://example.com/a.apk", release.apkUrl);
        assertNull(release.sha256);
    }

    @Test
    public void sha256AcceptsOnlyAFullSha256Digest() {
        assertNull(UpdateChecker.sha256(null));
        assertNull(UpdateChecker.sha256("md5:d41d8cd98f00b204e9800998ecf8427e"));
        assertNull(UpdateChecker.sha256("sha256:1234"));
        assertNull(UpdateChecker.sha256("sha256:zz52008d795b5cd4805a3b4daec70bfde51df8c6377b7fe1c4a90e837fd74a7"));
    }

    @Test
    public void releaseNotesTreatsBlankAsNone() throws Exception {
        assertNull(UpdateChecker.releaseNotes(new JSONObject("{\"body\":\"  \\n \"}")));
        assertNull(UpdateChecker.releaseNotes(new JSONObject("{}")));
        assertNull(UpdateChecker.releaseNotes(null));
    }

    @Test
    public void toHexMatchesTheDigestGitHubPublishes() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest("abc".getBytes(StandardCharsets.UTF_8));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                UpdateChecker.toHex(digest));
    }
}

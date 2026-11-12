package com.feurstagram.extension;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Incremental UTF-8 JSON redaction. Content containers become empty containers
 * of the same type; metadata and unrecognised fields retain their original bytes.
 * No response body is buffered: strings, escapes and nesting span transport chunks.
 */
final class JsonContentFilter {
    private final boolean adGraphQl;
    JsonContentFilter() { this(false); }
    JsonContentFilter(boolean adGraphQl) { this.adGraphQl = adGraphQl; }
    private final List<Boolean> objects = new ArrayList<>();
    private final List<String> containers = new ArrayList<>();
    private final StringBuilder key = new StringBuilder();
    private String field;
    private boolean expectKey, expectValue, string, keyString, escaped, longKey;
    private boolean started, passthrough;
    private int skipDepth;
    private boolean skipString, skipEscape, skipScalar;
    int removed;
    private final java.util.Set<String> debugKeys = new java.util.LinkedHashSet<>();
    String keySummary() { return debugKeys.toString(); }

    ByteBuffer filter(ByteBuffer source) {
        ByteBuffer input = source.duplicate();
        ByteArrayOutputStream output = new ByteArrayOutputStream(input.remaining());
        while (input.hasRemaining()) accept(input.get() & 255, output);
        return ByteBuffer.wrap(output.toByteArray());
    }

    private void accept(int c, ByteArrayOutputStream out) {
        if (passthrough) { out.write(c); return; }
        if (!started) {
            if (space(c)) { out.write(c); return; }
            started = true;
            // Only object-shaped API JSON. Binary, compressed and other payloads
            // keep their bytes; native errors and unsupported formats stay native.
            if (c != '{') { passthrough = true; out.write(c); return; }
        }
        if (skipScalar) {
            if (c != ',' && c != '}' && c != ']' && !space(c)) return;
            skipScalar = false;
            // The terminating delimiter still belongs to the surrounding object.
        }
        if (skipDepth > 0) {
            if (skipString) {
                if (skipEscape) skipEscape = false;
                else if (c == '\\') skipEscape = true;
                else if (c == '"') skipString = false;
            } else if (c == '"') skipString = true;
            else if (c == '{' || c == '[') skipDepth++;
            else if (c == '}' || c == ']') skipDepth--;
            return;
        }
        if (string) {
            out.write(c);
            if (escaped) {
                escaped = false;
                if (keyString) appendKey(c);
            } else if (c == '\\') {
                escaped = true;
                if (keyString) appendKey(c);
            } else if (c == '"') {
                string = false;
                if (keyString) {
                    field = longKey ? null : decodeKey(key.toString());
                    if (DebugBridge.isNetworkTraceEnabled() && objects.size() <= 2
                            && field != null && field.matches("[a-z_]{1,64}") && debugKeys.size() < 48) {
                        debugKeys.add(objects.size() == 1 ? field : containers.get(1) + "/" + field);
                    }
                    expectKey = false;
                }
            } else if (keyString) appendKey(c);
            return;
        }
        String containerField = null;
        if (expectValue && !space(c)) {
            expectValue = false;
            containerField = field;
            // Only root delivery lists, never arbitrary nested objects such as
            // challenge, checkpoint, login, error or account-status information.
            if (objects.size() == 1 && (contentField(field) || (adGraphQl && "data".equals(field)))
                    && (c == '[' || c == '{')) {
                out.write(c); out.write(c == '[' ? ']' : '}');
                skipDepth = 1;
                removed++;
                field = null;
                return;
            }
            // Empty lists must not trigger automatic load-more loops. Some
            // endpoints nest their cursor metadata in paging_info/paging.
            if (paginationScope() && paginationField(field) && (c == 't' || c == 'f')) {
                for (byte b : new byte[] {'f', 'a', 'l', 's', 'e'}) out.write(b);
                skipScalar = true;
                field = null;
                return;
            }
            field = null;
        }
        out.write(c);
        if (c == '"') {
            string = true;
            keyString = expectKey;
            key.setLength(0); longKey = false;
        } else if (c == '{' || c == '[') {
            objects.add(c == '{');
            containers.add(containerField);
            expectKey = c == '{';
        } else if (c == '}' || c == ']') {
            if (!objects.isEmpty()) {
                objects.remove(objects.size() - 1);
                containers.remove(containers.size() - 1);
            }
            expectKey = false; field = null;
        } else if (c == ':') {
            expectValue = true;
        } else if (c == ',') {
            expectKey = !objects.isEmpty() && objects.get(objects.size() - 1);
            field = null;
        }
    }

    private void appendKey(int c) {
        if (key.length() < 128) key.append((char) c);
        else longKey = true;
    }

    private static String decodeKey(String key) {
        if (key.indexOf('\\') < 0) return key;
        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '\\' && ++i < key.length()) {
                c = key.charAt(i);
                if (c == 'u' && i + 4 < key.length()) {
                    try { c = (char) Integer.parseInt(key.substring(i + 1, i + 5), 16); }
                    catch (NumberFormatException e) { return key; }
                    i += 4;
                }
            }
            decoded.append(c);
        }
        return decoded.toString();
    }

    private static boolean contentField(String name) {
        if (name == null) return false;
        switch (name) {
            case "feed_items": case "items": case "items_with_ads": case "tray": case "reels":
            case "reels_media": case "medias": case "media": case "sections":
            case "sectional_items": case "users": case "suggested_users":
            case "suggestions": case "suggested_accounts": case "ranked_items":
            case "results": case "ads": case "ad_items": case "edges":
            case "blend_items": return true;
            default: return false;
        }
    }

    private static boolean paginationField(String name) {
        return "more_available".equals(name) || "has_more".equals(name)
                || "has_next_page".equals(name) || "is_more_available".equals(name);
    }

    private boolean paginationScope() {
        if (objects.size() == 1) return true;
        if (objects.size() != 2) return false;
        String parent = containers.get(1);
        return "paging_info".equals(parent) || "paging".equals(parent) || "page_info".equals(parent);
    }

    private static boolean space(int c) {
        return c == ' ' || c == '\n' || c == '\r' || c == '\t';
    }
}

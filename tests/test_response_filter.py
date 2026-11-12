#!/usr/bin/env python3
"""Exercise the shipped Java filter against Python's JSON parser; no SDK needed."""
import base64
import copy
import json
import os
from pathlib import Path
import random
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
CONTENT = set("feed_items items items_with_ads tray reels reels_media medias media sections sectional_items users "
              "suggested_users suggestions suggested_accounts ranked_items results ads ad_items edges blend_items".split())
PAGING = set("more_available has_more has_next_page is_more_available".split())

HARNESS = r'''
package com.feurstagram.extension;
import java.io.*;
import java.net.URI;
import java.nio.*;
import java.util.*;
public class Harness {
    static class Request { URI uri; Request(String url) { uri = URI.create(url); } }
    static class Response { int status; Response(int value) { status = value; } }
    static byte[] data(String text) throws Exception { return text.getBytes("UTF-8"); }
    public static void main(String[] args) throws Exception {
        if (args.length > 0) { lifecycle(); return; }
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = reader.readLine()) != null) {
            String[] parts = line.split(" ", 2);
            int size = Integer.parseInt(parts[0]);
            byte[] bytes = Base64.getDecoder().decode(parts[1]);
            JsonContentFilter filter = new JsonContentFilter();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int offset = 0; offset < bytes.length; offset += size) {
                int length = Math.min(size, bytes.length - offset);
                ByteBuffer chunk = ByteBuffer.allocateDirect(length + 4);
                chunk.position(2); chunk.put(bytes, offset, length); chunk.flip(); chunk.position(2);
                ByteBuffer source = chunk.asReadOnlyBuffer();
                int position = source.position(), limit = source.limit();
                ByteBuffer result = filter.filter(source);
                if (source.position() != position || source.limit() != limit) throw new AssertionError("source changed");
                while (result.hasRemaining()) out.write(result.get());
            }
            System.out.println(Base64.getEncoder().encodeToString(out.toByteArray()));
        }
    }
    static void lifecycle() throws Exception {
        String content = "{\"feed_items\":[{\"secret\":1}],\"more_available\":true,\"status\":\"ok\"}";
        for (int status : new int[]{200, 400, 401, 403, 429, 500}) {
            Object dispatcher = new Object();
            Request request = new Request("https://i.instagram.com/api/v1/feed/timeline/");
            ResponseFilter.onResponse(dispatcher, new Response(status), request);
            ByteBuffer body = ByteBuffer.wrap(data(content));
            ByteBuffer result = ResponseFilter.onBody(dispatcher, request, body);
            if (status == 200 ? result == body : result != body) throw new AssertionError("HTTP policy " + status);
            ResponseFilter.onComplete(dispatcher);
            if (ResponseFilter.onBody(dispatcher, request, body) != body) throw new AssertionError("state leaked");
        }
        String[] allowed = {"https://i.instagram.com/api/v1/logging/client_event/",
            "https://i.instagram.com/api/v1/media/123/seen/", "https://i.instagram.com/api/v1/fbupload/",
            "https://i.instagram.com/api/v1/async_critical_notices/", "https://i.instagram.com/api/v1/commerce/",
            "https://example.com/api/v1/feed/timeline/", "https://instagram.com.evil.test/api/v1/feed/timeline/"};
        for (String url : allowed) if (Block.hidesResponse(URI.create(url))) throw new AssertionError(url);
        Config.block = false;
        if (Block.hidesResponse(URI.create("https://i.instagram.com/api/v1/feed/timeline/"))) throw new AssertionError("toggle off");
        Config.block = true;
        Object ads = new Object();
        Request adRequest = new Request("https://i.instagram.com/api/v1/ads/graphql/");
        ResponseFilter.onResponse(ads, new Response(200), adRequest);
        ByteBuffer adBody = ByteBuffer.wrap(data("{\"data\":{\"ads\":[1]},\"errors\":[{\"message\":\"kept\"}]}"));
        ByteBuffer adResult = ResponseFilter.onBody(ads, adRequest, adBody);
        byte[] adBytes = new byte[adResult.remaining()]; adResult.get(adBytes);
        if (!new String(adBytes, "UTF-8").equals("{\"data\":{},\"errors\":[{\"message\":\"kept\"}]}"))
            throw new AssertionError("GraphQL ad data/errors");
        ResponseFilter.onComplete(ads);
        Map<String,String> ranked = Collections.singletonMap("pagination_source", "feed_recs");
        if (LimitFeed.setFollowingHeader(ranked) != ranked) throw new AssertionError("hidden feed header changed");
        Config.block = false;
        if (!"following".equals(LimitFeed.setFollowingHeader(ranked).get("pagination_source")))
            throw new AssertionError("visible following header");
        Config.block = true;
        Object a = new Object(), b = new Object();
        Request req = new Request("https://i.instagram.com/api/v1/feed/timeline/");
        ResponseFilter.onResponse(a, new Response(200), req);
        ResponseFilter.onResponse(b, new Response(403), req);
        ByteBuffer first = ByteBuffer.wrap(data("{\"items\":[{\"x\":"));
        ResponseFilter.onBody(a, req, first);
        if (ResponseFilter.onBody(b, req, first) != first) throw new AssertionError("cross-request state");
        ResponseFilter.onError(a);
        if (ResponseFilter.onBody(a, req, first) != first) throw new AssertionError("error state leaked");
        java.lang.reflect.Field field = ResponseFilter.class.getDeclaredField("RESPONSES");
        field.setAccessible(true);
        ResponseFilter.onComplete(b);
        if (!((Map<?,?>) field.get(null)).isEmpty()) throw new AssertionError("active states remain");
        System.out.println("HTTP errors, toggles, hosts, telemetry, concurrency and cleanup: OK");
    }
}
'''


class ResponseFilterTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.build = Path(cls.tmp.name)
        src = ROOT / "extensions/extension/src/main/java/com/feurstagram/extension"
        for name in ("JsonContentFilter.java", "Block.java", "ResponseFilter.java", "LimitFeed.java"):
            text = (src / name).read_text()
            # Stand in for the two direct field-read bridges resolved by Morphe.
            text = text.replace("static URI requestUri(Object request) { return null; }",
                                "static URI requestUri(Object request) { return ((Harness.Request)request).uri; }")
            text = text.replace("static int responseStatus(Object response) { return 0; }",
                                "static int responseStatus(Object response) { return ((Harness.Response)response).status; }")
            (cls.build / name).write_text(text)
        (cls.build / "Harness.java").write_text(HARNESS)
        (cls.build / "Config.java").write_text("package com.feurstagram.extension; class Config { static boolean block=true;"
            + "static boolean isFollowingFeedOnly() { return true; }"
            + "".join(f"static boolean is{x}Blocked() {{ return block; }}" for x in
                      ("Feed", "Stories", "Explore", "Reels", "Suggested", "Ads")) + "}")
        (cls.build / "DebugBridge.java").write_text("package com.feurstagram.extension; class DebugBridge {"
            "static boolean isNetworkTraceEnabled() { return false; }"
            "static void recordResponse(String p,int s,long r,long d,int n) { }}")
        (cls.build / "Log.java").write_text("package android.util; public class Log {"
            "public static int i(String tag,String text) { return 0; }}")
        home = Path(os.environ.get("JAVA_HOME", "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home"))
        cls.java = str(home / "bin/java") if (home / "bin/java").exists() else shutil.which("java")
        javac = str(home / "bin/javac") if (home / "bin/javac").exists() else shutil.which("javac")
        subprocess.run([javac, "-d", str(cls.build), *map(str, cls.build.glob("*.java"))], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def filter_many(self, inputs):
        encoded = "".join(f"{size} {base64.b64encode(raw).decode()}\n" for size, raw in inputs)
        result = subprocess.run([self.java, "-cp", str(self.build), "com.feurstagram.extension.Harness"],
                                input=encoded, text=True, capture_output=True, check=True)
        return [base64.b64decode(line) for line in result.stdout.splitlines()]

    def test_chunk_boundaries_and_json_oracle(self):
        rng = random.Random(455)
        cases = [
            {"feed_items": [{"caption": 'é😃 \\" } ]', "items": [1, 2]}], "status": "ok",
             "more_available": True, "paging_info": {"more_available": True, "max_id": "123"}},
            {"tray": [], "items": None, "reels": {"123": {"items": [1]}}, "num_results": 12},
            {"challenge": {"url": "/challenge/", "items": [1], "more_available": True},
             "status": "fail", "message": "checkpoint_required", "error_type": "checkpoint"},
        ]
        for _ in range(200):
            case = {"metadata": {"items": [rng.randrange(100)], "more_available": True},
                    "status": "ok", "unicode": "été 🦆", "text": 'a\\b\"[}:'}
            for key in rng.sample(sorted(CONTENT), 5):
                case[key] = rng.choice([[{"a": [1, {"x": '\\"}'}]}], {"a": [1, 2]}, None, "unchanged", 5])
            case[rng.choice(sorted(PAGING))] = True
            cases.append(case)
        inputs, expected = [], []
        for case in cases:
            want = copy.deepcopy(case)
            for key, value in case.items():
                if key in CONTENT and isinstance(value, (list, dict)): want[key] = type(value)()
                if key in PAGING and isinstance(value, bool): want[key] = False
                if key in ("paging_info", "paging", "page_info") and isinstance(value, dict):
                    for flag in PAGING:
                        if isinstance(value.get(flag), bool): want[key][flag] = False
            for size in (1, 2, 3, 7, 31, 4096):
                inputs.append((size, json.dumps(case, ensure_ascii=False).encode()))
                expected.append(want)
        actual = self.filter_many(inputs)
        self.assertEqual(len(actual), len(expected))
        for result, want in zip(actual, expected): self.assertEqual(json.loads(result), want)

    def test_escaped_keys_and_unchanged_unknown_fields(self):
        raw = b'{"it\\u0065ms":[{"nested":["\\\"}"]}],"safe":{"items":[1]},"status":"ok"}'
        self.assertEqual(json.loads(self.filter_many([(1, raw)])[0]),
                         {"items": [], "safe": {"items": [1]}, "status": "ok"})
        payloads = [b' \n{"status":"fail","message":"login_required","challenge":{"url":"/c/"}}',
                    b'{"data":{"items":[1]},"extensions":{"trace":"abc"}}', b'\x1f\x8b\x08binary', b'[1,2]']
        self.assertEqual(self.filter_many([(1, raw) for raw in payloads]), payloads)

    def test_streamed_documents(self):
        raw = (b'{"items":[1],"items_with_ads":[{"media":{"id":"x"}}],"status":"ok"}\n'
               b'{"items":[2],"paging_info":{"more_available":true},"status":"ok"}')
        for result in self.filter_many([(n, raw) for n in (1, 2, 17, 4096)]):
            first, second = map(json.loads, result.splitlines())
            self.assertEqual(first, {"items": [], "items_with_ads": [], "status": "ok"})
            self.assertEqual(second, {"items": [], "paging_info": {"more_available": False}, "status": "ok"})

    def test_response_lifecycle(self):
        subprocess.run([self.java, "-cp", str(self.build), "com.feurstagram.extension.Harness", "lifecycle"], check=True)


if __name__ == "__main__":
    unittest.main()

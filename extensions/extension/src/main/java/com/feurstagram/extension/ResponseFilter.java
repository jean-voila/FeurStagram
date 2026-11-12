package com.feurstagram.extension;

import android.util.Log;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Filters delivery JSON after Tigon receives it, without intercepting transport. */
public final class ResponseFilter {
    private ResponseFilter() {}

    // A dispatcher belongs to one request, including its ordered body callbacks.
    // Weak keys also release state if cancellation never dispatches a terminal event.
    private static final Map<Object, State> RESPONSES =
            Collections.synchronizedMap(new WeakHashMap<Object, State>());

    // Replaced with direct, version-resolved field reads by the patch.
    static URI requestUri(Object request) { return null; }
    static int responseStatus(Object response) { return 0; }

    public static void onResponse(Object dispatcher, Object response, Object request) {
        URI uri = requestUri(request);
        int status = responseStatus(response);
        boolean hide = status >= 200 && status < 300 && Block.hidesResponse(uri);
        if (hide || DebugBridge.isNetworkTraceEnabled()) {
            RESPONSES.put(dispatcher, new State(uri, status, hide));
        } else {
            RESPONSES.remove(dispatcher);
        }
    }

    public static ByteBuffer onBody(Object dispatcher, Object request, ByteBuffer body) {
        State state = RESPONSES.get(dispatcher);
        if (state == null || body == null) return body;
        state.received += body.remaining();
        ByteBuffer delivered = state.filter == null ? body : state.filter.filter(body);
        state.delivered += delivered.remaining();
        return delivered;
    }

    public static void onComplete(Object dispatcher) { finish(dispatcher, "complete"); }
    public static void onError(Object dispatcher) { finish(dispatcher, "error"); }

    private static void finish(Object dispatcher, String event) {
        State state = RESPONSES.remove(dispatcher);
        if (state != null && "complete".equals(event)) {
            DebugBridge.recordResponse(state.path, state.status, state.received, state.delivered,
                    state.filter == null ? 0 : state.filter.removed);
        }
        if (state != null && DebugBridge.isNetworkTraceEnabled()) {
            Log.i("FeurResponse", event + " http=" + state.status + " received=" + state.received
                    + " delivered=" + state.delivered + " removed="
                    + (state.filter == null ? 0 : state.filter.removed) + " " + state.path
                    + (state.filter == null ? "" : " keys=" + state.filter.keySummary()));
        }
    }

    private static final class State {
        final String path;
        final int status;
        final JsonContentFilter filter;
        long received, delivered;
        State(URI uri, int status, boolean hide) {
            path = uri == null ? "" : uri.getPath();
            this.status = status;
            filter = hide ? new JsonContentFilter(path.contains("/ads/graphql")) : null;
        }
    }
}

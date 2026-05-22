/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.java.server.vertx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import software.amazon.smithy.java.http.api.ModifiableHttpHeaders;

/**
 * In-memory mutable header buffer used as the response side of an
 * {@link software.amazon.smithy.java.server.core.HttpJob}. We don't
 * write directly into Vert.x's
 * {@link io.vertx.core.http.HttpServerResponse} headers because the
 * orchestrator may set status code, content-type, and body well before
 * the response is actually flushed; we copy across at flush time.
 */
final class VertxResponseHeaders implements ModifiableHttpHeaders {

    private final Map<String, List<String>> headers = new LinkedHashMap<>();

    @Override
    public void addHeader(String name, String value) {
        String key = name.toLowerCase(Locale.ROOT);
        headers.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }

    @Override
    public void addHeader(String name, List<String> values) {
        String key = name.toLowerCase(Locale.ROOT);
        headers.computeIfAbsent(key, k -> new ArrayList<>()).addAll(values);
    }

    @Override
    public void setHeader(String name, String value) {
        String key = name.toLowerCase(Locale.ROOT);
        List<String> singleton = new ArrayList<>(1);
        singleton.add(value);
        headers.put(key, singleton);
    }

    @Override
    public void setHeader(String name, List<String> values) {
        String key = name.toLowerCase(Locale.ROOT);
        headers.put(key, new ArrayList<>(values));
    }

    @Override
    public void removeHeader(String name) {
        headers.remove(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public void clear() {
        headers.clear();
    }

    @Override
    public List<String> allValues(String name) {
        return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
    }

    @Override
    public int size() {
        int total = 0;
        for (var v : headers.values()) {
            total += v.size();
        }
        return total;
    }

    @Override
    public Map<String, List<String>> map() {
        Map<String, List<String>> snap = new LinkedHashMap<>(headers.size());
        for (var e : headers.entrySet()) {
            snap.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return Collections.unmodifiableMap(snap);
    }
}

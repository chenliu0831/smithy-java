/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.java.protocoltests.harness;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import software.amazon.smithy.java.http.api.HeaderName;
import software.amazon.smithy.java.http.api.HttpHeaders;
import software.amazon.smithy.java.http.api.HttpRequest;
import software.amazon.smithy.java.http.api.HttpResponse;
import software.amazon.smithy.java.io.datastream.DataStream;

final class ServerTestClient {
    private static final ConcurrentHashMap<URI, ServerTestClient> CLIENTS = new ConcurrentHashMap<>();

    /**
     * Toggle for per-request wire instrumentation. When enabled, every
     * request (and response status) is printed to stderr so a CI log
     * captures exactly what the harness sent the host under test —
     * including method, full URI, body length, and headers (with
     * sensitive values redacted). Off by default to avoid drowning
     * the ~350-test protocol-compliance run; flip on per Gradle task
     * via {@code -Dsmithy.protocoltest.trace=true}.
     *
     * <p>Stderr rather than {@link InternalLogger} because the latter
     * goes through JUL → Gradle's test-output filter, which has
     * historically dropped events at info/debug levels depending on
     * the consumer's logging.properties. Stderr always survives in
     * Gradle's test STANDARD_ERROR stream.
     */
    private static final boolean TRACE = Boolean.getBoolean("smithy.protocoltest.trace");

    private final URI endpoint;
    private final HttpClient httpClient;

    private ServerTestClient(URI endpoint) {
        this.endpoint = endpoint;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    }

    public static ServerTestClient get(URI endpoint) {
        return CLIENTS.computeIfAbsent(endpoint, ServerTestClient::new);
    }

    HttpResponse sendRequest(HttpRequest request) {

        var bodyPublisher = java.net.http.HttpRequest.BodyPublishers.fromPublisher(request.body());

        java.net.http.HttpRequest.Builder httpRequestBuilder = java.net.http.HttpRequest.newBuilder()
                .version(switch (request.httpVersion()) {
                    case HTTP_1_1 -> HttpClient.Version.HTTP_1_1;
                    case HTTP_2 -> HttpClient.Version.HTTP_2;
                    default -> throw new UnsupportedOperationException(request.httpVersion() + " is not supported");
                })
                .method(request.method(), bodyPublisher)
                .uri(request.uri().toURI());

        request.headers().forEachEntry(httpRequestBuilder, (b, name, value) -> {
            // Header names in HttpHeaders from Smithy are always canonicalized, so check by reference
            if (!name.equals(HeaderName.CONTENT_LENGTH.name())) {
                b.header(name, value);
            }
        });

        java.net.http.HttpRequest jdkRequest = httpRequestBuilder.build();
        traceRequest(request, jdkRequest);

        try {
            var response = httpClient.send(
                    jdkRequest,
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            traceResponse(response);
            return HttpResponse.create()
                    .setStatusCode(response.statusCode())
                    .setBody(DataStream.ofBytes(response.body()))
                    .setHeaders(HttpHeaders.of(response.headers().map()))
                    .toUnmodifiable();

        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void traceRequest(HttpRequest smithyRequest, java.net.http.HttpRequest jdkRequest) {
        if (!TRACE) {
            return;
        }
        var path = jdkRequest.uri().getRawPath();
        var query = jdkRequest.uri().getRawQuery();
        var pathAndQuery = (path == null || path.isEmpty() ? "/" : path) + (query == null ? "" : "?" + query);
        var headers = new java.util.TreeMap<String, String>();
        jdkRequest.headers().map().forEach((k, vs) -> headers.put(k.toLowerCase(java.util.Locale.ROOT),
                String.join(",", vs)));
        System.err.println("[harness-trace] -> "
                + jdkRequest.method() + " " + pathAndQuery
                + " body=" + smithyRequest.contentLength(0L) + "B"
                + " headers=" + headers);
    }

    private static void traceResponse(java.net.http.HttpResponse<byte[]> response) {
        if (!TRACE) {
            return;
        }
        var headers = new java.util.TreeMap<String, String>();
        response.headers().map().forEach((k, vs) -> headers.put(k.toLowerCase(java.util.Locale.ROOT),
                String.join(",", vs)));
        System.err.println("[harness-trace] <- "
                + response.statusCode()
                + " body=" + (response.body() == null ? 0 : response.body().length) + "B"
                + " headers=" + headers);
    }
}

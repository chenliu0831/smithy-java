/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.java.server.vertx;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Smoke test for {@link VertxBridgeServiceHost}: boot it with the
 * shared menu fixture, hit one operation, confirm a 200 response.
 *
 * <p>Verifies (a) the test-fixtures classpath wiring, (b) the host's
 * start/stop lifecycle, (c) end-to-end request flow through the
 * bridge under the host's Vert.x server. Does not exercise protocol
 * compliance — that is the protocol-test-harness's job, exercised by
 * Slice 3 of ADR-0007 Phase 1.
 */
class VertxBridgeServiceHostTest {

    private final VertxBridgeServiceHost host = new VertxBridgeServiceHost();

    @AfterEach
    void tearDown() {
        host.stop();
    }

    @Test
    void hostsServiceAndAnswersRequests() throws Exception {
        URI endpoint = host.start(MenuFixture.menuService());

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(endpoint + "/menu"))
                        .GET()
                        .timeout(Duration.ofSeconds(5))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void nameIsStable() {
        assertThat(host.name()).isEqualTo("vertx-bridge");
    }

    @Test
    void stopIsIdempotent() {
        host.start(MenuFixture.menuService());
        host.stop();
        host.stop(); // must not throw
    }
}

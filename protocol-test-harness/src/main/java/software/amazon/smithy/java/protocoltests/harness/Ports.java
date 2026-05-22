/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.java.protocoltests.harness;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * Free-port allocation helper for {@link ServiceHost} implementations.
 * Picks an ephemeral port by opening and immediately closing a
 * {@link ServerSocket}. The TOCTOU window between close and the host
 * binding is unavoidable but small in practice.
 */
public final class Ports {

    private Ports() {}

    /** Return a port that was free at the moment of this call. */
    public static int free() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

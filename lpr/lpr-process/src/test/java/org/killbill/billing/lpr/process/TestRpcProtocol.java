/*
 * Copyright 2020-2026 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.lpr.process;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class TestRpcProtocol {
    @Test(groups = "fast")
    public void testExactBudgetAndRoundTrip() throws Exception {
        final var frame = new RpcFrame(7, RpcFrame.REQUEST, List.of("😀", "中", ""));
        final byte[] bytes = frame.encode(1024);
        assertEquals(RpcFrame.decode(bytes, bytes.length), frame);
        assertEquals(frame.encode(bytes.length), bytes);
        assertThrows(IllegalArgumentException.class, () -> frame.encode(bytes.length - 1));
        assertThrows(IOException.class, () -> RpcFrame.decode(bytes, bytes.length - 1));
        assertThrows(IllegalArgumentException.class, () -> new RpcFrame(1, RpcFrame.REQUEST, java.util.Collections.nCopies(257, "")).encode(2048));
    }

    @Test(groups = "fast")
    public void testVersionStatusCountAndTruncationAreRejected() throws Exception {
        final byte[] bytes = new RpcFrame(7, RpcFrame.SUCCESS, List.of("a")).encode(1024);
        for (final int offset : List.of(0, 4, 16, 17, 21)) {
            final byte[] invalid = bytes.clone();
            invalid[offset] = (byte) 0x7f;
            assertThrows(IOException.class, () -> RpcFrame.decode(invalid, 1024));
        }
        assertThrows(IOException.class, () -> RpcFrame.decode(Arrays.copyOf(bytes, bytes.length - 1), 1024));
        assertThrows(IOException.class, () -> RpcFrame.decode(Arrays.copyOf(bytes, bytes.length + 1), 1024));
        final byte[] malformed = bytes.clone();
        malformed[malformed.length - 1] = (byte) 0x80;
        assertThrows(IOException.class, () -> RpcFrame.decode(malformed, 1024));
    }

    @Test(groups = "fast")
    public void testResponseCorrelationAndStatus() throws Exception {
        assertThrows(IOException.class, () -> ProcessPluginClient.validateResponse(1, new RpcFrame(2, RpcFrame.SUCCESS, List.of())));
        assertThrows(IOException.class, () -> ProcessPluginClient.validateResponse(1, new RpcFrame(1, RpcFrame.REQUEST, List.of())));
        ProcessPluginClient.validateResponse(1, new RpcFrame(1, RpcFrame.SUCCESS, List.of()));
        ProcessPluginClient.validateResponse(1, new RpcFrame(1, RpcFrame.FAILURE, List.of()));
    }

    @Test(groups = "fast")
    public void testStartupIdentityChallengeAndPid() throws Exception {
        final var descriptor = org.killbill.billing.lpr.spi.PluginDescriptor.of("fixture", "1.0.0", "example.Entry");
        final Process process = new ProcessBuilder(TestProcessPluginClient.JAVA.toString(), "-version")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        final byte[] challenge = new byte[32];
        final var fields = List.of(java.util.HexFormat.of().formatHex(challenge), "fixture", "1.0.0", Long.toString(process.pid()));
        try {
            ProcessPluginLauncher.validateHello(new RpcFrame(0, RpcFrame.HELLO, fields), process, descriptor, challenge);
            for (int index = 0; index < 4; index++) {
                final var invalid = new java.util.ArrayList<>(fields);
                invalid.set(index, "wrong");
                assertThrows(IOException.class, () -> ProcessPluginLauncher.validateHello(new RpcFrame(0, RpcFrame.HELLO, invalid), process, descriptor, challenge));
            }
            assertThrows(IOException.class, () -> ProcessPluginLauncher.validateHello(new RpcFrame(1, RpcFrame.HELLO, fields), process, descriptor, challenge));
        } finally { process.destroyForcibly(); process.waitFor(); }
    }
}

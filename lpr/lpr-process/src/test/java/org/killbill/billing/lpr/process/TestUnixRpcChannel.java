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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class TestUnixRpcChannel {
    @Test(groups = "fast")
    public void testMalformedAndTruncatedRepliesInvalidateClientOverRealSocket() throws Exception {
        for (final String mode : List.of("id", "status", "oversize", "truncated")) {
            final Path root = Files.createTempDirectory(Path.of("/tmp"), "rpc-wire-");
            final Path socketPath = root.resolve("p.sock");
            try (final var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                 final var executor = Executors.newSingleThreadExecutor()) {
                server.bind(UnixDomainSocketAddress.of(socketPath));
                final String classpath = ProcessPluginLauncher.runtimeClasspath() + java.io.File.pathSeparator
                        + Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
                final Process worker = new ProcessBuilder(TestProcessPluginClient.JAVA.toString(), "-cp", classpath,
                        InvalidPeer.class.getName(), socketPath.toString(), mode).start();
                final var accepted = executor.submit(server::accept);
                try {
                    final var channel = new UnixRpcChannel(accepted.get(5, java.util.concurrent.TimeUnit.SECONDS), 1024);
                    try (final var client = new ProcessPluginClient(worker, channel, root, TestProcessPluginClient.settings(Path.of("/tmp")))) {
                        final var failure = expectThrows(ProcessPluginCallException.class, () -> client.invoke("op", List.of()));
                        assertTrue(failure.isResultUnknown());
                        assertFalse(worker.isAlive());
                        assertThrows(IllegalStateException.class, () -> client.invoke("op", List.of()));
                    }
                } finally { worker.destroyForcibly(); worker.waitFor(); }
            } finally {
                Files.deleteIfExists(socketPath);
                Files.deleteIfExists(root);
            }
        }
    }

    @Test(groups = "fast")
    public void testWriteDeadlineWhenPeerStopsReading() throws Exception {
        final Path root = Files.createTempDirectory(Path.of("/tmp"), "rpc-block-");
        final Path socketPath = root.resolve("p.sock");
        try (final var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socketPath));
            try (final var peer = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                peer.connect(UnixDomainSocketAddress.of(socketPath));
                try (final var accepted = server.accept(); final var channel = new UnixRpcChannel(accepted, 1_048_576)) {
                    accepted.setOption(java.net.StandardSocketOptions.SO_SNDBUF, 1024);
                    final long deadline = System.nanoTime() + Duration.ofMillis(150).toNanos();
                    assertThrows(java.net.SocketTimeoutException.class, () -> channel.send(new byte[1_048_576], deadline));
                }
            }
        } finally { Files.deleteIfExists(socketPath); Files.delete(root); }
    }

    public static final class InvalidPeer {
        public static void main(final String[] args) throws Exception {
            try (final var socket = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                socket.connect(UnixDomainSocketAddress.of(args[0]));
                final var input = new DataInputStream(Channels.newInputStream(socket));
                final int length = input.readInt();
                final var request = RpcFrame.decode(input.readNBytes(length), 1024);
                final var output = new DataOutputStream(Channels.newOutputStream(socket));
                switch (args[1]) {
                    case "oversize" -> output.writeInt(1_048_577);
                    case "truncated" -> { output.writeInt(100); output.write(1); }
                    case "id", "status" -> {
                        final byte[] response = new RpcFrame(args[1].equals("id") ? request.requestId() + 1 : request.requestId(),
                                args[1].equals("status") ? RpcFrame.HELLO : RpcFrame.SUCCESS, List.of()).encode(1024);
                        output.writeInt(response.length);
                        output.write(response);
                    }
                    default -> throw new IllegalArgumentException();
                }
                output.flush();
            }
        }
    }
}

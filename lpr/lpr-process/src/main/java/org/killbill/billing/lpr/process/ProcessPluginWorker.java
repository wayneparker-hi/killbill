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
import java.io.EOFException;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import org.killbill.billing.lpr.api.ProcessPlugin;
import org.killbill.billing.lpr.classloader.DefaultPluginClassLoaderFactory;
import org.killbill.billing.lpr.spi.ClassLoaderPolicy;
import org.killbill.billing.lpr.spi.PluginArtifact;

/** Standalone child entry point. Only launch in a separate JVM. */
public final class ProcessPluginWorker {
    private ProcessPluginWorker() {}

    public static void main(final String[] args) {
        int exit = 1;
        try { run(args); exit = 0; } catch (final Throwable failure) { exit = 1; }
        // EOF must also stop non-daemon threads/shutdown hooks created by the plugin.
        Runtime.getRuntime().halt(exit);
    }

    private static void run(final String[] args) throws Exception {
        if (args.length < 7) { throw new IllegalArgumentException("Worker arguments missing"); }
        final ProcessHandle parent = ProcessHandle.of(Long.parseLong(args[6])).orElseThrow();
        parent.onExit().thenRun(() -> Runtime.getRuntime().halt(0));
        final byte[] challenge = System.in.readNBytes(32);
        if (challenge.length != 32) { throw new IOException("Missing startup challenge"); }
        final int maxBytes = Integer.parseInt(args[5]);
        final var artifact = new PluginArtifact(args[1], args[2], Path.of(args[4]),
                Arrays.stream(args).skip(7).map(Path::of).toList());
        try (final var loader = new DefaultPluginClassLoaderFactory().create(artifact, ClassLoaderPolicy.defaultPolicy(),
                                                                           ProcessPluginWorker.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader.classLoader());
            final ProcessPlugin plugin = loader.classLoader().loadClass(args[3]).asSubclass(ProcessPlugin.class).getConstructor().newInstance();
            plugin.start();
            serve(plugin, args, challenge, maxBytes);
        } finally { Thread.currentThread().setContextClassLoader(ProcessPluginWorker.class.getClassLoader()); }
    }

    private static void serve(final ProcessPlugin plugin, final String[] args, final byte[] challenge, final int maxBytes) throws IOException {
        try (final SocketChannel socket = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            socket.connect(UnixDomainSocketAddress.of(args[0]));
            final var input = new DataInputStream(Channels.newInputStream(socket));
            final var output = new DataOutputStream(Channels.newOutputStream(socket));
            send(output, new RpcFrame(0, RpcFrame.HELLO, List.of(HexFormat.of().formatHex(challenge), args[1], args[2],
                                                              Long.toString(ProcessHandle.current().pid()))), maxBytes);
            while (true) {
                final int length;
                try { length = input.readInt(); } catch (final EOFException eof) { return; }
                if (length < RpcFrame.HEADER_BYTES || length > maxBytes) { throw new IOException("Invalid RPC frame length"); }
                final byte[] bytes = input.readNBytes(length);
                final RpcFrame request = RpcFrame.decode(bytes, maxBytes);
                if (request.status() != RpcFrame.REQUEST || request.fields().isEmpty()) { throw new IOException("Invalid RPC request"); }
                send(output, invoke(plugin, request), maxBytes);
            }
        }
    }

    private static RpcFrame invoke(final ProcessPlugin plugin, final RpcFrame request) {
        try {
            final List<String> result = plugin.invoke(request.fields().get(0), request.fields().subList(1, request.fields().size()));
            return new RpcFrame(request.requestId(), RpcFrame.SUCCESS, result);
        } catch (final Exception failure) {
            return new RpcFrame(request.requestId(), RpcFrame.FAILURE, List.of("Plugin invocation failed"));
        }
    }

    private static void send(final DataOutputStream output, final RpcFrame frame, final int maxBytes) throws IOException {
        final byte[] bytes = frame.encode(maxBytes);
        output.writeInt(bytes.length);
        output.write(bytes);
        output.flush();
    }
}

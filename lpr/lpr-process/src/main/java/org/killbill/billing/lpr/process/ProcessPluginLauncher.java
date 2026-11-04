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
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.killbill.billing.lpr.api.ProcessPlugin;
import org.killbill.billing.lpr.classloader.DefaultPluginClassLoaderFactory;
import org.killbill.billing.lpr.spi.PluginArtifact;
import org.killbill.billing.lpr.spi.PluginDescriptor;

/** Experimental local launcher. Not installed in the default runtime's Guice graph. */
public final class ProcessPluginLauncher {
    public ProcessPluginClient launch(final PluginArtifact artifact, final PluginDescriptor descriptor,
                                      final ProcessPluginSettings settings) throws IOException {
        if (!artifact.pluginId().equals(descriptor.pluginId()) || !artifact.version().equals(descriptor.version())) {
            throw new IllegalArgumentException("Process artifact and descriptor identity differ");
        }
        final Path directory = Files.createTempDirectory(settings.socketRoot().toRealPath(), "p-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Process process = null;
        UnixRpcChannel channel = null;
        try (final var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            final long deadline = System.nanoTime() + settings.startupTimeout().toNanos();
            server.bind(UnixDomainSocketAddress.of(directory.resolve("p.sock")));
            final byte[] challenge = new byte[32];
            new SecureRandom().nextBytes(challenge);
            process = start(artifact, descriptor, settings, directory);
            try (var stdin = process.getOutputStream()) { stdin.write(challenge); }
            channel = new UnixRpcChannel(accept(server, process, deadline), settings.maxFrameBytes());
            validateHello(channel.receive(deadline), process, descriptor, challenge);
            return new ProcessPluginClient(process, channel, directory, settings);
        } catch (final IOException | RuntimeException e) {
            cleanFailedLaunch(process, channel, directory, settings, e);
            throw e;
        }
    }

    private static Process start(final PluginArtifact artifact, final PluginDescriptor descriptor, final ProcessPluginSettings settings,
                                  final Path directory) throws IOException {
        final List<String> command = new ArrayList<>(List.of(settings.javaExecutable().toString(), "-Xms16m",
                "-Xmx" + settings.maxHeapMiB() + "m", "-XX:+ExitOnOutOfMemoryError", "-cp", runtimeClasspath(),
                ProcessPluginWorker.class.getName(), directory.resolve("p.sock").toString(), artifact.pluginId(), artifact.version(),
                descriptor.entrypointClass(), artifact.jar().toAbsolutePath().toString(), Integer.toString(settings.maxFrameBytes()), Long.toString(ProcessHandle.current().pid())));
        artifact.libraries().forEach(path -> command.add(path.toAbsolutePath().toString()));
        final ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        for (final String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) {
            builder.environment().remove(variable);
        }
        return builder.start();
    }

    static String runtimeClasspath() {
        return List.of(ProcessPluginWorker.class, ProcessPlugin.class, PluginArtifact.class, DefaultPluginClassLoaderFactory.class,
                       org.slf4j.LoggerFactory.class).stream()
                .map(type -> Path.of(java.net.URI.create(type.getProtectionDomain().getCodeSource().getLocation().toString())).toString())
                .distinct().collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }

    private static SocketChannel accept(final ServerSocketChannel server, final Process process, final long deadline) throws IOException {
        server.configureBlocking(false);
        try (final Selector selector = Selector.open()) {
            server.register(selector, SelectionKey.OP_ACCEPT);
            while (true) {
                final long remaining = UnixRpcChannel.checkDeadline(deadline);
                if (!process.isAlive()) { throw new IOException("Plugin exited before startup handshake: " + process.exitValue()); }
                final SocketChannel socket = server.accept();
                if (socket != null) { return socket; }
                selector.select(Math.max(1L, Math.min(100L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining))));
                selector.selectedKeys().clear();
            }
        }
    }

    static void validateHello(final RpcFrame hello, final Process process, final PluginDescriptor descriptor, final byte[] challenge) throws IOException {
        if (hello.status() != RpcFrame.HELLO || hello.requestId() != 0
            || !hello.fields().equals(List.of(HexFormat.of().formatHex(challenge), descriptor.pluginId(), descriptor.version(), Long.toString(process.pid())))) {
            throw new IOException("Invalid plugin startup handshake");
        }
    }

    private static void cleanFailedLaunch(final Process process, final UnixRpcChannel channel, final Path directory,
                                          final ProcessPluginSettings settings, final Throwable original) {
        if (channel != null) {
            try { channel.close(); } catch (final IOException e) { original.addSuppressed(e); }
        }
        try { WorkerResources.terminate(process, settings); } catch (final IOException e) { original.addSuppressed(e); }
        try { WorkerResources.removeDirectory(directory); } catch (final IOException e) { original.addSuppressed(e); }
    }
}

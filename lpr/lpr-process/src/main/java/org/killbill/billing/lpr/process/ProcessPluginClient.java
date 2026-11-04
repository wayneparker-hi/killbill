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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** One explicitly launched worker. Register with ResourceRegistry; no automatic restart or retry. */
public final class ProcessPluginClient implements AutoCloseable {
    private final Process process;
    private final UnixRpcChannel channel;
    private final Path directory;
    private final ProcessPluginSettings settings;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ReentrantLock invocation = new ReentrantLock();
    private long sequence;

    ProcessPluginClient(final Process process, final UnixRpcChannel channel, final Path directory, final ProcessPluginSettings settings) {
        this.process = process;
        this.channel = channel;
        this.directory = directory;
        this.settings = settings;
    }

    public long pid() { return process.pid(); }
    public boolean isAlive() { return !closed.get() && process.isAlive(); }

    public List<String> invoke(final String operation, final List<String> arguments) throws IOException {
        if (!invocation.tryLock()) { throw new RejectedExecutionException("Plugin already has an in-flight request"); }
        try {
            if (closed.get()) { throw new IllegalStateException("Plugin process client is closed"); }
            if (operation == null || operation.isBlank()) { throw new IllegalArgumentException("Operation is required"); }
            if (arguments.size() >= RpcFrame.MAX_FIELDS) { throw new IllegalArgumentException("Too many RPC arguments"); }
            final List<String> fields = new ArrayList<>();
            fields.add(operation);
            fields.addAll(List.copyOf(arguments));
            final long id = ++sequence;
            final byte[] request = new RpcFrame(id, RpcFrame.REQUEST, fields).encode(settings.maxFrameBytes());
            return dispatch(id, request);
        } finally { invocation.unlock(); }
    }

    private List<String> dispatch(final long id, final byte[] request) throws IOException {
        final long deadline = System.nanoTime() + settings.callTimeout().toNanos();
        try {
            channel.send(request, deadline);
            final RpcFrame response = channel.receive(deadline);
            validateResponse(id, response);
            if (response.status() == RpcFrame.FAILURE) {
                throw new ProcessPluginCallException("Remote plugin failed; execution result is unknown", true, null);
            }
            return response.fields();
        } catch (final ProcessPluginCallException e) { throw e;
        } catch (final IOException | RuntimeException e) {
            final var failure = new ProcessPluginCallException("Plugin transport failed; execution result is unknown", false, e);
            try { close(); } catch (final IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    static void validateResponse(final long id, final RpcFrame response) throws IOException {
        if (response.requestId() != id || (response.status() != RpcFrame.SUCCESS && response.status() != RpcFrame.FAILURE)) {
            throw new IOException("Invalid RPC response correlation or status");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed.compareAndSet(false, true)) { return; }
        IOException failure = null;
        try { channel.close(); } catch (final IOException e) { failure = e; }
        try { WorkerResources.terminate(process, settings); } catch (final IOException e) {
            if (failure == null) { failure = e; } else { failure.addSuppressed(e); }
        }
        try { WorkerResources.removeDirectory(directory); } catch (final IOException e) {
            if (failure == null) { failure = e; } else { failure.addSuppressed(e); }
        }
        if (failure != null) { throw failure; }
    }
}

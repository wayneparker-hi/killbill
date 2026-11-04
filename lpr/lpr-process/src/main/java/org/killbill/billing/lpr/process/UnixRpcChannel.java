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

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;

/** Caller-thread IO with one total monotonic deadline; close wakes blocked IO. */
final class UnixRpcChannel implements AutoCloseable {
    private final SocketChannel socket;
    private final Selector selector;
    private final int maxBytes;

    UnixRpcChannel(final SocketChannel socket, final int maxBytes) throws IOException {
        this.socket = socket;
        this.maxBytes = maxBytes;
        this.selector = openSelector(socket);
        try {
            socket.configureBlocking(false);
            socket.register(selector, SelectionKey.OP_READ);
        } catch (final IOException | RuntimeException e) {
            try { selector.close(); } catch (final IOException cleanup) { e.addSuppressed(cleanup); }
            try { socket.close(); } catch (final IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    private static Selector openSelector(final SocketChannel socket) throws IOException {
        try { return Selector.open(); }
        catch (final IOException | RuntimeException e) {
            try { socket.close(); } catch (final IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    void send(final byte[] frame, final long deadline) throws IOException {
        final ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + frame.length).putInt(frame.length).put(frame);
        buffer.flip();
        transfer(buffer, true, deadline);
    }

    RpcFrame receive(final long deadline) throws IOException {
        final ByteBuffer header = ByteBuffer.allocate(Integer.BYTES);
        transfer(header, false, deadline);
        final int length = header.flip().getInt();
        if (length < RpcFrame.HEADER_BYTES || length > maxBytes) { throw new IOException("Invalid RPC frame length"); }
        final ByteBuffer body = ByteBuffer.allocate(length);
        transfer(body, false, deadline);
        return RpcFrame.decode(body.array(), maxBytes);
    }

    private void transfer(final ByteBuffer buffer, final boolean writing, final long deadline) throws IOException {
        while (buffer.hasRemaining()) {
            checkDeadline(deadline);
            final int count = writing ? socket.write(buffer) : socket.read(buffer);
            if (count < 0) { throw new EOFException("Plugin process disconnected"); }
            if (count == 0) {
                socket.keyFor(selector).interestOps(writing ? SelectionKey.OP_WRITE : SelectionKey.OP_READ);
                final long remaining = checkDeadline(deadline);
                selector.select(Math.max(1L, Math.min(100L, TimeUnit.NANOSECONDS.toMillis(remaining))));
                selector.selectedKeys().clear();
            }
        }
    }

    static long checkDeadline(final long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) { throw new InterruptedIOException("Plugin call interrupted"); }
        final long remaining = deadline - System.nanoTime();
        if (remaining <= 0) { throw new SocketTimeoutException("Plugin deadline exceeded"); }
        return remaining;
    }

    @Override
    public void close() throws IOException {
        selector.wakeup();
        try { socket.close(); } finally { selector.close(); }
    }
}

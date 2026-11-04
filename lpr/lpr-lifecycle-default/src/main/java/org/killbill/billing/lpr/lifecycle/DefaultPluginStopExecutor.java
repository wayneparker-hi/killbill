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

package org.killbill.billing.lpr.lifecycle;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.killbill.billing.lpr.spi.PluginStopExecutor;

/** No queue: blocked callbacks consume a worker until they actually return. */
public final class DefaultPluginStopExecutor implements PluginStopExecutor {

    private final ThreadPoolExecutor workers;
    private final long timeoutMillis;

    public DefaultPluginStopExecutor(final int maxThreads, final long timeoutMillis) {
        if (maxThreads <= 0 || timeoutMillis <= 0) {
            throw new IllegalArgumentException("Stop worker limit and timeout must be positive");
        }
        this.timeoutMillis = timeoutMillis;
        final AtomicInteger sequence = new AtomicInteger();
        workers = new ThreadPoolExecutor(0, maxThreads, 60, TimeUnit.SECONDS,
                new SynchronousQueue<>(), action -> {
                    final Thread worker = new Thread(action, "plugin-stop-" + sequence.incrementAndGet());
                    worker.setDaemon(true);
                    worker.setContextClassLoader(DefaultPluginStopExecutor.class.getClassLoader());
                    return worker;
                });
    }

    @Override
    public void execute(final Runnable action) throws TimeoutException, InterruptedException {
        final Execution execution = new Execution(Objects.requireNonNull(action, "action"));
        workers.execute(execution);
        try {
            execution.completed.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (final TimeoutException | InterruptedException e) {
            execution.interrupt();
            throw e;
        } catch (final ExecutionException e) {
            if (e.getCause() instanceof Error error) { throw error; }
            throw new IllegalStateException("Plugin teardown failed", e.getCause());
        }
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }

    private static final class Execution implements Runnable {
        private final Runnable action;
        private final CompletableFuture<Void> completed = new CompletableFuture<>();
        private Thread runner;
        private boolean interrupted;

        private Execution(final Runnable action) { this.action = action; }

        @Override
        public void run() {
            begin();
            try {
                action.run();
                completed.complete(null);
            } catch (final Throwable failure) {
                completed.completeExceptionally(failure);
            } finally {
                end();
            }
        }

        private synchronized void begin() {
            runner = Thread.currentThread();
            if (interrupted) { runner.interrupt(); }
        }

        private synchronized void end() { runner = null; }

        private synchronized void interrupt() {
            interrupted = true;
            if (runner != null) { runner.interrupt(); }
        }
    }
}

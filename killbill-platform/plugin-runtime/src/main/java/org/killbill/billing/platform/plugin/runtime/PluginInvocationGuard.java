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

package org.killbill.billing.platform.plugin.runtime;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.killbill.billing.lpr.core.LprMetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies timeout and circuit-breaker protection to every call into a plugin service.
 * <p>
 * There is one guard per runtime. It keeps a per-plugin circuit breaker and a shared
 * executor on which plugin calls are executed so that they can be interrupted on timeout.
 * The worker count and each plugin's concurrent calls are bounded. Cancellation requests an
 * interrupt; an uncooperative call retains its slot until its code actually returns.
 * <p>
 * The guard is thread-safe and intended to be used from the dynamic proxy installed around
 * every plugin service registration.
 */
final class PluginInvocationGuard {

    private static final Logger log = LoggerFactory.getLogger(PluginInvocationGuard.class);

    private final PluginRuntimeConfig config;
    private final LprMetricsCollector metrics;
    private final ExecutorService executor;
    private final Map<String, Semaphore> slotsByPlugin = new ConcurrentHashMap<>();
    private final Map<String, CircuitBreaker> breakersByPlugin = new ConcurrentHashMap<>();
    private final Map<String, Long> timeoutOverrides;

    PluginInvocationGuard(final PluginRuntimeConfig config, final LprMetricsCollector metrics) {
        this(config, metrics, defaultExecutor(config));
    }

    PluginInvocationGuard(final PluginRuntimeConfig config,
                          final LprMetricsCollector metrics,
                          final ExecutorService executor) {
        this.config = Objects.requireNonNull(config, "config");
        this.metrics = metrics;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.timeoutOverrides = parseOverrides(config.getPluginInvocationTimeoutOverrides());
    }

    <T> T call(final String pluginId, final Callable<T> callable) throws Exception {
        final CircuitBreaker breaker = breakerFor(pluginId);
        final CircuitBreaker.Permit permit = breaker.acquire();
        if (permit == null) {
            throw new PluginInvocationException("Circuit breaker is OPEN for plugin " + pluginId);
        }
        final Semaphore slots = slotsByPlugin.computeIfAbsent(pluginId,
                ignored -> new Semaphore(config.getPluginInvocationMaxConcurrentPerPlugin()));
        if (!slots.tryAcquire()) {
            breaker.recordFailure(permit);
            throw new PluginInvocationException("Plugin " + pluginId + " concurrent call limit exceeded");
        }
        final FutureTask<T> future = new FutureTask<>(callable) {
            @Override
            public void run() {
                try {
                    super.run();
                } finally {
                    // Cancellation does not release a slot while an uncooperative call still runs.
                    slots.release();
                }
            }
        };
        try {
            executor.execute(future);
        } catch (final RejectedExecutionException e) {
            slots.release();
            breaker.recordFailure(permit);
            throw new PluginInvocationException("Plugin invocation capacity exhausted", e);
        }
        return await(pluginId, future, breaker, permit);
    }

    private <T> T await(final String pluginId, final Future<T> future,
                        final CircuitBreaker breaker, final CircuitBreaker.Permit permit) throws Exception {
        final long timeout = timeoutFor(pluginId);
        try {
            final T result = timeout > 0 ? future.get(timeout, TimeUnit.MILLISECONDS) : future.get();
            breaker.recordSuccess(permit);
            return result;
        } catch (final TimeoutException e) {
            future.cancel(true);
            breaker.recordFailure(permit);
            if (metrics != null) {
                metrics.recordPluginInvocationTimeout();
            }
            throw new PluginInvocationException("Plugin " + pluginId + " call timed out after " + timeout + " ms", e);
        } catch (final InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            breaker.recordFailure(permit);
            throw new PluginInvocationException("Interrupted while calling plugin " + pluginId, e);
        } catch (final ExecutionException e) {
            breaker.recordFailure(permit);
            if (metrics != null) {
                metrics.recordPluginInvocationFailure();
            }
            final Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new PluginInvocationException("Unexpected plugin failure", cause);
        }
    }

    void shutdown() {
        executor.shutdownNow();
        slotsByPlugin.clear();
        breakersByPlugin.clear();
    }

    CircuitBreaker breakerFor(final String pluginId) {
        return breakersByPlugin.computeIfAbsent(pluginId, ignored ->
                new CircuitBreaker(config.getPluginCircuitBreakerFailureThreshold(),
                                   config.getPluginCircuitBreakerOpenDuration(),
                                   () -> { if (metrics != null) { metrics.recordCircuitBreakerOpen(); } }));
    }

    private long timeoutFor(final String pluginId) {
        return timeoutOverrides.getOrDefault(pluginId, config.getPluginInvocationDefaultTimeout());
    }

    private static Map<String, Long> parseOverrides(final String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }
        final Map<String, Long> parsed = new HashMap<>();
        for (final String pair : raw.split(",")) {
            final String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            final int equals = trimmed.indexOf('=');
            if (equals <= 0 || equals == trimmed.length() - 1) {
                log.warn("Ignoring malformed timeout override '{}'", trimmed);
                continue;
            }
            final String pluginId = trimmed.substring(0, equals).trim();
            final String value = trimmed.substring(equals + 1).trim();
            try {
                parsed.put(pluginId, Long.parseLong(value));
            } catch (final NumberFormatException e) {
                log.warn("Ignoring non-numeric timeout override '{}' for plugin '{}'", value, pluginId);
            }
        }
        return Collections.unmodifiableMap(parsed);
    }

    private static ExecutorService defaultExecutor(final PluginRuntimeConfig config) {
        final AtomicInteger counter = new AtomicInteger();
        return new ThreadPoolExecutor(0, config.getPluginInvocationMaxThreads(), 60, TimeUnit.SECONDS,
                new SynchronousQueue<>(), r -> {
                    final Thread thread = new Thread(r, "plugin-invocation-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    thread.setContextClassLoader(PluginInvocationGuard.class.getClassLoader());
                    return thread;
                });
    }
}

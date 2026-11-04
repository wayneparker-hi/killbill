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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.killbill.billing.lpr.api.PluginHealthCheck;
import org.killbill.billing.lpr.core.DefaultPluginManager;
import org.killbill.billing.lpr.core.LprMetricsCollector;
import org.killbill.billing.lpr.core.PluginHealthState;
import org.killbill.billing.lpr.core.RunningPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs plugin health probes on a schedule and keeps the registry view in sync.
 * <p>
 * Each {@link org.killbill.billing.lpr.api.Plugin#healthCheck()} is called with the plugin's
 * ClassLoader as the thread context ClassLoader and guarded by a timeout. The resulting state is
 * exposed to {@link org.killbill.billing.lpr.core.DefaultServiceRegistry} through a predicate, so
 * an unhealthy plugin's services disappear from selectors until it recovers.
 */
public final class PluginHealthCheckManager {

    private static final Logger log = LoggerFactory.getLogger(PluginHealthCheckManager.class);

    private final Supplier<List<RunningPlugin>> pluginsSupplier;
    private final PluginRuntimeConfig config;
    private final LprMetricsCollector metrics;
    private final ExecutorService workerExecutor;
    private final ScheduledExecutorService scheduler;
    private final Set<PluginVersion> probing = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean started = new AtomicBoolean();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Map<PluginVersion, PluginHealthState> states = new ConcurrentHashMap<>();

    public PluginHealthCheckManager(final DefaultPluginManager pluginManager,
                                    final PluginRuntimeConfig config,
                                    final LprMetricsCollector metrics) {
        this(pluginManager::runningPlugins, config, metrics, defaultWorkerExecutor(config), defaultScheduler());
    }

    public PluginHealthCheckManager(final Supplier<List<RunningPlugin>> pluginsSupplier,
                                    final PluginRuntimeConfig config,
                                    final LprMetricsCollector metrics) {
        this(pluginsSupplier, config, metrics, defaultWorkerExecutor(config), defaultScheduler());
    }

    PluginHealthCheckManager(final Supplier<List<RunningPlugin>> pluginsSupplier,
                             final PluginRuntimeConfig config,
                             final LprMetricsCollector metrics,
                             final ExecutorService workerExecutor,
                             final ScheduledExecutorService scheduler) {
        this.pluginsSupplier = Objects.requireNonNull(pluginsSupplier, "pluginsSupplier");
        this.config = Objects.requireNonNull(config, "config");
        this.metrics = metrics != null ? metrics : new LprMetricsCollector(org.killbill.billing.lpr.core.NoOpLprMetrics.INSTANCE);
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /**
     * Starts the periodic check. Safe to call more than once.
     */
    public void start() {
        if (!config.isPluginHealthCheckEnabled()) {
            log.info("Plugin health checks are disabled");
            return;
        }
        final long interval = config.getPluginHealthCheckInterval();
        if (interval <= 0) {
            log.info("Plugin health check interval is non-positive; disabling");
            return;
        }
        if (started.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(this::runChecks, interval, interval, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Runs one check cycle immediately. For tests.
     */
    void runChecksOnce() {
        runChecks();
    }

    /**
     * Stops scheduling new checks and attempts to interrupt in-flight ones.
     */
    public void stop() {
        scheduler.shutdownNow();
        workerExecutor.shutdownNow();
        listeners.clear();
        try {
            // Best-effort wait for cleanup; do not block shutdown indefinitely.
            workerExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) { states.clear(); }
    }

    /**
     * @param pluginId the plugin id
     * @param version  the plugin version
     * @return the most recent state, or empty if no check has run
     */
    public Optional<PluginHealthState> getState(final String pluginId, final String version) {
        return Optional.ofNullable(states.get(new PluginVersion(pluginId, version)));
    }

    /**
     * Predicate suitable for {@link org.killbill.billing.lpr.core.DefaultServiceRegistry}.
     *
     * @param pluginId the plugin id
     * @param version  the plugin version
     * @return true if the plugin version is healthy or has no health probe
     */
    public boolean isHealthy(final String pluginId, final String version) {
        final PluginHealthState state = states.get(new PluginVersion(pluginId, version));
        // Unknown / never-checked states default to healthy so a plugin is not hidden at startup
        // before the first probe completes.
        return state == null || state.healthy();
    }

    private synchronized void runChecks() {
        final List<RunningPlugin> running;
        try {
            running = pluginsSupplier.get();
        } catch (final RuntimeException e) {
            log.error("Failed to list running plugins for health check", e);
            return;
        }

        for (final RunningPlugin plugin : running) {
            check(plugin);
        }

        // Retain only still-running plugins so stopped versions do not keep stale unhealthy states
        // that would hide a future version with the same plugin id.
        final java.util.Set<PluginVersion> active = running.stream()
                                                           .map(p -> new PluginVersion(p.pluginId(), p.version()))
                                                           .collect(java.util.stream.Collectors.toUnmodifiableSet());
        states.keySet().removeIf(key -> !active.contains(key));
    }

    void addListener(final Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    synchronized PluginHealthState checkNow(final RunningPlugin plugin) {
        check(plugin);
        return getState(plugin.pluginId(), plugin.version()).orElseThrow();
    }

    private void check(final RunningPlugin plugin) {
        final PluginVersion key = new PluginVersion(plugin.pluginId(), plugin.version());
        final long startNanos = System.nanoTime();
        Future<PluginHealthCheck.HealthCheckResult> future = null;
        try {
            future = submitProbe(plugin, key);
            final long timeout = config.getPluginHealthCheckTimeout();
            final PluginHealthCheck.HealthCheckResult result = timeout > 0
                    ? future.get(timeout, TimeUnit.MILLISECONDS) : future.get();
            update(key, result.healthy(), result.message());
        } catch (final Exception e) {
            if (future != null) {
                future.cancel(true);
            }
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            final Throwable cause = e.getCause() != null ? e.getCause() : e;
            update(key, false, e instanceof TimeoutException ? "Health check timed out" : cause.toString());
            log.warn("Health check failed for plugin {} version {}", plugin.pluginId(), plugin.version(), cause);
        } finally {
            metrics.recordPluginHealthCheckDuration(System.nanoTime() - startNanos);
        }
    }

    private Future<PluginHealthCheck.HealthCheckResult> submitProbe(final RunningPlugin plugin, final PluginVersion key) {
        if (!probing.add(key)) {
            throw new RejectedExecutionException("Previous health check is still running");
        }
        final FutureTask<PluginHealthCheck.HealthCheckResult> task = new FutureTask<>(() -> probe(plugin)) {
            @Override public void run() {
                try { super.run(); } finally { probing.remove(key); }
            }
        };
        try {
            workerExecutor.execute(task);
            return task;
        } catch (final RuntimeException e) {
            probing.remove(key);
            throw e;
        }
    }

    private PluginHealthCheck.HealthCheckResult probe(final RunningPlugin plugin) throws Exception {
        final Thread thread = Thread.currentThread();
        final ClassLoader original = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(plugin.classLoader());
            final Optional<PluginHealthCheck> probe = plugin.instance().healthCheck();
            return probe.isPresent() ? Objects.requireNonNull(probe.get().check(), "Health check result")
                                     : PluginHealthCheck.HealthCheckResult.ok("No health probe configured");
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    private void update(final PluginVersion key, final boolean healthy, final String message) {
        states.put(key, new PluginHealthState(key.pluginId(), key.version(), healthy, message, Instant.now()));
        for (final Runnable listener : listeners) {
            try {
                listener.run();
            } catch (final RuntimeException e) {
                log.warn("Health state listener failed", e);
            }
        }
    }

    private static ExecutorService defaultWorkerExecutor(final PluginRuntimeConfig config) {
        final AtomicInteger counter = new AtomicInteger();
        return new ThreadPoolExecutor(0, config.getPluginHealthCheckMaxThreads(), 60, TimeUnit.SECONDS,
                new SynchronousQueue<>(), r -> {
                    final Thread thread = new Thread(r, "plugin-healthcheck-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    thread.setContextClassLoader(PluginHealthCheckManager.class.getClassLoader());
                    return thread;
                });
    }

    private static ScheduledExecutorService defaultScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread thread = new Thread(r, "plugin-healthcheck-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    private record PluginVersion(String pluginId, String version) {
    }
}

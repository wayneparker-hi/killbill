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
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.killbill.billing.platform.plugin.runtime;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;

import org.killbill.billing.lpr.api.EventBus;
import org.killbill.billing.lpr.core.DefaultPluginManager;
import org.killbill.billing.lpr.core.LprMetricsCollector;
import org.killbill.billing.lpr.core.PluginActivationListener;
import org.killbill.billing.lpr.core.PluginHealthState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches a plugin after it is activated and rolls it back if its health check fails within a
 * configurable window.
 * <p>
 * The watch is a safety net, not a replacement for the start-time checks in
 * {@link DefaultPluginManager#activateVersion}: a version that fails to start is already rejected
 * there. This class handles the case where a version starts successfully but then fails its first
 * probes.
 */
public final class PluginUpgradeMonitor implements PluginActivationListener {

    private static final Logger log = LoggerFactory.getLogger(PluginUpgradeMonitor.class);

    private final DefaultPluginManager pluginManager;
    private final PluginHealthCheckManager healthCheckManager;
    private final PluginRuntimeConfig config;
    private final EventBus eventBus;
    private final LprMetricsCollector metrics;
    private final ScheduledExecutorService scheduler;
    private final Map<PluginVersion, Watch> watches = new ConcurrentHashMap<>();

    @Inject
    public PluginUpgradeMonitor(final DefaultPluginManager pluginManager,
                                final PluginHealthCheckManager healthCheckManager,
                                final PluginRuntimeConfig config,
                                final EventBus eventBus,
                                final LprMetricsCollector metrics) {
        this(pluginManager, healthCheckManager, config, eventBus, metrics, defaultScheduler());
    }

    PluginUpgradeMonitor(final DefaultPluginManager pluginManager,
                         final PluginHealthCheckManager healthCheckManager,
                         final PluginRuntimeConfig config,
                         final EventBus eventBus,
                         final LprMetricsCollector metrics,
                         final ScheduledExecutorService scheduler) {
        this.pluginManager = Objects.requireNonNull(pluginManager, "pluginManager");
        this.healthCheckManager = Objects.requireNonNull(healthCheckManager, "healthCheckManager");
        this.config = Objects.requireNonNull(config, "config");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.metrics = metrics != null ? metrics : new LprMetricsCollector(org.killbill.billing.lpr.core.NoOpLprMetrics.INSTANCE);
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (config.getPluginUpgradeHealthCheckInterval() <= 0) {
            throw new IllegalArgumentException("Upgrade health check interval must be positive");
        }
        pluginManager.addActivationListener(this);
    }

    /**
     * Starts the monitor. Safe to call more than once.
     */
    public void start() {
        log.info("Plugin upgrade monitor started; auto-rollback={}", config.isPluginUpgradeAutoRollbackEnabled());
    }

    /**
     * Stops all in-flight watches and the scheduler.
     */
    public void stop() {
        for (final Watch watch : watches.values()) {
            watch.cancel();
        }
        watches.clear();
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean retainPreviousVersion() {
        return config.isPluginUpgradeAutoRollbackEnabled() && config.isPluginHealthCheckEnabled()
               && config.getPluginUpgradeHealthCheckWindow() > 0;
    }

    @Override
    public void onActivated(final String pluginId, final String newVersion, final Optional<String> previousVersion) {
        watches.keySet().stream().filter(key -> key.pluginId().equals(pluginId)).toList()
               .forEach(key -> cancelWatch(key.pluginId(), key.version()));
        publish(PluginActivationEvent.upgrade(pluginId, previousVersion.orElse(null), newVersion));
        if (!retainPreviousVersion() || previousVersion.isEmpty()) {
            return;
        }
        final Watch watch = new Watch(Instant.now().plusMillis(config.getPluginUpgradeHealthCheckWindow()));
        watches.put(new PluginVersion(pluginId, newVersion), watch);
        if (!check(pluginId, newVersion, watch)) {
            return;
        }
        final long interval = config.getPluginUpgradeHealthCheckInterval();
        watch.attach(scheduler.scheduleAtFixedRate(() -> {
            try {
                check(pluginId, newVersion, watch);
            } catch (final RuntimeException e) {
                log.warn("Upgrade observation failed for {} {}", pluginId, newVersion, e);
            }
        }, interval, interval, TimeUnit.MILLISECONDS));
    }

    @Override
    public void onRolledBack(final String pluginId, final String currentVersion,
                             final String failedVersion, final String reason) {
        cancelWatch(pluginId, failedVersion);
        publish(PluginActivationEvent.rollback(pluginId, failedVersion, currentVersion, reason));
    }

    private boolean check(final String pluginId, final String version, final Watch watch) {
        if (watches.get(new PluginVersion(pluginId, version)) != watch) {
            return false;
        }
        if (!pluginManager.get(pluginId).map(info -> info.version().equals(version)).orElse(false)) {
            cancelWatch(pluginId, version);
            return false;
        }
        healthCheckManager.runChecksOnce();
        final Optional<PluginHealthState> state = healthCheckManager.getState(pluginId, version);
        if (state.isPresent() && !state.get().healthy()) {
            cancelWatch(pluginId, version);
            if (pluginManager.rollbackIfCurrent(pluginId, version, state.get().message())) {
                metrics.recordUpgradeRollback();
            }
            return false;
        }
        if (!Instant.now().isBefore(watch.deadline)) {
            cancelWatch(pluginId, version);
            pluginManager.completeUpgrade(pluginId, version);
            return false;
        }
        return true;
    }

    private void cancelWatch(final String pluginId, final String version) {
        final Watch watch = watches.remove(new PluginVersion(pluginId, version));
        if (watch != null) {
            watch.cancel();
        }
    }

    private void publish(final PluginActivationEvent event) {
        try {
            log.info("Plugin audit action={} pluginId={} from={} to={} reason={} actor=runtime timestamp={}",
                     event.type(), event.pluginId(), event.fromVersion(), event.toVersion(), event.reason(), event.timestamp());
            eventBus.publish(event);
        } catch (final RuntimeException e) {
            log.warn("Failed to publish {}", event, e);
        }
    }

    private static ScheduledExecutorService defaultScheduler() {
        return Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);

            @Override
            public Thread newThread(final Runnable r) {
                final Thread thread = new Thread(r, "plugin-upgrade-monitor-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    private static final class Watch {
        private final Instant deadline;
        private ScheduledFuture<?> future;
        private boolean cancelled;

        private Watch(final Instant deadline) {
            this.deadline = deadline;
        }

        synchronized void attach(final ScheduledFuture<?> scheduled) {
            future = scheduled;
            if (cancelled) { scheduled.cancel(false); }
        }

        synchronized void cancel() {
            cancelled = true;
            if (future != null) { future.cancel(false); }
        }
    }

    private record PluginVersion(String pluginId, String version) {
    }
}

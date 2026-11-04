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

package org.killbill.billing.lpr.core;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.killbill.billing.lpr.api.PluginState;
import org.killbill.billing.lpr.spi.LprMetrics;

/**
 * Centralizes LPR metric names and tags. Core components call these helpers instead of scattering
 * raw metric names, making the emitted namespace easy to document and stabilize.
 * <p>
 * Because the underlying {@link LprMetrics} port is tag-less, dimensions are encoded into dotted
 * names (e.g. {@code killbill.lpr.plugins.state.active}).
 */
public final class LprMetricsCollector {

    private static final String METRIC_PREFIX = "killbill.lpr";

    private final LprMetrics metrics;

    // Counters / timers held per component to avoid repeated lookups.
    private final LprMetrics.Counter eventsPublished;
    private final LprMetrics.Counter eventsFailed;
    private final LprMetrics.Counter invocationTimeouts;
    private final LprMetrics.Counter invocationFailures;
    private final LprMetrics.Counter circuitBreakerOpens;
    private final LprMetrics.Counter upgradeRollbacks;
    private final LprMetrics.Timer pluginStartTimer;
    private final LprMetrics.Timer pluginStopTimer;
    private final LprMetrics.Timer healthCheckTimer;

    // Mutable gauges updated by components.
    private final AtomicLong activeClassLoaders = new AtomicLong(0);
    private final AtomicLong registeredResources = new AtomicLong(0);
    private final ConcurrentHashMap<String, AtomicLong> providersByType = new ConcurrentHashMap<>();

    public LprMetricsCollector(final LprMetrics metrics) {
        this.metrics = metrics != null ? metrics : NoOpLprMetrics.INSTANCE;
        this.eventsPublished = this.metrics.counter(metric("events.published"));
        this.eventsFailed = this.metrics.counter(metric("events.failed"));
        this.invocationTimeouts = this.metrics.counter(metric("invocation.timeout"));
        this.invocationFailures = this.metrics.counter(metric("invocation.failure"));
        this.circuitBreakerOpens = this.metrics.counter(metric("circuitbreaker.open"));
        this.upgradeRollbacks = this.metrics.counter(metric("upgrade.rollback"));
        this.pluginStartTimer = this.metrics.timer(metric("plugin.start.duration"));
        this.pluginStopTimer = this.metrics.timer(metric("plugin.stop.duration"));
        this.healthCheckTimer = this.metrics.timer(metric("healthcheck.duration"));

        this.metrics.gauge(metric("classloaders.active"), activeClassLoaders::get);
        this.metrics.gauge(metric("resources.registered"), registeredResources::get);
    }

    public LprMetrics metrics() {
        return metrics;
    }

    /**
     * Registers gauges that count how many plugin runtimes are currently in each state. The
     * supplier is read on every metrics scrape, so the gauges always reflect the live map rather
     * than counters that can drift.
     */
    public void trackPluginStates(final Supplier<Collection<PluginState>> states) {
        for (final PluginState state : PluginState.values()) {
            final String stateName = state.name();
            metrics.gauge(metric("plugins.state." + stateName.toLowerCase()),
                          () -> countState(states.get(), stateName));
        }
    }

    private static long countState(final Collection<PluginState> states, final String stateName) {
        if (states == null) {
            return 0;
        }
        return states.stream().filter(s -> s != null && s.name().equals(stateName)).count();
    }

    /**
     * Tracks the number of providers for a given service type. The count is keyed by type name so
     * the gauge does not hold a reference to a plugin-loaded Class object after the plugin is
     * unloaded.
     */
    public void providerRegistered(final Class<?> serviceType) {
        providersByType.computeIfAbsent(serviceType.getName(), type -> {
            final AtomicLong count = new AtomicLong(0);
            metrics.gauge(metric("registry.providers." + sanitize(type)), count::get);
            return count;
        }).incrementAndGet();
    }

    public void providerUnregistered(final Class<?> serviceType) {
        providersByType.computeIfPresent(serviceType.getName(), (type, count) -> {
            count.decrementAndGet();
            return count;
        });
    }

    public void classLoaderCreated() {
        activeClassLoaders.incrementAndGet();
    }

    public void classLoaderDestroyed() {
        activeClassLoaders.decrementAndGet();
    }

    public void resourceRegistered() {
        registeredResources.incrementAndGet();
    }

    public void resourceReleased() {
        registeredResources.decrementAndGet();
    }

    public void eventPublished() {
        eventsPublished.increment();
    }

    public void eventFailed() {
        eventsFailed.increment();
    }

    public void recordPluginStart(final long durationNanos) {
        pluginStartTimer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordPluginStop(final long durationNanos) {
        pluginStopTimer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordPluginInvocationTimeout() {
        invocationTimeouts.increment();
    }

    public void recordPluginInvocationFailure() {
        invocationFailures.increment();
    }

    public void recordCircuitBreakerOpen() {
        circuitBreakerOpens.increment();
    }

    public void recordPluginHealthCheckDuration(final long durationNanos) {
        healthCheckTimer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordUpgradeRollback() {
        upgradeRollbacks.increment();
    }

    private static String metric(final String name) {
        return METRIC_PREFIX + "." + name;
    }

    private static String sanitize(final String className) {
        return className.replace('.', '_');
    }
}

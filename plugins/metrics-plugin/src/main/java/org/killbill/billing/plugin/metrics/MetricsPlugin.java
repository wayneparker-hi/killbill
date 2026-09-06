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

package org.killbill.billing.plugin.metrics;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.Set;

import javax.management.MBeanServer;

import org.killbill.billing.lpr.api.Plugin;
import org.killbill.billing.lpr.api.PluginContext;
import org.killbill.billing.runtime.api.Healthcheck;
import org.killbill.billing.tenant.api.Tenant;
import org.killbill.commons.metrics.api.MetricRegistry;
import org.killbill.commons.metrics.dropwizard.KillBillCodahaleMetricRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricSet;
import com.codahale.metrics.jmx.JmxReporter;
import com.codahale.metrics.jvm.BufferPoolMetricSet;
import com.codahale.metrics.jvm.ClassLoadingGaugeSet;
import com.codahale.metrics.jvm.GarbageCollectorMetricSet;
import com.codahale.metrics.jvm.MemoryUsageGaugeSet;
import com.codahale.metrics.jvm.ThreadDeadlockDetector;
import com.codahale.metrics.jvm.ThreadStatesGaugeSet;

/**
 * Publishes the {@link MetricRegistry} the rest of the system meters into, and a healthcheck that
 * reports deadlocked threads.
 * <p>
 * Ported from the OSGi metrics bundle. Two things about it are worth knowing.
 * <p>
 * <b>It is not optional in practice.</b> Kill Bill's {@code KillbillPluginsMetricRegistry} delegates
 * to whatever plugin publishes a {@code MetricRegistry} and no-ops when none does, so a deployment
 * without this plugin records nothing anywhere -- with no error to say so.
 * <p>
 * <b>The JMX reporter is a managed resource.</b> It starts a thread, and a thread that outlives the
 * plugin pins its ClassLoader, so it goes through {@code ResourceRegistry} rather than being closed
 * by hand in {@link #stop()}. That is the difference between a plugin that can be reloaded and one
 * that leaks metaspace on every reload.
 */
public class MetricsPlugin implements Plugin {

    private static final Logger log = LoggerFactory.getLogger(MetricsPlugin.class);

    /** The name the healthcheck and metric registry answer to; matches the OSGi bundle's. */
    public static final String REGISTRATION_NAME = "killbill-metrics";

    private com.codahale.metrics.MetricRegistry codahaleRegistry;

    @Override
    public void start(final PluginContext context) {
        codahaleRegistry = new com.codahale.metrics.MetricRegistry();
        final MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();

        registerAll("buffers", new BufferPoolMetricSet(mBeanServer));
        registerAll("classloading", new ClassLoadingGaugeSet());
        registerAll("gc", new GarbageCollectorMetricSet());
        registerAll("memory", new MemoryUsageGaugeSet());
        registerAll("threads", new ThreadStatesGaugeSet());

        final JmxReporter jmxReporter = JmxReporter.forRegistry(codahaleRegistry)
                                                   .registerWith(mBeanServer)
                                                   .build();
        jmxReporter.start();
        // Managed rather than closed in stop(): the reporter owns a thread, and the runtime has to
        // be able to drop it even if stop() never runs.
        context.resources().manage(jmxReporter);
        log.info("Reporting metrics to JMX");

        final Map<String, Object> properties =
                Map.of("killbill.pluginName", REGISTRATION_NAME);

        context.services().register(MetricRegistry.class,
                                    new KillBillCodahaleMetricRegistry(codahaleRegistry),
                                    properties);
        context.services().register(Healthcheck.class, new DeadlockHealthcheck(), properties);
    }

    @Override
    public void stop() {
        // Registrations and the JMX reporter are unwound by the runtime. Dropping the registry
        // reference is all this plugin owns.
        codahaleRegistry = null;
    }

    private void registerAll(final String prefix, final MetricSet metricSet) {
        for (final Map.Entry<String, Metric> entry : metricSet.getMetrics().entrySet()) {
            if (entry.getValue() instanceof MetricSet nested) {
                registerAll(prefix + '.' + entry.getKey(), nested);
            } else {
                codahaleRegistry.register(prefix + '.' + entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Reports the JVM as unhealthy when threads are deadlocked.
     * <p>
     * Deliberately the only healthcheck here: a deadlock is a condition the process cannot recover
     * from, which is what a load balancer needs to know. Metric thresholds are a monitoring
     * question, not a liveness one.
     */
    private static final class DeadlockHealthcheck implements Healthcheck {

        private final ThreadDeadlockDetector detector = new ThreadDeadlockDetector();

        @Override
        public HealthStatus getHealthStatus(final Tenant tenant, final Map properties) {
            final Set<String> deadlocked = detector.getDeadlockedThreads();
            if (deadlocked == null || deadlocked.isEmpty()) {
                return HealthStatus.healthy();
            }
            return HealthStatus.unHealthy("Deadlocked threads: " + deadlocked);
        }
    }
}

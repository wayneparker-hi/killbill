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

import java.nio.file.Path;
import java.util.List;

import org.killbill.billing.lpr.api.PluginState;
import org.killbill.billing.lpr.testkit.PluginTestRuntime;
import org.killbill.billing.runtime.api.Healthcheck;
import org.killbill.commons.metrics.api.MetricRegistry;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * The metrics plugin is not optional in practice: Kill Bill's {@code KillbillPluginsMetricRegistry}
 * delegates to whatever plugin publishes a {@code MetricRegistry} and quietly no-ops when none does.
 * A deployment missing this plugin therefore records nothing, anywhere, with no error to say so --
 * which is why "it publishes a MetricRegistry" is worth asserting rather than assuming.
 * <p>
 * The plugin is loaded from a jar built out of this module's own compiled classes. On the test
 * classpath its classes would be loaded by the application ClassLoader and the interesting part --
 * that {@code com.codahale.metrics} types resolve to the platform's copy, not a bundled one --
 * would not be exercised at all.
 */
public class TestMetricsPlugin {

    private PluginTestRuntime runtime;

    @BeforeMethod(groups = "fast")
    public void beforeMethod() {
        runtime = PluginTestRuntime.create();
        runtime.installCompiled(Path.of("target", "classes"));
        runtime.discover();
        runtime.startAll();
    }

    @AfterMethod(groups = "fast", alwaysRun = true)
    public void afterMethod() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test(groups = "fast")
    public void testItPublishesAMetricRegistryAndAHealthcheck() {
        assertEquals(runtime.state("killbill-metrics").orElseThrow(), PluginState.ACTIVE);

        final MetricRegistry registry = runtime.service(MetricRegistry.class, "killbill-metrics");
        assertNotNull(registry, "Nothing published a MetricRegistry; every Kill Bill timer would no-op");

        assertNotNull(runtime.service(Healthcheck.class, "killbill-metrics"));
    }

    /**
     * The registry has to be usable, not merely present. A registry that cannot record a counter is
     * indistinguishable from a missing one at the call sites, and both fail silently.
     */
    @Test(groups = "fast")
    public void testTheRegistryActuallyRecords() {
        final MetricRegistry registry = runtime.service(MetricRegistry.class, "killbill-metrics");

        registry.counter("test.counter").inc(1);

        assertEquals(registry.counter("test.counter").getCount(), 1L);
    }

    /**
     * JVM metric sets are the reason this plugin exists rather than a bare registry: without them
     * there is no memory, gc or thread instrumentation anywhere in the process.
     */
    @Test(groups = "fast")
    public void testJvmMetricsAreRegistered() {
        final MetricRegistry registry = runtime.service(MetricRegistry.class, "killbill-metrics");

        final List<String> prefixes = List.of("memory", "gc", "threads", "classloading", "buffers");
        for (final String prefix : prefixes) {
            assertTrue(registry.getMetrics().keySet().stream().anyMatch(name -> name.startsWith(prefix + '.')),
                       "No " + prefix + " metrics registered; found: " + registry.getMetrics().keySet());
        }
    }

    /**
     * The deadlock healthcheck is what a load balancer reads. A healthy JVM must report healthy --
     * a check that always says unhealthy would take a working node out of rotation.
     */
    @Test(groups = "fast")
    public void testTheHealthcheckReportsHealthyWhenNothingIsDeadlocked() {
        final Healthcheck healthcheck = runtime.service(Healthcheck.class, "killbill-metrics");

        assertTrue(healthcheck.getHealthStatus(null, null).isHealthy());
    }

    /**
     * The JMX reporter owns a thread. If it were not handed to the resource registry, stopping the
     * plugin would leave that thread running, pinning the plugin's ClassLoader and leaking metaspace
     * on every reload.
     */
    @Test(groups = "fast")
    public void testStoppingWithdrawsEverythingItPublished() {
        runtime.manager().stopAll();

        assertEquals(runtime.state("killbill-metrics").orElseThrow(), PluginState.STOPPED);
        assertTrue(runtime.services().getPluginIds(MetricRegistry.class).isEmpty(),
                   "A stopped plugin must no longer be found in the registry");
    }
}

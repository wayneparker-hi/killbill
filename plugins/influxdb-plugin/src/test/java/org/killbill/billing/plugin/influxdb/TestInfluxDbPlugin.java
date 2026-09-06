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

package org.killbill.billing.plugin.influxdb;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.killbill.billing.lpr.api.PluginState;
import org.killbill.billing.lpr.testkit.PluginTestRuntime;
import org.killbill.billing.platform.api.KillbillConfigSource;
import org.killbill.commons.metrics.api.MetricRegistry;
import org.killbill.commons.metrics.dropwizard.KillBillCodahaleMetricRegistry;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Reports Kill Bill's metrics to InfluxDB on a fixed interval.
 * <p>
 * Two properties matter and neither is obvious from reading the plugin.
 * <p>
 * <b>Disabled has to mean "starts and does nothing", not "fails to start".</b> A deployment ships
 * every reporter and enables one; if starting while disabled threw, the plugin would go
 * {@code FAILED}, and were it ever listed as mandatory it would take the boot down with it.
 * <p>
 * <b>Enabled has to release its reporter thread on stop.</b> The reporter is scheduled on a thread
 * of its own, and a thread that outlives the plugin keeps the plugin's ClassLoader alive -- so a
 * reloaded plugin leaks metaspace and, worse, keeps writing to InfluxDb from a version that is no
 * longer deployed. Asserting the thread is gone tests that directly, rather than through a proxy.
 * <p>
 * The plugin is loaded from a jar built out of this module's compiled classes, so its classes come
 * through a plugin ClassLoader while {@code com.codahale.metrics} resolves to the platform's copy.
 * That split is the thing most likely to break and is invisible on the test classpath.
 */
public class TestInfluxDbPlugin {

    private static final String PLUGIN_ID = "killbill-influxdb";
    private static final String ENABLED = "org.killbill.metrics.influxDb";

    private PluginTestRuntime runtime;

    @AfterMethod(groups = "fast", alwaysRun = true)
    public void afterMethod() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test(groups = "fast")
    public void testItStartsCleanlyWhenDisabled() {
        start(Map.of());

        assertEquals(runtime.state(PLUGIN_ID).orElseThrow(), PluginState.ACTIVE,
                     "A disabled reporter must still reach ACTIVE, not FAILED");
        assertTrue(reporterThreads().isEmpty(), "A disabled reporter must not start a thread");
    }

    /**
     * Enabling through {@code killbill.properties} is the path the OSGi bundle used, and the one the
     * descriptor must not shadow: a {@code config:} entry in {@code plugin.yaml} would win over the
     * platform property and make this impossible.
     */
    @Test(groups = "fast")
    public void testThePlatformPropertyEnablesIt() {
        // Nothing listening on the InfluxDB port: the sender connects lazily, on the first report,
        // so the reporter starts regardless -- which is what lets this run without a server.
        start(Map.of(ENABLED, "true", ENABLED + ".interval", "3600"));

        assertEquals(runtime.state(PLUGIN_ID).orElseThrow(), PluginState.ACTIVE);
        assertFalse(reporterThreads().isEmpty(),
                    "Enabled but no reporter thread; the platform property did not reach the plugin. "
                    + "Live threads: " + allThreadNames());
    }

    @Test(groups = "fast")
    public void testStoppingReleasesTheReporterThread() throws Exception {
        start(Map.of(ENABLED, "true", ENABLED + ".interval", "3600"));
        assertFalse(reporterThreads().isEmpty());

        runtime.manager().stopAll();

        assertTrue(awaitNoReporterThreads(),
                   "The reporter thread outlived the plugin, which pins its ClassLoader. "
                   + "Remaining: " + reporterThreads());
    }

    private void start(final Map<String, String> platformProperties) {
        runtime = PluginTestRuntime.create(
                Map.of(MetricRegistry.class, new KillBillCodahaleMetricRegistry(),
                       KillbillConfigSource.class, (KillbillConfigSource) platformProperties::get));
        runtime.installCompiled(Path.of("target", "classes"));
        runtime.discover();
        runtime.startAll();
    }

    /**
     * Dropwizard names the reporter's scheduler thread after the reporter, which is the only handle
     * a test has on it.
     */
    private static Set<String> reporterThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                     .map(Thread::getName)
                     .filter(name -> name.toLowerCase(java.util.Locale.ROOT).contains("influx"))
                     .collect(java.util.stream.Collectors.toSet());
    }

    private static Set<String> allThreadNames() {
        return Thread.getAllStackTraces().keySet().stream()
                     .map(Thread::getName)
                     .collect(java.util.stream.Collectors.toSet());
    }

    /** Shutdown is asynchronous, so poll rather than assume the thread is gone on return. */
    private static boolean awaitNoReporterThreads() throws InterruptedException {
        for (int i = 0; i < 100 && !reporterThreads().isEmpty(); i++) {
            Thread.sleep(20);
        }
        return reporterThreads().isEmpty();
    }
}

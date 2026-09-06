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

package org.killbill.billing.plugin.prometheus;

import java.nio.file.Path;
import java.util.Map;

import jakarta.servlet.Servlet;

import org.killbill.billing.lpr.api.PluginState;
import org.killbill.billing.lpr.testkit.PluginTestRuntime;
import org.killbill.commons.metrics.api.MetricRegistry;
import org.killbill.commons.metrics.dropwizard.KillBillCodahaleMetricRegistry;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Exposes Kill Bill's metrics in Prometheus text format at {@code /plugins/killbill-prometheus}.
 * <p>
 * The interesting hazard here is not the servlet -- it is that the collector registers itself into
 * Prometheus's <b>process-wide</b> {@code CollectorRegistry}, which lives outside the plugin's
 * ClassLoader and outlives the plugin unless something takes it out. Left behind, the stale
 * collector holds a reference to the old ClassLoader and double-reports every metric after a
 * reload. That unregistration is the property most worth asserting, because nothing else in the
 * system would notice it was missing.
 * <p>
 * Loaded from a jar built out of this module's compiled classes, so the plugin's classes come
 * through a plugin ClassLoader while {@code io.prometheus} and Kill Bill's {@code MetricRegistry}
 * resolve to the platform's copies.
 */
public class TestPrometheusPlugin {

    private static final String PLUGIN_ID = "killbill-prometheus";

    private PluginTestRuntime runtime;

    @AfterMethod(groups = "fast", alwaysRun = true)
    public void afterMethod() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test(groups = "fast")
    public void testItPublishesAServlet() {
        start();

        assertEquals(runtime.state(PLUGIN_ID).orElseThrow(), PluginState.ACTIVE);
        assertTrue(runtime.services().getPluginIds(Servlet.class).contains(PLUGIN_ID),
                   "Without a Servlet registration there is nothing at /plugins/" + PLUGIN_ID);
    }

    /**
     * The exported page has to contain Kill Bill's metrics, not merely be reachable. A scrape
     * endpoint that returns an empty body looks healthy to Prometheus and reports nothing.
     */
    @Test(groups = "fast")
    public void testItExportsMetricsFromTheKillbillRegistry() {
        final KillBillCodahaleMetricRegistry registry = new KillBillCodahaleMetricRegistry();
        registry.counter("kb.test.counter").inc(3);
        start(registry);

        final String exported = scrape();

        assertTrue(exported.contains("kb_test_counter"),
                   "The Kill Bill metric did not reach the Prometheus export:\n" + exported);
    }

    /**
     * The collector lives in process-wide state, so stopping the plugin must take it out again.
     * Otherwise a reload leaves the previous version's collector reporting alongside the new one.
     */
    @Test(groups = "fast")
    public void testStoppingUnregistersTheProcessWideCollector() {
        final KillBillCodahaleMetricRegistry registry = new KillBillCodahaleMetricRegistry();
        registry.counter("kb.leak.probe").inc(1);
        start(registry);
        assertTrue(scrape().contains("kb_leak_probe"));

        runtime.manager().stopAll();

        assertFalse(scrape().contains("kb_leak_probe"),
                    "The collector survived the plugin, so it still holds the old ClassLoader "
                    + "and will double-report after a reload");
    }

    /** Reads the default registry the plugin registered into, the way the servlet does. */
    private static String scrape() {
        final java.io.StringWriter out = new java.io.StringWriter();
        try {
            io.prometheus.client.exporter.common.TextFormat.write004(
                    out, io.prometheus.client.CollectorRegistry.defaultRegistry.metricFamilySamples());
        } catch (final java.io.IOException e) {
            throw new java.io.UncheckedIOException("Cannot render the Prometheus export", e);
        }
        return out.toString();
    }

    private void start() {
        start(new KillBillCodahaleMetricRegistry());
    }

    private void start(final MetricRegistry metricRegistry) {
        runtime = PluginTestRuntime.create(Map.of(MetricRegistry.class, metricRegistry));
        runtime.installCompiled(Path.of("target", "classes"));
        runtime.discover();
        runtime.startAll();
    }
}

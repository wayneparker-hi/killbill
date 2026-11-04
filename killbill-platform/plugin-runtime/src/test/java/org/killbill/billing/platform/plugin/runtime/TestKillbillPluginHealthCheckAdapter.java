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

import org.killbill.billing.lpr.api.Plugin;
import org.killbill.billing.lpr.api.PluginContext;
import org.killbill.billing.lpr.api.PluginHealthCheck;
import org.killbill.billing.lpr.core.LprMetricsCollector;
import org.killbill.billing.lpr.core.NoOpLprMetrics;
import org.killbill.billing.lpr.core.PluginHealthState;
import org.killbill.billing.lpr.core.RunningPlugin;
import org.killbill.billing.runtime.api.Healthcheck.HealthStatus;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TestKillbillPluginHealthCheckAdapter {

    @Test(groups = "fast")
    public void testHealthyStateIsAdapted() {
        final PluginHealthCheckManager manager = managerWithProbe(
                () -> PluginHealthCheck.HealthCheckResult.ok("ok"));
        final KillbillPluginHealthCheckAdapter adapter =
                new KillbillPluginHealthCheckAdapter("stripe", "1.0.0", manager);

        final HealthStatus status = adapter.getHealthStatus(null, null);

        assertTrue(status.isHealthy());
        assertEquals(status.getDetails().get("healthy"), "true");
        assertEquals(status.getDetails().get("message"), "ok");
    }

    @Test(groups = "fast")
    public void testUnhealthyStateIsAdapted() {
        final PluginHealthCheckManager manager = managerWithProbe(
                () -> PluginHealthCheck.HealthCheckResult.unhealthy("db down"));
        final KillbillPluginHealthCheckAdapter adapter =
                new KillbillPluginHealthCheckAdapter("stripe", "1.0.0", manager);

        final HealthStatus status = adapter.getHealthStatus(null, null);

        assertFalse(status.isHealthy());
        assertEquals(status.getDetails().get("healthy"), "false");
        assertEquals(status.getDetails().get("message"), "db down");
    }

    @Test(groups = "fast")
    public void testMissingStateReportsHealthy() {
        final PluginHealthCheckManager manager = emptyManager();
        final KillbillPluginHealthCheckAdapter adapter =
                new KillbillPluginHealthCheckAdapter("stripe", "1.0.0", manager);

        final HealthStatus status = adapter.getHealthStatus(null, null);

        assertTrue(status.isHealthy());
    }

    private PluginHealthCheckManager managerWithProbe(final PluginHealthCheck probe) {
        final Plugin plugin = new Plugin() {
            @Override
            public void start(final PluginContext context) { }

            @Override
            public void stop() { }

            @Override
            public java.util.Optional<PluginHealthCheck> healthCheck() {
                return java.util.Optional.of(probe);
            }
        };
        final RunningPlugin running = new RunningPlugin() {
            @Override
            public String pluginId() { return "stripe"; }

            @Override
            public String version() { return "1.0.0"; }

            @Override
            public Plugin instance() { return plugin; }

            @Override
            public ClassLoader classLoader() { return ClassLoader.getSystemClassLoader(); }
        };
        final PluginHealthCheckManager manager = new PluginHealthCheckManager(
                () -> List.of(running),
                config(),
                new LprMetricsCollector(NoOpLprMetrics.INSTANCE),
                java.util.concurrent.Executors.newSingleThreadExecutor(),
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor());
        manager.runChecksOnce();
        return manager;
    }

    private PluginHealthCheckManager emptyManager() {
        final StubConfig config = config();
        config.healthCheckEnabled = false;
        return new PluginHealthCheckManager(() -> java.util.List.of(),
                                            config,
                                            new LprMetricsCollector(NoOpLprMetrics.INSTANCE),
                                            java.util.concurrent.Executors.newSingleThreadExecutor(),
                                            java.util.concurrent.Executors.newSingleThreadScheduledExecutor());
    }

    private static StubConfig config() {
        return new StubConfig();
    }

    private static final class StubConfig extends TestPluginRuntimeConfig {

        boolean healthCheckEnabled = true;
        long healthCheckInterval = 30000L;
        long healthCheckTimeout = 5000L;

        @Override
        public boolean isPluginHealthCheckEnabled() {
            return healthCheckEnabled;
        }

        @Override
        public long getPluginHealthCheckInterval() {
            return healthCheckInterval;
        }

        @Override
        public long getPluginHealthCheckTimeout() {
            return healthCheckTimeout;
        }
    }
}

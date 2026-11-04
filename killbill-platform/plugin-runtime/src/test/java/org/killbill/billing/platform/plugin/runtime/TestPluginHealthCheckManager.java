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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.killbill.billing.lpr.api.Plugin;
import org.killbill.billing.lpr.api.PluginContext;
import org.killbill.billing.lpr.api.PluginHealthCheck;
import org.killbill.billing.lpr.core.LprMetricsCollector;
import org.killbill.billing.lpr.core.NoOpLprMetrics;
import org.killbill.billing.lpr.core.PluginHealthState;
import org.killbill.billing.lpr.core.RunningPlugin;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * Unit tests for {@link PluginHealthCheckManager}.
 */
public class TestPluginHealthCheckManager {

    private StubConfig config;
    private LprMetricsCollector metrics;
    private ExecutorService workerExecutor;
    private ScheduledExecutorService scheduler;

    @BeforeMethod
    public void setUp() {
        config = new StubConfig();
        config.healthCheckEnabled = true;
        config.healthCheckInterval = 100L;
        config.healthCheckTimeout = 200L;
        metrics = new LprMetricsCollector(NoOpLprMetrics.INSTANCE);
        workerExecutor = Executors.newCachedThreadPool();
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterMethod
    public void tearDown() {
        workerExecutor.shutdownNow();
        scheduler.shutdownNow();
    }

    @Test(groups = "fast")
    public void testHealthyProbeIsRecorded() throws Exception {
        final AtomicReference<ClassLoader> capturedTccl = new AtomicReference<>();
        final Plugin plugin = new PluginWithHealthCheck(() -> {
            capturedTccl.set(Thread.currentThread().getContextClassLoader());
            return PluginHealthCheck.HealthCheckResult.ok("all good");
        });
        final RunningPlugin running = runningPlugin("good", "1.0.0", plugin,
                                                    new ClassLoader("good-cl", ClassLoader.getSystemClassLoader()) { });

        final PluginHealthCheckManager manager = managerFor(() -> List.of(running));
        manager.runChecksOnce();

        final PluginHealthState state = manager.getState("good", "1.0.0").orElseThrow();
        assertTrue(state.healthy(), state.message());
        assertEquals(state.message(), "all good");
        assertEquals(capturedTccl.get(), running.classLoader());
    }

    @Test(groups = "fast")
    public void testUnhealthyProbeMarksPluginUnhealthy() {
        final Plugin plugin = new PluginWithHealthCheck(
                () -> PluginHealthCheck.HealthCheckResult.unhealthy("database down"));
        final RunningPlugin running = runningPlugin("sick", "1.0.0", plugin, ClassLoader.getSystemClassLoader());

        final PluginHealthCheckManager manager = managerFor(() -> List.of(running));
        manager.runChecksOnce();

        final PluginHealthState state = manager.getState("sick", "1.0.0").orElseThrow();
        assertFalse(state.healthy());
        assertEquals(state.message(), "database down");
        assertFalse(manager.isHealthy("sick", "1.0.0"));
    }

    @Test(groups = "fast")
    public void testProbeExceptionIsTreatedAsUnhealthy() {
        final Plugin plugin = new PluginWithHealthCheck(() -> {
            throw new RuntimeException("probe blew up");
        });
        final RunningPlugin running = runningPlugin("fragile", "1.0.0", plugin, ClassLoader.getSystemClassLoader());

        final PluginHealthCheckManager manager = managerFor(() -> List.of(running));
        manager.runChecksOnce();

        final PluginHealthState state = manager.getState("fragile", "1.0.0").orElseThrow();
        assertFalse(state.healthy());
        assertTrue(state.message().contains("probe blew up"));
    }

    @Test(groups = "fast")
    public void testSlowProbeTimesOut() throws Exception {
        final CountDownLatch interrupted = new CountDownLatch(1);
        final Plugin plugin = new PluginWithHealthCheck(() -> {
            try {
                Thread.sleep(10_000);
            } catch (final InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return PluginHealthCheck.HealthCheckResult.ok();
        });
        final RunningPlugin running = runningPlugin("slow", "1.0.0", plugin, ClassLoader.getSystemClassLoader());

        final PluginHealthCheckManager manager = managerFor(() -> List.of(running));
        manager.runChecksOnce();

        final PluginHealthState state = manager.getState("slow", "1.0.0").orElseThrow();
        assertFalse(state.healthy());
        assertTrue(state.message().contains("timed out"));
        assertTrue(interrupted.await(2, TimeUnit.SECONDS), "Slow probe should have been interrupted");
    }

    @Test(groups = "fast")
    public void testNoProbeMeansHealthy() {
        final Plugin plugin = new Plugin() {
            @Override
            public void start(final PluginContext context) { }

            @Override
            public void stop() { }
        };
        final RunningPlugin running = runningPlugin("noop", "1.0.0", plugin, ClassLoader.getSystemClassLoader());

        final PluginHealthCheckManager manager = managerFor(() -> List.of(running));
        manager.runChecksOnce();

        final PluginHealthState state = manager.getState("noop", "1.0.0").orElseThrow();
        assertTrue(state.healthy());
        assertTrue(manager.isHealthy("noop", "1.0.0"));
    }

    @Test(groups = "fast")
    public void testStoppedPluginsAreRemovedFromState() {
        final Plugin plugin = new PluginWithHealthCheck(
                () -> PluginHealthCheck.HealthCheckResult.unhealthy("sick"));
        final RunningPlugin running = runningPlugin("fading", "1.0.0", plugin, ClassLoader.getSystemClassLoader());
        final List<RunningPlugin> plugins = new ArrayList<>();
        plugins.add(running);

        final PluginHealthCheckManager manager = managerFor(() -> List.copyOf(plugins));
        manager.runChecksOnce();
        assertTrue(manager.getState("fading", "1.0.0").isPresent());

        plugins.clear();
        manager.runChecksOnce();
        assertTrue(manager.getState("fading", "1.0.0").isEmpty());
    }

    @Test(groups = "fast")
    public void testScheduledExecutionRunsRepeatedly() throws Exception {
        final CountDownLatch latch = new CountDownLatch(2);
        final Plugin plugin = new PluginWithHealthCheck(() -> {
            latch.countDown();
            return PluginHealthCheck.HealthCheckResult.ok();
        });
        final RunningPlugin running = runningPlugin("scheduled", "1.0.0", plugin, ClassLoader.getSystemClassLoader());

        final PluginHealthCheckManager manager = managerFor(() -> List.of(running));
        manager.start();

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Health check should have run at least twice");
        manager.stop();
    }

    @Test(groups = "fast")
    public void testDisabledHealthCheckDoesNotSchedule() throws Exception {
        config.healthCheckEnabled = false;
        final CountDownLatch latch = new CountDownLatch(1);
        final Plugin plugin = new PluginWithHealthCheck(() -> {
            latch.countDown();
            return PluginHealthCheck.HealthCheckResult.ok();
        });
        final RunningPlugin running = runningPlugin("disabled", "1.0.0", plugin, ClassLoader.getSystemClassLoader());

        final PluginHealthCheckManager manager = managerFor(() -> List.of(running));
        manager.start();

        assertFalse(latch.await(300, TimeUnit.MILLISECONDS), "Health check should not run when disabled");
        manager.stop();
    }

    @Test(groups = "fast")
    public void testAccessorFailureDoesNotCancelSchedule() throws Exception {
        final CountDownLatch attempts = new CountDownLatch(3);
        final Plugin plugin = new Plugin() {
            @Override public void start(final PluginContext context) { }
            @Override public void stop() { }
            @Override public java.util.Optional<PluginHealthCheck> healthCheck() {
                attempts.countDown();
                throw new IllegalStateException("accessor failed");
            }
        };
        final PluginHealthCheckManager manager = managerFor(() -> List.of(
                runningPlugin("accessor", "1", plugin, getClass().getClassLoader())));
        try {
            manager.start();
            manager.start();
            assertTrue(attempts.await(2, TimeUnit.SECONDS));
            manager.runChecksOnce();
            assertFalse(manager.isHealthy("accessor", "1"));
        } finally { manager.stop(); }
    }

    @Test(groups = "fast")
    public void testAccessorIsIncludedInTimeoutAndTcclBoundary() {
        final AtomicReference<ClassLoader> captured = new AtomicReference<>();
        final ClassLoader loader = new ClassLoader() { };
        final Plugin plugin = new Plugin() {
            @Override public void start(final PluginContext context) { }
            @Override public void stop() { }
            @Override public java.util.Optional<PluginHealthCheck> healthCheck() {
                captured.set(Thread.currentThread().getContextClassLoader());
                try { Thread.sleep(10_000); } catch (final InterruptedException e) { Thread.currentThread().interrupt(); }
                return java.util.Optional.empty();
            }
        };
        final PluginHealthCheckManager manager = managerFor(() -> List.of(runningPlugin("accessor", "1", plugin, loader)));
        manager.runChecksOnce();
        assertFalse(manager.isHealthy("accessor", "1"));
        assertEquals(captured.get(), loader);
    }

    @Test(groups = "fast")
    public void testUncooperativeProbeCannotOccupyAllWorkers() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        final Plugin stuck = new PluginWithHealthCheck(() -> {
            calls.incrementAndGet();
            try {
                while (release.getCount() > 0) {
                    try { release.await(); } catch (final InterruptedException ignored) { /* uncooperative fixture */ }
                }
                return PluginHealthCheck.HealthCheckResult.ok();
            } finally { finished.countDown(); }
        });
        final Plugin healthy = new PluginWithHealthCheck(() -> PluginHealthCheck.HealthCheckResult.ok());
        final PluginHealthCheckManager manager = new PluginHealthCheckManager(() -> List.of(
                runningPlugin("stuck", "1", stuck, getClass().getClassLoader()),
                runningPlugin("healthy", "1", healthy, getClass().getClassLoader())), new TestPluginRuntimeConfig() {
            @Override public int getPluginHealthCheckMaxThreads() { return 2; }
            @Override public long getPluginHealthCheckTimeout() { return 100; }
        }, metrics);
        try {
            for (int i = 0; i < 4; i++) { manager.runChecksOnce(); }
            assertEquals(calls.get(), 1);
            assertTrue(manager.isHealthy("healthy", "1"));
        } finally {
            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            manager.stop();
        }
    }

    private PluginHealthCheckManager managerFor(final Supplier<List<RunningPlugin>> supplier) {
        return new PluginHealthCheckManager(supplier, config, metrics, workerExecutor, scheduler);
    }

    private static RunningPlugin runningPlugin(final String pluginId,
                                               final String version,
                                               final Plugin instance,
                                               final ClassLoader classLoader) {
        return new RunningPlugin() {
            @Override
            public String pluginId() {
                return pluginId;
            }

            @Override
            public String version() {
                return version;
            }

            @Override
            public Plugin instance() {
                return instance;
            }

            @Override
            public ClassLoader classLoader() {
                return classLoader;
            }
        };
    }

    private static final class PluginWithHealthCheck implements Plugin {

        private final PluginHealthCheck probe;

        private PluginWithHealthCheck(final PluginHealthCheck probe) {
            this.probe = probe;
        }

        @Override
        public void start(final PluginContext context) { }

        @Override
        public void stop() { }

        @Override
        public java.util.Optional<PluginHealthCheck> healthCheck() {
            return java.util.Optional.of(probe);
        }
    }

    private static final class StubConfig extends TestPluginRuntimeConfig {

        boolean healthCheckEnabled = true;
        long healthCheckInterval = 100L;
        long healthCheckTimeout = 200L;

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

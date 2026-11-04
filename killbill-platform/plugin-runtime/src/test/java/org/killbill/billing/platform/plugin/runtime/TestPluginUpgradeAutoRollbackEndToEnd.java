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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.killbill.billing.lpr.api.EventBus;
import org.killbill.billing.lpr.api.PluginHealthCheck;
import org.killbill.billing.lpr.core.LprMetricsCollector;
import org.killbill.billing.lpr.core.NoOpLprMetrics;
import org.killbill.billing.lpr.testkit.PluginFixture;
import org.killbill.billing.lpr.testkit.PluginTestRuntime;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * End-to-end verification that an unhealthy upgrade is rolled back automatically and that traffic
 * is never left without a provider.
 */
public class TestPluginUpgradeAutoRollbackEndToEnd {

    /** Stands in for a Kill Bill plugin SPI. In {@code org.killbill.*}, so it resolves parent-first. */
    public interface PaymentGateway {
        String charge(String amount);
    }

    @Test(groups = "fast")
    public void testUnhealthyUpgradeIsRolledBackWithoutWindow() throws Exception {
        try (PluginTestRuntime runtime = PluginTestRuntime.create()) {
            installVersion(runtime, "1.0.0", v1PluginSource());
            runtime.startAll();
            assertEquals(runtime.manager().get("upgrade-payment").orElseThrow().version(), "1.0.0");

            installVersion(runtime, "2.0.0", unhealthyV2PluginSource());

            final UpgradeWatch watch = new UpgradeWatch(runtime, rollbackConfig());
            try {
                runtime.manager().activateVersion("upgrade-payment", "2.0.0");

                // The candidate started, but its health probe is failing, so the registry hides it
                // and the previous version keeps serving.
                assertEquals(service(runtime), "v1.0.0 charged 1.00",
                             "Traffic should still reach the healthy previous version");

                watch.awaitRollback("upgrade-payment", "1.0.0", 2_000);

                assertEquals(runtime.manager().get("upgrade-payment").orElseThrow().version(), "1.0.0");
                assertEquals(service(runtime), "v1.0.0 charged 1.00");

                final List<PluginActivationEvent> events = watch.events();
                assertTrue(events.stream().anyMatch(e -> e.type() == PluginActivationEvent.Type.UPGRADE
                                                         && "2.0.0".equals(e.toVersion())),
                           "Upgrade audit event should be published");
                assertTrue(events.stream().anyMatch(e -> e.type() == PluginActivationEvent.Type.ROLLBACK
                                                         && "1.0.0".equals(e.toVersion())),
                           "Rollback audit event should be published");
            } finally {
                watch.stop();
            }
        }
    }

    @Test(groups = "fast")
    public void testHealthyUpgradeIsNotRolledBack() throws Exception {
        try (PluginTestRuntime runtime = PluginTestRuntime.create()) {
            installVersion(runtime, "1.0.0", v1PluginSource());
            runtime.startAll();

            installVersion(runtime, "2.0.0", healthyV2PluginSource());

            final UpgradeWatch watch = new UpgradeWatch(runtime, rollbackConfig());
            try {
                runtime.manager().activateVersion("upgrade-payment", "2.0.0");

                watch.awaitVersion("upgrade-payment", "2.0.0", 2_000);
                assertEquals(service(runtime), "v2.0.0 charged 1.00");

                final List<PluginActivationEvent> events = watch.events();
                assertTrue(events.stream().anyMatch(e -> e.type() == PluginActivationEvent.Type.UPGRADE
                                                         && "2.0.0".equals(e.toVersion())),
                           "Upgrade audit event should be published");
                assertTrue(events.stream().noneMatch(e -> e.type() == PluginActivationEvent.Type.ROLLBACK),
                           "No rollback should be published for a healthy upgrade");
            } finally {
                watch.stop();
            }
        }
    }

    @Test(groups = "fast")
    public void testLaterFailureRollsBackWithoutProviderGap() throws Exception {
        try (PluginTestRuntime runtime = PluginTestRuntime.create()) {
            installVersion(runtime, "1.0.0", v1PluginSource());
            runtime.startAll();
            final String source = healthyV2PluginSource()
                    .replace("public class UpgradePlugin implements Plugin {", "public class UpgradePlugin implements Plugin { private int checks;")
                    .replace("PluginHealthCheck.HealthCheckResult.ok()", "++checks == 1 ? PluginHealthCheck.HealthCheckResult.ok() : PluginHealthCheck.HealthCheckResult.unhealthy(\"late failure\")");
            installVersion(runtime, "2.0.0", source);
            final UpgradeWatch watch = new UpgradeWatch(runtime, rollbackConfig());
            final List<String> gaps = new CopyOnWriteArrayList<>();
            final java.util.concurrent.atomic.AtomicBoolean observing = new java.util.concurrent.atomic.AtomicBoolean(true);
            runtime.services().addListener(new org.killbill.billing.lpr.core.ServiceRegistryListener() {
                @Override public void onRegistered(final org.killbill.billing.lpr.api.ServiceReference<?> reference) { check(); }
                @Override public void onUnregistered(final org.killbill.billing.lpr.api.ServiceReference<?> reference) { check(); }
                @Override public void onVisibilityChanged() { check(); }
                private void check() {
                    if (observing.get() && runtime.services().getService(PaymentGateway.class, "upgrade-payment").isEmpty()) {
                        gaps.add("No provider during route change");
                    }
                }
            });
            try {
                runtime.manager().activateVersion("upgrade-payment", "2.0.0");
                watch.awaitRollback("upgrade-payment", "1.0.0", 3_000);
                assertEquals(service(runtime), "v1.0.0 charged 1.00");
                assertTrue(gaps.isEmpty(), gaps.toString());
                assertTrue(watch.events().stream().anyMatch(e -> "late failure".equals(e.reason())));
            } finally {
                observing.set(false);
                watch.stop();
            }
        }
    }

    @Test(groups = "fast")
    public void testHealthyWindowRetiresOldRuntimeAndStaleRollbackIsRejected() throws Exception {
        try (PluginTestRuntime runtime = PluginTestRuntime.create()) {
            installVersion(runtime, "1.0.0", v1PluginSource());
            runtime.startAll();
            installVersion(runtime, "2.0.0", healthyV2PluginSource());
            installVersion(runtime, "3.0.0", healthyV2PluginSource().replace("v2.0.0", "v3.0.0"));
            final UpgradeWatch watch = new UpgradeWatch(runtime, rollbackConfig());
            try {
                runtime.manager().activateVersion("upgrade-payment", "2.0.0");
                assertEquals(runtime.manager().runningPlugins().size(), 2);
                runtime.manager().activateVersion("upgrade-payment", "3.0.0");
                assertTrue(!runtime.manager().rollbackIfCurrent("upgrade-payment", "2.0.0", "stale callback"));
                final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                while (runtime.manager().runningPlugins().size() != 1 && System.nanoTime() < deadline) { Thread.sleep(20); }
                assertEquals(runtime.manager().runningPlugins().size(), 1, "Old runtime must retire after the observation window");
                assertEquals(service(runtime), "v3.0.0 charged 1.00");
            } finally { watch.stop(); }
        }
    }

    private static String service(final PluginTestRuntime runtime) {
        return runtime.services()
                      .getService(PaymentGateway.class, "upgrade-payment")
                      .orElseThrow(() -> new IllegalStateException("No payment gateway available"))
                      .charge("1.00");
    }

    private static void installVersion(final PluginTestRuntime runtime,
                                       final String version,
                                       final String source) {
        runtime.install(PluginFixture.plugin("upgrade-payment", version)
                                     .entrypoint("com.upgrade.UpgradePlugin", source));
    }

    private static PluginRuntimeConfig rollbackConfig() {
        return new TestPluginRuntimeConfig() {
            @Override
            public long getPluginUpgradeHealthCheckInterval() {
                return 50L;
            }

            @Override
            public long getPluginUpgradeHealthCheckWindow() {
                return 500L;
            }

            @Override
            public long getPluginHealthCheckTimeout() {
                return 500L;
            }
        };
    }

    private static String header() {
        return "package com.upgrade;\n"
               + "import org.killbill.billing.lpr.api.Plugin;\n"
               + "import org.killbill.billing.lpr.api.PluginContext;\n"
               + "import org.killbill.billing.platform.plugin.runtime.TestPluginUpgradeAutoRollbackEndToEnd.PaymentGateway;\n";
    }

    private static String v1PluginSource() {
        return header()
               + "public class UpgradePlugin implements Plugin {\n"
               + "    @Override public void start(PluginContext context) {\n"
               + "        context.services().register(PaymentGateway.class,\n"
               + "            amount -> \"v" + "1.0.0" + " charged \" + amount);\n"
               + "    }\n"
               + "    @Override public void stop() { }\n"
               + "}\n";
    }

    private static String unhealthyV2PluginSource() {
        return header()
               + "import org.killbill.billing.lpr.api.PluginHealthCheck;\n"
               + "import java.util.Optional;\n"
               + "public class UpgradePlugin implements Plugin {\n"
               + "    @Override public void start(PluginContext context) {\n"
               + "        boolean incumbent = context.services().getService(PaymentGateway.class, context.pluginId()).isPresent();\n"
               + "        context.services().register(PaymentGateway.class,\n"
               + "            amount -> \"v2.0.0 (incumbent was serving: \" + incumbent + \")\");\n"
               + "    }\n"
               + "    @Override public void stop() { }\n"
               + "    @Override public Optional<PluginHealthCheck> healthCheck() {\n"
               + "        return Optional.of(() -> PluginHealthCheck.HealthCheckResult.unhealthy(\"simulated failure\"));\n"
               + "    }\n"
               + "}\n";
    }

    private static String healthyV2PluginSource() {
        return header()
               + "import org.killbill.billing.lpr.api.PluginHealthCheck;\n"
               + "import java.util.Optional;\n"
               + "public class UpgradePlugin implements Plugin {\n"
               + "    @Override public void start(PluginContext context) {\n"
               + "        context.services().register(PaymentGateway.class,\n"
               + "            amount -> \"v2.0.0 charged \" + amount);\n"
               + "    }\n"
               + "    @Override public void stop() { }\n"
               + "    @Override public Optional<PluginHealthCheck> healthCheck() {\n"
               + "        return Optional.of(() -> PluginHealthCheck.HealthCheckResult.ok());\n"
               + "    }\n"
               + "}\n";
    }

    /**
     * Combines a real runtime with the health check manager and upgrade monitor that are normally
     * wired in {@link PluginRuntimeModule}.
     */
    private static final class UpgradeWatch {

        private final PluginTestRuntime runtime;
        private final PluginHealthCheckManager healthCheckManager;
        private final PluginUpgradeMonitor monitor;
        private final List<PluginActivationEvent> events = new CopyOnWriteArrayList<>();

        UpgradeWatch(final PluginTestRuntime runtime, final PluginRuntimeConfig config) {
            this.runtime = runtime;
            final LprMetricsCollector metrics = new LprMetricsCollector(NoOpLprMetrics.INSTANCE);
            this.healthCheckManager = new PluginHealthCheckManager(runtime.manager()::runningPlugins,
                                                                   config,
                                                                   metrics);
            runtime.services().setHealthPredicate(healthCheckManager::isHealthy);
            healthCheckManager.addListener(runtime.services()::refreshVisibility);
            this.monitor = new PluginUpgradeMonitor(runtime.manager(),
                                                    healthCheckManager,
                                                    config,
                                                    runtime.eventBus(),
                                                    metrics);
            runtime.eventBus().subscribe(PluginActivationEvent.class, events::add);
        }

        void awaitRollback(final String pluginId, final String expectedVersion, final long timeoutMillis)
                throws InterruptedException {
            final long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                if (expectedVersion.equals(runtime.manager().get(pluginId).map(p -> p.version()).orElse(null))) {
                    return;
                }
                Thread.sleep(20L);
            }
            throw new AssertionError("Rollback did not complete within " + timeoutMillis + " ms");
        }

        void awaitVersion(final String pluginId, final String expectedVersion, final long timeoutMillis)
                throws InterruptedException {
            final long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                if (expectedVersion.equals(runtime.manager().get(pluginId).map(p -> p.version()).orElse(null))) {
                    return;
                }
                Thread.sleep(20L);
            }
            throw new AssertionError("Version did not switch to " + expectedVersion + " within " + timeoutMillis + " ms");
        }

        List<PluginActivationEvent> events() {
            return new ArrayList<>(events);
        }

        void stop() {
            monitor.stop();
            healthCheckManager.stop();
        }
    }
}

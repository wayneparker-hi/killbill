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

package org.killbill.billing.lpr.testkit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.killbill.billing.lpr.core.ServiceRegistryListener;
import org.killbill.billing.lpr.api.ServiceReference;
import org.killbill.billing.lpr.management.ArtifactSource;
import org.killbill.billing.lpr.management.PluginInstaller;
import org.killbill.billing.lpr.testkit.TestLifecycleFaultInjection.Control;
import org.killbill.billing.lpr.testkit.TestLifecycleFaultInjection.Gateway;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TestConcurrentPluginOperations {

    @Test(groups = "fast")
    public void installDiscoverActivateAndRollbackKeepOneLiveVersionAndConsistentMarker() throws Exception {
        final Control control = new Control();
        try (PluginTestRuntime runtime = PluginTestRuntime.create(Map.of(Control.class, control))) {
            runtime.install(version("1.0.0"));
            runtime.install(version("2.0.0"));
            runtime.setActiveVersion("raced", "1.0.0");
            runtime.startAll();
            runtime.manager().activateVersion("raced", "2.0.0");
            final List<String> gaps = new CopyOnWriteArrayList<>();
            final AtomicBoolean observing = new AtomicBoolean(true);
            watchRoutes(runtime, observing, gaps);
            try {
                raceOperations(runtime);
                runtime.manager().activateVersion("raced", "3.0.0");
                assertEquals(runtime.manager().get("raced").orElseThrow().version(), "3.0.0");
                assertEquals(runtime.service(Gateway.class, "raced").value(), "3.0.0");
                assertEquals(activeMarker(runtime), "3.0.0");
                assertEquals(control.duplicates.get(), 0, "The same version was loaded twice");
                assertTrue(gaps.isEmpty(), gaps.toString());
            } finally { observing.set(false); }
            runtime.manager().stopAll();
            assertTrue(runtime.services().getServices(Gateway.class).isEmpty());
            assertTrue(control.active.values().stream().allMatch(value -> value.get() == 0), control.active.toString());
            for (final String version : List.of("1.0.0", "2.0.0", "3.0.0")) {
                assertFalse(runtime.manager().isVersionLoaded("raced", version));
            }
        }
    }

    private static String activeMarker(final PluginTestRuntime runtime) throws Exception {
        final java.util.Properties marker = new java.util.Properties();
        try (var input = Files.newInputStream(runtime.pluginsDirectory().resolve("raced/ACTIVE"))) {
            marker.load(input);
        }
        return marker.getProperty("active");
    }

    private static void raceOperations(final PluginTestRuntime runtime) throws Exception {
        final Path artifact = Files.createTempFile("lpr-concurrent-", ".jar");
        final PluginFixture fixture = version("3.0.0");
        new PluginJarBuilder().build(fixture.sources(), artifact);
        final CountDownLatch go = new CountDownLatch(1);
        final var workers = Executors.newFixedThreadPool(4);
        try {
            final List<Future<?>> attempts = new ArrayList<>();
            attempts.add(workers.submit(() -> {
                await(go);
                new PluginInstaller(runtime.repository()).install("raced", "3.0.0", ArtifactSource.of(artifact),
                        "id: raced\nversion: 3.0.0\nentrypoint:\n  class: com.fault.FaultPlugin\n");
                runtime.discover();
            }));
            attempts.add(workers.submit(() -> {
                await(go);
                for (int i = 0; i < 20; i++) { runtime.manager().activateVersion("raced", i % 2 == 0 ? "1.0.0" : "2.0.0"); }
            }));
            attempts.add(workers.submit(() -> {
                await(go);
                for (int i = 0; i < 20; i++) { runtime.manager().rollback("raced"); }
            }));
            attempts.add(workers.submit(() -> {
                await(go);
                for (int i = 0; i < 20; i++) { runtime.discover(); }
            }));
            go.countDown();
            for (final Future<?> attempt : attempts) { attempt.get(20, TimeUnit.SECONDS); }
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            Files.deleteIfExists(artifact);
        }
    }

    private static PluginFixture version(final String version) {
        return TestLifecycleFaultInjection.fixture("raced", version, """
                control.started(context.version());
                context.resources().onClose(() -> control.stopped(context.version()));
                """, "");
    }

    private static void await(final CountDownLatch latch) {
        try { latch.await(); }
        catch (final InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    private static void watchRoutes(final PluginTestRuntime runtime, final AtomicBoolean observing,
                                     final List<String> gaps) {
        runtime.services().addListener(new ServiceRegistryListener() {
            @Override public void onRegistered(final ServiceReference<?> reference) { check(); }
            @Override public void onUnregistered(final ServiceReference<?> reference) { check(); }
            @Override public void onVisibilityChanged() { check(); }
            private void check() {
                if (observing.get() && runtime.services().getService(Gateway.class, "raced").isEmpty()) {
                    gaps.add("No provider during version operation");
                }
            }
        });
    }
}

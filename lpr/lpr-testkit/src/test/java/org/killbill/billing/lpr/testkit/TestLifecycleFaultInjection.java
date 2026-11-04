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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.killbill.billing.lpr.api.PluginState;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/** Real jars, independent loaders, and controlled faults; no permanently blocked test threads. */
public class TestLifecycleFaultInjection {

    public interface Gateway { String value(); }

    public static final class Control {
        public final CountDownLatch stopEntered = new CountDownLatch(1);
        public final CountDownLatch release = new CountDownLatch(1);
        public final CountDownLatch lateLoaded = new CountDownLatch(1);
        public final AtomicInteger released = new AtomicInteger();
        public final AtomicInteger lateRejected = new AtomicInteger();
        public final Map<String, AtomicInteger> active = new ConcurrentHashMap<>();
        public final AtomicInteger duplicates = new AtomicInteger();
        public java.lang.ref.WeakReference<ClassLoader> loader;

        public void started(final String version) {
            if (active.computeIfAbsent(version, key -> new AtomicInteger()).incrementAndGet() != 1) {
                duplicates.incrementAndGet();
            }
        }

        public void stopped(final String version) { active.get(version).decrementAndGet(); }

        public void blockStop() {
            stopEntered.countDown();
            while (release.getCount() != 0) {
                try { release.await(); } catch (final InterruptedException ignored) { }
            }
        }
    }

    @Test(groups = "fast")
    public void startupOutOfMemoryErrorDoesNotStrandServicesResourcesOrOtherPlugins() {
        final Control control = new Control();
        try (PluginTestRuntime runtime = PluginTestRuntime.create(Map.of(Control.class, control))) {
            runtime.install(fixture("a-broken", "1.0.0", """
                context.resources().onClose(control.released::incrementAndGet);
                context.resources().onClose(() -> { throw new AssertionError("release failure"); });
                throw new OutOfMemoryError("injected plugin OOM");
                """, ""));
            runtime.install(fixture("z-good", "1.0.0", "", ""));
            runtime.discover();
            assertEquals(runtime.manager().startAll(List.of()), List.of("z-good"));
            assertEquals(runtime.state("a-broken").orElseThrow(), PluginState.FAILED);
            assertTrue(runtime.manager().get("a-broken").orElseThrow().failureReason().contains("injected plugin OOM"));
            assertFalse(runtime.manager().isVersionLoaded("a-broken", "1.0.0"));
            assertTrue(runtime.services().getService(Gateway.class, "a-broken").isEmpty());
            assertEquals(control.released.get(), 1);
            assertEquals(runtime.service(Gateway.class, "z-good").value(), "1.0.0");
        }
    }

    @Test(groups = "fast")
    public void stopErrorStillReleasesResourcesAndLoader() {
        final Control control = new Control();
        try (PluginTestRuntime runtime = PluginTestRuntime.create(Map.of(Control.class, control))) {
            runtime.install(fixture("stop-error", "1.0.0",
                    "context.resources().onClose(control.released::incrementAndGet);",
                    "throw new AssertionError(\"injected stop failure\");"));
            runtime.startAll();
            assertTrue(runtime.manager().stop("stop-error"));
            assertEquals(runtime.state("stop-error").orElseThrow(), PluginState.STOPPED);
            assertFalse(runtime.manager().isVersionLoaded("stop-error", "1.0.0"));
            assertEquals(control.released.get(), 1);
        }
    }

    @Test(groups = "fast")
    public void timedOutStopKeepsLoaderUntilExitAndCannotRepublishOrReload() throws Exception {
        final Control control = new Control();
        try (PluginTestRuntime runtime = PluginTestRuntime.create(Map.of(Control.class, control), 100)) {
            runtime.install(fixture("blocked", "1.0.0",
                    "context.resources().onClose(control.released::incrementAndGet);", """
                    control.blockStop();
                    Class.forName("com.fault.Late", true, getClass().getClassLoader());
                    control.lateLoaded.countDown();
                    try { saved.services().register(Gateway.class, () -> "late"); }
                    catch (IllegalStateException expected) { control.lateRejected.incrementAndGet(); }
                    """).withClass("com.fault.Late", "package com.fault; public class Late { }"));
            runtime.install(fixture("other", "1.0.0",
                    "context.resources().onClose(control.released::incrementAndGet);", ""));
            runtime.startAll();
            try {
                final long before = System.nanoTime();
                runtime.manager().stopAll();
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 2000,
                           "Blocked stop held the host shutdown");
                assertTrue(control.stopEntered.await(2, TimeUnit.SECONDS));
                assertEquals(runtime.state("blocked").orElseThrow(), PluginState.STOPPING);
                assertEquals(runtime.state("other").orElseThrow(), PluginState.STOPPED);
                assertTrue(runtime.services().getService(Gateway.class, "blocked").isEmpty());
                assertTrue(runtime.manager().isVersionLoaded("blocked", "1.0.0"));
                assertEquals(control.released.get(), 1, "Blocked callback's resources were released early");
                runtime.discover();
                assertEquals(runtime.state("blocked").orElseThrow(), PluginState.STOPPING);
                expectThrows(IllegalStateException.class, () -> runtime.manager().start("blocked"));
                expectThrows(IllegalStateException.class, () -> runtime.manager().activateVersion("blocked", "1.0.0"));
            } finally {
                control.release.countDown();
                awaitStopped(runtime, "blocked");
            }
            assertEquals(control.lateLoaded.getCount(), 0L, "Loader was closed before stop exited");
            assertEquals(control.lateRejected.get(), 1, "Old context republished after withdrawal");
            assertEquals(control.released.get(), 2);
            assertFalse(runtime.manager().isVersionLoaded("blocked", "1.0.0"));
        } finally { control.release.countDown(); }
    }

    @Test(groups = "fast")
    public void stoppedLoaderIsCollectableWhileStopPoolRemainsAlive() throws Exception {
        final Control control = new Control();
        try (PluginTestRuntime runtime = PluginTestRuntime.create(Map.of(Control.class, control))) {
            stopCollectablePlugin(runtime);
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (control.loader.get() != null && System.nanoTime() < deadline) {
                System.gc();
                Thread.sleep(20);
            }
            assertNull(control.loader.get(), "Idle stop worker retained the plugin loader");
        }
    }

    private static void stopCollectablePlugin(final PluginTestRuntime runtime) {
        runtime.install(fixture("collectable", "1.0.0",
                "control.loader = new java.lang.ref.WeakReference<>(getClass().getClassLoader());", ""));
        runtime.startAll();
        runtime.manager().stop("collectable");
        assertFalse(runtime.manager().isVersionLoaded("collectable", "1.0.0"));
    }

    @Test(groups = "fast")
    public void retiredVersionCannotReloadUntilTimedOutStopActuallyExits() throws Exception {
        final Control control = new Control();
        try (PluginTestRuntime runtime = PluginTestRuntime.create(Map.of(Control.class, control), 100)) {
            runtime.install(fixture("upgrade", "1.0.0", "", "control.blockStop();"));
            runtime.install(fixture("upgrade", "2.0.0", "", ""));
            runtime.setActiveVersion("upgrade", "1.0.0");
            runtime.startAll();
            try {
                runtime.manager().activateVersion("upgrade", "2.0.0");
                assertTrue(control.stopEntered.await(2, TimeUnit.SECONDS));
                assertEquals(runtime.service(Gateway.class, "upgrade").value(), "2.0.0");
                expectThrows(IllegalStateException.class, () -> runtime.manager().activateVersion("upgrade", "1.0.0"));
                assertEquals(runtime.service(Gateway.class, "upgrade").value(), "2.0.0");
            } finally { control.release.countDown(); }
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (runtime.manager().isVersionLoaded("upgrade", "1.0.0") && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertFalse(runtime.manager().isVersionLoaded("upgrade", "1.0.0"));
            runtime.manager().activateVersion("upgrade", "1.0.0");
            assertEquals(runtime.service(Gateway.class, "upgrade").value(), "1.0.0");
        } finally { control.release.countDown(); }
    }

    @Test(groups = "fast")
    public void rejectedRetirementStillRetainsOldInstanceForRetry() throws Exception {
        final Control control = new Control();
        try (PluginTestRuntime runtime = PluginTestRuntime.create(Map.of(Control.class, control), 100, 1)) {
            runtime.install(fixture("blocker", "1.0.0", "", "control.blockStop();"));
            runtime.install(fixture("upgrade", "1.0.0", "", ""));
            runtime.install(fixture("upgrade", "2.0.0", "", ""));
            runtime.setActiveVersion("upgrade", "1.0.0");
            runtime.startAll();
            try {
                runtime.manager().stop("blocker");
                assertTrue(control.stopEntered.await(2, TimeUnit.SECONDS));
                expectThrows(java.util.concurrent.RejectedExecutionException.class,
                        () -> runtime.manager().activateVersion("upgrade", "2.0.0"));
                assertEquals(runtime.service(Gateway.class, "upgrade").value(), "2.0.0");
                assertTrue(runtime.manager().isVersionLoaded("upgrade", "1.0.0"), "Lost rejected retirement");
            } finally {
                control.release.countDown();
                awaitStopped(runtime, "blocker");
            }
            runtime.manager().stopAll();
            assertFalse(runtime.manager().isVersionLoaded("upgrade", "1.0.0"));
            assertFalse(runtime.manager().isVersionLoaded("upgrade", "2.0.0"));
            assertTrue(runtime.services().getServices(Gateway.class).isEmpty());
        } finally { control.release.countDown(); }
    }

    @Test(groups = "fast")
    public void blockedResourceCleanupAlsoRespectsCallerDeadline() throws Exception {
        final Control control = new Control();
        try (PluginTestRuntime runtime = PluginTestRuntime.create(Map.of(Control.class, control), 100)) {
            runtime.install(fixture("resource-blocked", "1.0.0", """
                    context.resources().onClose(control.released::incrementAndGet);
                    context.resources().onClose(control::blockStop);
                    """, ""));
            runtime.startAll();
            try {
                runtime.manager().stop("resource-blocked");
                assertTrue(control.stopEntered.await(2, TimeUnit.SECONDS));
                assertEquals(runtime.state("resource-blocked").orElseThrow(), PluginState.STOPPING);
                assertEquals(control.released.get(), 0);
                assertTrue(runtime.manager().isVersionLoaded("resource-blocked", "1.0.0"));
            } finally {
                control.release.countDown();
                awaitStopped(runtime, "resource-blocked");
            }
            assertEquals(control.released.get(), 1);
            assertFalse(runtime.manager().isVersionLoaded("resource-blocked", "1.0.0"));
        } finally { control.release.countDown(); }
    }

    public static PluginFixture fixture(final String id, final String version,
                                         final String startFault, final String stopFault) {
        return PluginFixture.plugin(id, version).entrypoint("com.fault.FaultPlugin", """
                package com.fault;
                import org.killbill.billing.lpr.api.*;
                import org.killbill.billing.lpr.testkit.TestLifecycleFaultInjection.Gateway;
                import org.killbill.billing.lpr.testkit.TestLifecycleFaultInjection.Control;
                public class FaultPlugin implements Plugin {
                    private Control control;
                    private PluginContext saved;
                    public void start(PluginContext context) {
                        saved = context;
                        control = context.getPlatformService(Control.class);
                        context.services().register(Gateway.class, context::version);
                        %s
                    }
                    public void stop() throws Exception { %s }
                }
                """.formatted(startFault, stopFault));
    }

    private static void awaitStopped(final PluginTestRuntime runtime, final String id) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (runtime.state(id).orElseThrow() != PluginState.STOPPED && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(runtime.state(id).orElseThrow(), PluginState.STOPPED);
    }
}

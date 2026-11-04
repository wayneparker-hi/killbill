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

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.killbill.billing.lpr.api.PluginContext;
import org.killbill.billing.lpr.api.Subscription;
import org.killbill.billing.lpr.classloader.DefaultPluginClassLoaderFactory;
import org.killbill.billing.lpr.core.DefaultPluginLifecycleManager;
import org.killbill.billing.lpr.core.DefaultServiceRegistry;
import org.killbill.billing.lpr.core.PluginRuntime;
import org.killbill.billing.lpr.event.DefaultEventBus;
import org.killbill.billing.lpr.lifecycle.DefaultPluginStopExecutor;
import org.killbill.billing.lpr.spi.ClassLoaderPolicy;
import org.killbill.billing.lpr.spi.PluginArtifact;
import org.killbill.billing.lpr.spi.PluginDescriptor;
import org.killbill.billing.lpr.testkit.PluginJarBuilder;
import org.killbill.bus.api.PersistentBus;
import org.testng.annotations.Test;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.*;

public class TestDurablePluginEventIsolation {
    @Test(groups = "fast")
    public void testRealPluginContextTcclStopAndRetainedClosedHandleUnloading() throws Exception {
        final Path root = Files.createTempDirectory("lpr-durable-isolation-");
        final var registry = new DefaultServiceRegistry();
        try (final var adapter = new PersistentPluginEventBus(mock(PersistentBus.class), registry, TestPersistentPluginEventBus.enabled());
             final var stopExecutor = new DefaultPluginStopExecutor(1, 5000)) {
            adapter.start();
            final Probe probe = loadUseStop(root, registry, adapter, stopExecutor);
            final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (probe.loader().get() != null && System.nanoTime() < deadline) {
                System.gc();
                Thread.sleep(50);
            }
            assertNull(probe.loader().get(), "Closed subscriptions/context must not retain plugin loader");
            assertFalse(probe.subscription().isActive());
            assertThrows(IllegalStateException.class, () -> probe.context().durableEvents().subscribe("late", ignored -> {}));
        } finally {
            try (var paths = Files.walk(root)) {
                for (final var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
            }
        }
    }

    private Probe loadUseStop(final Path root, final DefaultServiceRegistry registry, final PersistentPluginEventBus adapter,
                              final DefaultPluginStopExecutor stopExecutor) throws Exception {
        final String entrypoint = "org.killbill.billing.plugin.durable.TestPlugin";
        final Path jar = new PluginJarBuilder().build(Map.of(entrypoint, source()), root.resolve("plugin.jar"));
        final var retained = new AtomicReference<Subscription>();
        final var count = new AtomicInteger();
        final Map<String, Object> captured = new java.util.HashMap<>();
        final var lifecycle = new DefaultPluginLifecycleManager(new DefaultPluginClassLoaderFactory(), ClassLoaderPolicy.defaultPolicy(),
                getClass().getClassLoader(), registry, new DefaultEventBus(),
                Map.of(AtomicReference.class, retained, AtomicInteger.class, count, Map.class, captured), null, stopExecutor, adapter);
        final var runtime = new PluginRuntime(PluginDescriptor.of("target", "1.0.0", entrypoint), PluginArtifact.of("target", "1.0.0", jar));
        assertTrue(lifecycle.start(runtime));
        final var loader = (ClassLoader) captured.remove("loader");
        assertNotSame(loader, getClass().getClassLoader());
        final var previous = Thread.currentThread().getContextClassLoader();
        adapter.onEvent(TestPersistentPluginEventBus.envelope("target", "jobs"));
        assertEquals(count.get(), 1);
        assertSame(Thread.currentThread().getContextClassLoader(), previous);
        assertThrows(IllegalArgumentException.class, () -> adapter.onEvent(TestPersistentPluginEventBus.envelope("target", "failure")));
        assertSame(Thread.currentThread().getContextClassLoader(), previous);
        final var context = (PluginContext) captured.remove("context");
        assertTrue(lifecycle.stop(runtime));
        assertThrows(IllegalStateException.class, () -> adapter.onEvent(TestPersistentPluginEventBus.envelope("target", "jobs")));
        assertEquals(runtime.state(), org.killbill.billing.lpr.api.PluginState.STOPPED);
        return new Probe(new WeakReference<>(loader), retained.get(), context);
    }

    private record Probe(WeakReference<ClassLoader> loader, Subscription subscription, PluginContext context) {}

    private static String source() {
        return """
                package org.killbill.billing.plugin.durable;
                import org.killbill.billing.lpr.api.*;
                import java.util.concurrent.atomic.*;
                public final class TestPlugin implements Plugin {
                    public void start(PluginContext context) {
                        context.getPlatformService(java.util.Map.class).put("context", context);
                        context.getPlatformService(java.util.Map.class).put("loader", getClass().getClassLoader());
                        DurableEventBus bus = context.durableEvents();
                        if (bus != context.durableEvents()) throw new IllegalStateException("Scope not cached");
                        AtomicInteger count = context.getPlatformService(AtomicInteger.class);
                        Subscription sub = bus.subscribe("jobs", event -> {
                            if (Thread.currentThread().getContextClassLoader() != getClass().getClassLoader()) {
                                throw new IllegalStateException("Wrong plugin TCCL");
                            }
                            count.incrementAndGet();
                        });
                        context.getPlatformService(AtomicReference.class).set(sub);
                        bus.subscribe("failure", event -> { throw new IllegalArgumentException("retry"); });
                    }
                    public void stop() {}
                }
                """;
    }
}

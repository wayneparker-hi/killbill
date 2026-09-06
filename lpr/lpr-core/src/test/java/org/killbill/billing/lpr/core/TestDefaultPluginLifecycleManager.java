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

package org.killbill.billing.lpr.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.killbill.billing.lpr.api.Plugin;
import org.killbill.billing.lpr.api.PluginContext;
import org.killbill.billing.lpr.api.PluginState;
import org.killbill.billing.lpr.spi.ClassLoaderPolicy;
import org.killbill.billing.lpr.spi.PluginArtifact;
import org.killbill.billing.lpr.spi.PluginClassLoaderFactory;
import org.killbill.billing.lpr.spi.PluginClassLoaderHandle;
import org.killbill.billing.lpr.spi.PluginDescriptor;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

public class TestDefaultPluginLifecycleManager {

    /** What a plugin publishes, standing in for PaymentPluginApi and friends. */
    public interface Gateway {
        String charge();
    }

    /** Records what each plugin saw, so tests can assert on ordering rather than on side effects. */
    private static final List<String> journal = new ArrayList<>();

    private DefaultServiceRegistry serviceRegistry;
    private DefaultEventBus eventBus;
    private StubClassLoaderFactory classLoaderFactory;
    private DefaultPluginLifecycleManager lifecycle;

    @BeforeMethod(groups = "fast")
    public void beforeMethod() {
        journal.clear();
        RecordingPlugin.registryForAssertions = null;
        serviceRegistry = new DefaultServiceRegistry();
        eventBus = new DefaultEventBus();
        classLoaderFactory = new StubClassLoaderFactory();
        lifecycle = new DefaultPluginLifecycleManager(classLoaderFactory,
                                                      ClassLoaderPolicy.defaultPolicy(),
                                                      getClass().getClassLoader(),
                                                      serviceRegistry,
                                                      eventBus,
                                                      Map.of(Gateway.class, (Gateway) () -> "platform"));
    }

    @Test(groups = "fast")
    public void testStartMovesThePluginToActiveAndPublishesItsServices() {
        final PluginRuntime runtime = runtimeFor(RecordingPlugin.class);
        assertEquals(runtime.state(), PluginState.INSTALLED);

        assertTrue(lifecycle.start(runtime));

        assertEquals(runtime.state(), PluginState.ACTIVE);
        assertEquals(journal, List.of("start"));
        assertEquals(serviceRegistry.getService(Gateway.class, "recording").orElseThrow().charge(), "recording");
    }

    /**
     * The ordering requirement that makes shutdown safe. A plugin's services must already be
     * unreachable when its {@code stop()} runs, so no new caller can enter code whose ClassLoader
     * is about to close; and its resources must be released only afterwards, so the plugin can
     * still use them while winding down.
     */
    @Test(groups = "fast")
    public void testStopWithdrawsServicesBeforeCallingThePluginAndReleasesResourcesAfter() {
        final PluginRuntime runtime = runtimeFor(RecordingPlugin.class);
        RecordingPlugin.registryForAssertions = serviceRegistry;
        lifecycle.start(runtime);

        assertTrue(lifecycle.stop(runtime));

        assertEquals(journal, List.of(
                "start",
                "stop:services-already-withdrawn",
                "resource-released"));
        assertEquals(runtime.state(), PluginState.STOPPED);
    }

    /**
     * After a stop the runtime must hold nothing belonging to the plugin. Keeping the instance or
     * its context around would pin the ClassLoader and make unloading a claim rather than a fact.
     */
    @Test(groups = "fast")
    public void testStopDropsEveryReferenceToTheLoadedPlugin() {
        final PluginRuntime runtime = runtimeFor(RecordingPlugin.class);
        lifecycle.start(runtime);

        lifecycle.stop(runtime);

        assertNull(runtime.instance());
        assertNull(runtime.context());
        assertNull(runtime.classLoaderHandle());
        assertTrue(classLoaderFactory.lastHandle.closed);
    }

    /**
     * A plugin that fails halfway through start must not leave services behind for the core to
     * call, nor a ClassLoader nobody will close.
     */
    @Test(groups = "fast")
    public void testAFailedStartIsRolledBackCompletely() {
        final PluginRuntime runtime = runtimeFor(FailingStartPlugin.class);

        assertThrows(IllegalStateException.class, () -> lifecycle.start(runtime));

        assertEquals(runtime.state(), PluginState.FAILED);
        assertTrue(runtime.failureReason().isPresent());
        assertTrue(serviceRegistry.getService(Gateway.class, "failing-start").isEmpty(),
                   "A service registered before the failure was left reachable");
        assertTrue(classLoaderFactory.lastHandle.closed, "ClassLoader of a failed plugin was not closed");
        assertNull(runtime.instance());
    }

    /**
     * A plugin that throws on the way down must not block the teardown of the rest of the system,
     * and must not stay ACTIVE.
     */
    @Test(groups = "fast")
    public void testAPluginThrowingOnStopStillCompletesTeardown() {
        final PluginRuntime runtime = runtimeFor(FailingStopPlugin.class);
        lifecycle.start(runtime);

        assertTrue(lifecycle.stop(runtime));

        assertEquals(runtime.state(), PluginState.STOPPED);
        assertTrue(serviceRegistry.getService(Gateway.class, "failing-stop").isEmpty());
        assertTrue(classLoaderFactory.lastHandle.closed);
    }

    /**
     * Plugins can be started by the boot sequence, by an operator through the management API and by
     * a cluster node command at the same time. Exactly one of those may actually run the plugin.
     */
    @Test(groups = "fast")
    public void testConcurrentStartsProduceExactlyOneStartedPlugin() throws Exception {
        final PluginRuntime runtime = runtimeFor(CountingPlugin.class);
        CountingPlugin.startCount.set(0);

        final int racers = 8;
        final CountDownLatch go = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            final List<Callable<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                attempts.add(() -> {
                    go.await();
                    return lifecycle.start(runtime);
                });
            }
            final var futures = attempts.stream().map(pool::submit).toList();
            go.countDown();

            int winners = 0;
            for (final var future : futures) {
                if (future.get(10, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertEquals(winners, 1, "More than one caller believed it started the plugin");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(CountingPlugin.startCount.get(), 1, "Plugin.start() ran more than once");
        assertEquals(runtime.state(), PluginState.ACTIVE);
    }

    @Test(groups = "fast")
    public void testStartingAnAlreadyActivePluginIsANoOp() {
        final PluginRuntime runtime = runtimeFor(RecordingPlugin.class);
        assertTrue(lifecycle.start(runtime));

        assertFalse(lifecycle.start(runtime));
        assertEquals(journal, List.of("start"));
    }

    @Test(groups = "fast")
    public void testStoppingAPluginThatIsNotRunningIsANoOp() {
        final PluginRuntime runtime = runtimeFor(RecordingPlugin.class);

        assertFalse(lifecycle.stop(runtime));
        assertEquals(runtime.state(), PluginState.INSTALLED);
    }

    @Test(groups = "fast")
    public void testRestartStopsThenStarts() {
        final PluginRuntime runtime = runtimeFor(RecordingPlugin.class);
        lifecycle.start(runtime);

        lifecycle.restart(runtime);

        assertEquals(journal, List.of("start", "stop:services-already-withdrawn", "resource-released", "start"));
        assertEquals(runtime.state(), PluginState.ACTIVE);
    }

    /**
     * A stopped plugin can be started again, which is what an operator toggling a plugin expects
     * and what {@code restart} relies on.
     */
    @Test(groups = "fast")
    public void testAStoppedPluginCanBeStartedAgain() {
        final PluginRuntime runtime = runtimeFor(RecordingPlugin.class);
        lifecycle.start(runtime);
        lifecycle.stop(runtime);

        assertTrue(lifecycle.start(runtime));
        assertEquals(runtime.state(), PluginState.ACTIVE);
        assertTrue(serviceRegistry.getService(Gateway.class, "recording").isPresent());
    }

    /**
     * Plugin code runs with its own ClassLoader as the thread context ClassLoader: ServiceLoader,
     * JDBC drivers and reflective libraries all reach for the TCCL and would otherwise search the
     * core's classpath. The caller's TCCL must be restored afterwards.
     */
    @Test(groups = "fast")
    public void testPluginCodeRunsWithItsOwnContextClassLoaderAndRestoresTheCallers() {
        final PluginRuntime runtime = runtimeFor(ContextClassLoaderProbePlugin.class);
        final ClassLoader before = Thread.currentThread().getContextClassLoader();

        lifecycle.start(runtime);

        assertSame(ContextClassLoaderProbePlugin.observedDuringStart,
                   classLoaderFactory.lastHandle.classLoader(),
                   "Plugin.start() did not see its own ClassLoader as the TCCL");
        assertSame(Thread.currentThread().getContextClassLoader(), before,
                   "The caller's context ClassLoader was not restored");
    }

    private PluginRuntime runtimeFor(final Class<? extends Plugin> entrypoint) {
        final String pluginId = pluginIdOf(entrypoint);
        return new PluginRuntime(
                PluginDescriptor.of(pluginId, "1.0.0", entrypoint.getName()),
                PluginArtifact.of(pluginId, "1.0.0", Path.of("target", pluginId + ".jar")));
    }

    private static String pluginIdOf(final Class<?> entrypoint) {
        // CamelCase simple name, minus the Plugin suffix, as a lowercase dashed id.
        final String base = entrypoint.getSimpleName().replaceAll("Plugin$", "");
        return base.replaceAll("(?<!^)([A-Z])", "-$1").toLowerCase(java.util.Locale.ROOT);
    }

    // ----------------------------------------------------------------------------------------
    // Test plugins. Loaded through the stub factory from the test's own ClassLoader, so these
    // exercise the lifecycle rather than the ClassLoader backend -- that is covered separately by
    // the isolation and unloading tests in lpr-classloader-default.
    // ----------------------------------------------------------------------------------------

    public static class RecordingPlugin implements Plugin {

        static DefaultServiceRegistry registryForAssertions;

        @Override
        public void start(final PluginContext context) {
            journal.add("start");
            context.services().register(Gateway.class, () -> "recording");
            context.resources().onClose(() -> journal.add("resource-released"));
        }

        @Override
        public void stop() {
            // Observed from inside the plugin: by the time stop() runs, the runtime must already
            // have made this plugin's services unreachable.
            final boolean stillReachable = registryForAssertions != null
                                           && registryForAssertions.getService(Gateway.class, "recording").isPresent();
            journal.add(stillReachable ? "stop:services-STILL-REACHABLE" : "stop:services-already-withdrawn");
        }
    }

    public static class FailingStartPlugin implements Plugin {

        @Override
        public void start(final PluginContext context) {
            context.services().register(Gateway.class, () -> "failing-start");
            throw new IllegalStateException("cannot reach the payment gateway");
        }

        @Override
        public void stop() {
        }
    }

    public static class FailingStopPlugin implements Plugin {

        @Override
        public void start(final PluginContext context) {
            context.services().register(Gateway.class, () -> "failing-stop");
        }

        @Override
        public void stop() {
            throw new IllegalStateException("cannot flush pending state");
        }
    }

    public static class CountingPlugin implements Plugin {

        static final AtomicInteger startCount = new AtomicInteger();

        @Override
        public void start(final PluginContext context) {
            startCount.incrementAndGet();
        }

        @Override
        public void stop() {
        }
    }

    public static class ContextClassLoaderProbePlugin implements Plugin {

        static ClassLoader observedDuringStart;

        @Override
        public void start(final PluginContext context) {
            observedDuringStart = Thread.currentThread().getContextClassLoader();
        }

        @Override
        public void stop() {
        }
    }

    /**
     * Hands back a ClassLoader that simply delegates to the test's own, so lifecycle behaviour can
     * be tested without building fixture jars. Isolation itself is verified against the real
     * backend in lpr-classloader-default.
     */
    private static final class StubClassLoaderFactory implements PluginClassLoaderFactory {

        private StubHandle lastHandle;

        @Override
        public PluginClassLoaderHandle create(final PluginArtifact artifact,
                                              final ClassLoaderPolicy policy,
                                              final ClassLoader parent) {
            lastHandle = new StubHandle(artifact.pluginId(),
                                        new java.net.URLClassLoader("stub-" + artifact.pluginId(),
                                                                    new java.net.URL[0],
                                                                    parent));
            return lastHandle;
        }

        @Override
        public String backendName() {
            return "stub";
        }
    }

    private static final class StubHandle implements PluginClassLoaderHandle {

        private final String pluginId;
        private final ClassLoader classLoader;
        private boolean closed;

        private StubHandle(final String pluginId, final ClassLoader classLoader) {
            this.pluginId = pluginId;
            this.classLoader = classLoader;
        }

        @Override
        public String pluginId() {
            return pluginId;
        }

        @Override
        public ClassLoader classLoader() {
            return classLoader;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}

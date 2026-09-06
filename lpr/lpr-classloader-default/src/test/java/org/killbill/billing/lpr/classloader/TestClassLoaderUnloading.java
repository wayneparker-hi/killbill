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

package org.killbill.billing.lpr.classloader;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.killbill.billing.lpr.spi.ClassLoaderPolicy;
import org.killbill.billing.lpr.spi.PluginArtifact;
import org.killbill.billing.lpr.spi.PluginClassLoaderFactory;
import org.killbill.billing.lpr.spi.PluginClassLoaderHandle;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * Whether stopping a plugin actually unloads it.
 * <p>
 * There is no {@code ClassLoader.unload()}. A plugin's classes go away only when its ClassLoader
 * becomes unreachable and the collector gets to it -- so "we stopped the plugin" and "the plugin is
 * gone" are different claims, and only the second one prevents a metaspace leak across repeated
 * reloads. Without a test, the difference is invisible until a long-running server falls over.
 * <p>
 * The second test here is the load-bearing one: it demonstrates that a single leaked thread is
 * enough to pin everything, which is the entire reason {@code ResourceRegistry} exists rather than
 * being left to plugin authors' discipline.
 */
public class TestClassLoaderUnloading {

    private static final String GREETER = "com.example.lib.Greeter";
    private static final long GC_TIMEOUT_MILLIS = 10_000L;

    private final PluginClassLoaderFactory factory = new DefaultPluginClassLoaderFactory();

    private Path workDir;
    private Path jar;

    @BeforeClass(groups = "fast")
    public void beforeClass() throws Exception {
        workDir = Files.createTempDirectory("lpr-unloading-");
        jar = TestPluginJarBuilder.newJar()
                                  .withClass(GREETER, greeterSource())
                                  .buildInto(workDir, "plugin.jar");
    }

    /**
     * The baseline: load a plugin, use it, close it, drop it -- and the ClassLoader is collectable.
     * If this ever fails, the runtime itself is retaining something and no amount of plugin-side
     * discipline will fix it.
     */
    @Test(groups = "fast")
    public void testClassLoaderIsCollectedAfterCloseWhenNothingRetainsIt() throws Exception {
        final WeakReference<ClassLoader> ref = loadUseAndRelease();

        assertTrue(awaitCollected(ref),
                   "Plugin ClassLoader survived collection with nothing referencing it: "
                   + "the runtime is retaining plugin state, so unloading is a no-op and reloads leak metaspace");
    }

    /**
     * The failure mode {@code ResourceRegistry} exists to prevent, and a second, less obvious one.
     * <p>
     * A running thread executing plugin code keeps its stack frames -- and therefore the plugin's
     * classes and ClassLoader -- reachable from a GC root. Stopping the plugin and closing its
     * ClassLoader changes nothing while that thread lives.
     * <p>
     * Stopping the thread is necessary but <b>not sufficient</b>. On JDK 21 a terminated
     * {@code Thread} object still strongly references its {@code Runnable}, so everything the task
     * captured stays reachable for as long as anyone holds the {@code Thread}. (Older JDKs cleared
     * {@code target} in {@code Thread.exit()}; the virtual-thread rework dropped that.) Verified on
     * this JDK, not assumed -- the two phases below fail independently if it ever changes.
     * <p>
     * The consequence for the runtime: {@code ResourceRegistry} must <em>release</em> executors and
     * threads, not merely shut them down. An {@code ExecutorService} that was shut down but is
     * still referenced keeps its workers' tasks alive, and with them the plugin's ClassLoader.
     */
    @Test(groups = "fast")
    public void testLeakedThreadKeepsClassLoaderAliveUntilItStopsAndIsReleased() throws Exception {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        LeakyPlugin leaky = startPluginLeakingAThread(started, release);
        Thread pluginThread = leaky.thread();
        final WeakReference<ClassLoader> classLoaderRef = leaky.classLoaderRef();
        leaky = null;

        assertTrue(started.await(5, TimeUnit.SECONDS), "Plugin thread never started");

        // Phase 1: the thread is running. Nothing the runtime does can unload this plugin.
        assertFalse(awaitCollected(classLoaderRef, 1_000L),
                    "Expected the running thread to pin the ClassLoader; if this passes, the test no "
                    + "longer demonstrates anything and should be rewritten");

        // Phase 2: the thread has terminated, but we still hold the Thread object -- which is what
        // an ExecutorService that was shut down but not dereferenced looks like. Still pinned.
        release.countDown();
        pluginThread.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(pluginThread.isAlive(), "Plugin thread did not stop");
        assertFalse(awaitCollected(classLoaderRef, 1_000L),
                    "A terminated Thread no longer pins its Runnable on this JDK; the runtime's "
                    + "'release, do not merely shut down' rule can be relaxed, and this test rewritten");

        // Phase 3: the last reference goes. Only now is the plugin genuinely unloadable.
        pluginThread = null;
        assertTrue(awaitCollected(classLoaderRef),
                   "ClassLoader still not collectable after its thread stopped and was released");
    }

    /**
     * Closing must be safe to call more than once: the runtime closes on the stop path, and again
     * on the failure path when a stop went wrong.
     */
    @Test(groups = "fast")
    public void testCloseIsIdempotent() {
        final PluginClassLoaderHandle handle =
                factory.create(PluginArtifact.of("plugin-close", "1.0.0", jar),
                               ClassLoaderPolicy.defaultPolicy(),
                               getClass().getClassLoader());
        handle.close();
        handle.close();
    }

    /**
     * Loads a class through a fresh plugin ClassLoader and lets every strong reference die with
     * this frame. Only the weak reference escapes -- keeping any of these in the caller's scope
     * would make the test pass or fail for the wrong reason.
     */
    private WeakReference<ClassLoader> loadUseAndRelease() throws Exception {
        final PluginClassLoaderHandle handle =
                factory.create(PluginArtifact.of("plugin-collect", "1.0.0", jar),
                               ClassLoaderPolicy.defaultPolicy(),
                               getClass().getClassLoader());

        final ClassLoader classLoader = handle.classLoader();
        final Class<?> greeter = classLoader.loadClass(GREETER);
        final Object instance = greeter.getDeclaredConstructor().newInstance();
        assertNotNull(greeter.getMethod("greet").invoke(instance));

        final WeakReference<ClassLoader> ref = new WeakReference<>(classLoader);
        handle.close();
        return ref;
    }

    private LeakyPlugin startPluginLeakingAThread(final CountDownLatch started,
                                                  final CountDownLatch release) throws Exception {
        final PluginClassLoaderHandle handle =
                factory.create(PluginArtifact.of("plugin-leaky", "1.0.0", jar),
                               ClassLoaderPolicy.defaultPolicy(),
                               getClass().getClassLoader());

        final ClassLoader classLoader = handle.classLoader();
        final Class<?> greeter = classLoader.loadClass(GREETER);
        final Object instance = greeter.getDeclaredConstructor().newInstance();

        // A plugin-owned thread holding a plugin object, exactly what `new Thread(...)` inside a
        // plugin produces when nobody tracks it.
        final Thread thread = new Thread(() -> {
            started.countDown();
            try {
                release.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Keeps `instance` -- and its ClassLoader -- reachable for the thread's whole life.
            instance.toString();
        }, "lpr-leaky-plugin-thread");
        thread.setDaemon(true);
        thread.start();

        handle.close();
        return new LeakyPlugin(thread, new WeakReference<>(classLoader));
    }

    private boolean awaitCollected(final WeakReference<?> ref) {
        return awaitCollected(ref, GC_TIMEOUT_MILLIS);
    }

    /**
     * {@code System.gc()} is a hint, so poll rather than assume a single call is enough. Allocating
     * between attempts raises pressure and makes the collector far likelier to act.
     */
    private boolean awaitCollected(final WeakReference<?> ref, final long timeoutMillis) {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (ref.get() == null) {
                return true;
            }
            System.gc();
            allocateGarbage();
            try {
                Thread.sleep(50L);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return ref.get() == null;
    }

    /** Volatile so the writes cannot be optimised away as dead stores. */
    private static volatile Object gcPressureSink;

    private void allocateGarbage() {
        for (int i = 0; i < 16; i++) {
            gcPressureSink = new byte[256 * 1024];
        }
        gcPressureSink = null;
    }

    private static String greeterSource() {
        return "package com.example.lib;\n"
               + "public class Greeter {\n"
               + "    public String greet() { return \"hello\"; }\n"
               + "}\n";
    }

    private record LeakyPlugin(Thread thread, WeakReference<ClassLoader> classLoaderRef) {
    }
}

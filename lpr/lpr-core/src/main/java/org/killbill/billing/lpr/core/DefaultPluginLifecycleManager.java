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

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

import org.killbill.billing.lpr.api.EventBus;
import org.killbill.billing.lpr.spi.DurableEventBusFactory;
import org.killbill.billing.lpr.api.Plugin;
import org.killbill.billing.lpr.api.PluginContext;
import org.killbill.billing.lpr.api.PluginHealthCheck;
import org.killbill.billing.lpr.api.PluginState;
import org.killbill.billing.lpr.spi.ClassLoaderPolicy;
import org.killbill.billing.lpr.spi.PluginClassLoaderFactory;
import org.killbill.billing.lpr.spi.PluginClassLoaderHandle;
import org.killbill.billing.lpr.spi.PluginStopExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives plugins between states, and is the only thing allowed to.
 * <p>
 * The two sequences below are the whole class, and their <b>order</b> is the part that matters.
 * Stopping in the wrong order is the classic way to turn a clean shutdown into a crash: close the
 * ClassLoader while a request is still inside the plugin and the caller gets a
 * {@code NoClassDefFoundError} from code that was working a millisecond earlier.
 * <pre>
 *   start                          stop
 *   -----                          ----
 *   CAS to STARTING                CAS to STOPPING
 *   build ClassLoader              1. withdraw services   (cut off new callers first)
 *   load entrypoint                2. Plugin.stop()       (let it finish its own work)
 *   build context                  3. release resources   (threads, subscriptions, connections)
 *   Plugin.start()                 4. close ClassLoader   (stop callback has actually returned)
 *   ACTIVE                         5. drop references     (or nothing is ever unloaded)
 *                                  STOPPED
 * </pre>
 * Plugin code runs with the plugin's own ClassLoader as the thread context ClassLoader, because
 * {@code ServiceLoader}, JDBC drivers and most reflective libraries reach for the TCCL and would
 * otherwise search the core's classpath for the plugin's classes.
 */
public class DefaultPluginLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultPluginLifecycleManager.class);

    private final PluginClassLoaderFactory classLoaderFactory;
    private final ClassLoaderPolicy policy;
    private final ClassLoader parentClassLoader;
    private final DefaultServiceRegistry serviceRegistry;
    private final EventBus eventBus;
    private final DurableEventBusFactory durableEventBusFactory;
    private final Map<Class<?>, Object> platformServices;
    private final LprMetricsCollector metrics;
    private final PluginStopExecutor stopExecutor;

    public DefaultPluginLifecycleManager(final PluginClassLoaderFactory classLoaderFactory,
                                         final ClassLoaderPolicy policy,
                                         final ClassLoader parentClassLoader,
                                         final DefaultServiceRegistry serviceRegistry,
                                         final EventBus eventBus,
                                         final Map<Class<?>, Object> platformServices,
                                         final PluginStopExecutor stopExecutor) {
        this(classLoaderFactory, policy, parentClassLoader, serviceRegistry, eventBus, platformServices, null, stopExecutor);
    }

    public DefaultPluginLifecycleManager(final PluginClassLoaderFactory classLoaderFactory,
                                         final ClassLoaderPolicy policy,
                                         final ClassLoader parentClassLoader,
                                         final DefaultServiceRegistry serviceRegistry,
                                         final EventBus eventBus,
                                         final Map<Class<?>, Object> platformServices,
                                         final LprMetricsCollector metrics,
                                         final PluginStopExecutor stopExecutor) {
        this(classLoaderFactory, policy, parentClassLoader, serviceRegistry, eventBus, platformServices, metrics, stopExecutor, null);
    }

    public DefaultPluginLifecycleManager(final PluginClassLoaderFactory classLoaderFactory, final ClassLoaderPolicy policy,
                                         final ClassLoader parentClassLoader, final DefaultServiceRegistry serviceRegistry,
                                         final EventBus eventBus, final Map<Class<?>, Object> platformServices,
                                         final LprMetricsCollector metrics, final PluginStopExecutor stopExecutor,
                                         final DurableEventBusFactory durableEventBusFactory) {
        this.durableEventBusFactory = durableEventBusFactory;
        this.classLoaderFactory = Objects.requireNonNull(classLoaderFactory, "classLoaderFactory");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.parentClassLoader = Objects.requireNonNull(parentClassLoader, "parentClassLoader");
        this.serviceRegistry = Objects.requireNonNull(serviceRegistry, "serviceRegistry");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.platformServices = Map.copyOf(Objects.requireNonNullElse(platformServices, Map.of()));
        this.stopExecutor = Objects.requireNonNull(stopExecutor, "stopExecutor");
        this.metrics = metrics != null ? metrics : new LprMetricsCollector(NoOpLprMetrics.INSTANCE);
    }

    /**
     * Starts a plugin.
     * <p>
     * Returns quietly if it is already {@link PluginState#ACTIVE} or if another thread is starting
     * it: "make sure this is running" is the useful contract for something reachable from boot, an
     * operator action and a cluster command at once.
     *
     * @param runtime the plugin to start
     * @return true if this call started it
     * @throws IllegalStateException if the plugin cannot be started from its current state
     */
    public boolean start(final PluginRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");

        final PluginState current = runtime.state();
        if (current == PluginState.ACTIVE || current == PluginState.STARTING) {
            return false;
        }
        if (current != PluginState.INSTALLED && current != PluginState.RESOLVED
            && current != PluginState.STOPPED && current != PluginState.FAILED) {
            throw new IllegalStateException("Cannot start plugin " + runtime.pluginId() + " from " + current);
        }
        if (!runtime.transition(current, PluginState.STARTING)) {
            // Lost the race; whoever won is starting it.
            return false;
        }

        PluginClassLoaderHandle handle = null;
        final long startNanos = System.nanoTime();
        try {
            handle = classLoaderFactory.create(runtime.artifact(), policy, parentClassLoader);
            metrics.classLoaderCreated();
            final Plugin instance = instantiate(runtime, handle.classLoader());

            final DefaultResourceRegistry resources = new DefaultResourceRegistry(runtime.pluginId(), metrics);
            final PluginContext context = new DefaultPluginContext(
                    runtime.pluginId(),
                    runtime.version(),
                    new DefaultPluginConfig(runtime.pluginId(), runtime.descriptor().config()),
                    runtime.artifact().root(),
                    runtime.artifact().jar(),
                    new PluginServiceScope(serviceRegistry, runtime.pluginId(), runtime.version()),
                    eventBus,
                    resources,
                    platformServices, durableEventBusFactory);

            runtime.loaded(handle, instance, context, resources);
            runWithPluginClassLoader(handle.classLoader(), () -> instance.start(context));

            runtime.transition(PluginState.STARTING, PluginState.ACTIVE);
            registerHealthCheck(runtime, instance, context);
            metrics.recordPluginStart(System.nanoTime() - startNanos);
            log.info("Started plugin {} version {}", runtime.pluginId(), runtime.version());
            return true;

        } catch (final Throwable e) {
            // Roll back whatever got as far as being registered. A plugin that failed halfway
            // through start() must not leave services behind for the core to call.
            log.error("Failed to start plugin {} version {}", runtime.pluginId(), runtime.version(), e);
            withdrawServices(runtime);
            releaseResources(runtime);
            closeQuietly(handle);
            if (handle != null) {
                metrics.classLoaderDestroyed();
            }
            runtime.unloaded();
            runtime.markFailed(e.getMessage() != null ? e.getMessage() : e.toString());
            metrics.recordPluginStart(System.nanoTime() - startNanos);
            throw new IllegalStateException("Plugin " + runtime.pluginId() + " failed to start", e);
        }
    }

    /**
     * Stops a plugin, following the teardown order above.
     * <p>
     * Returns quietly if it is not running, so that shutting down a partially started system does
     * not need to know which plugins made it up.
     *
     * @param runtime the plugin to stop
     * @return true if this call accepted teardown; a timed-out callback remains STOPPING
     */
    public boolean stop(final PluginRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");

        if (!runtime.transition(PluginState.ACTIVE, PluginState.STOPPING)) {
            // FAILED plugins may still hold a ClassLoader from a start that got partway.
            if (runtime.state() == PluginState.FAILED && runtime.classLoaderHandle() != null) {
                cleanUp(runtime);
                runtime.transition(PluginState.FAILED, PluginState.STOPPED);
                return true;
            }
            return false;
        }

        try {
            stopExecutor.execute(() -> tearDown(runtime));
        } catch (final TimeoutException e) {
            withdrawServices(runtime);
            log.warn("Plugin {} {} stop timed out; cleanup continues in STOPPING", runtime.pluginId(), runtime.version());
        } catch (final InterruptedException e) {
            withdrawServices(runtime);
            Thread.currentThread().interrupt();
            log.warn("Interrupted waiting for plugin {} stop; cleanup continues", runtime.pluginId());
        } catch (final RejectedExecutionException e) {
            runtime.transition(PluginState.STOPPING, PluginState.ACTIVE);
            throw e;
        }
        return true;
    }

    private void tearDown(final PluginRuntime runtime) {
        final long stopNanos = System.nanoTime();
        withdrawServices(runtime);
        final Plugin instance = runtime.instance();
        final PluginClassLoaderHandle handle = runtime.classLoaderHandle();
        try {
            if (instance != null && handle != null) {
                runWithPluginClassLoader(handle.classLoader(), instance::stop);
            }
        } catch (final Throwable e) {
            log.warn("Plugin {} threw while stopping; continuing teardown", runtime.pluginId(), e);
        } finally {
            // Interruption is a request to stop the callback, not to skip resource cleanup.
            final boolean interrupted = Thread.interrupted();
            try {
                cleanUp(runtime);
                runtime.transition(PluginState.STOPPING, PluginState.STOPPED);
                metrics.recordPluginStop(System.nanoTime() - stopNanos);
            } finally {
                if (interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private void withdrawServices(final PluginRuntime runtime) {
        final PluginContext context = runtime.context();
        if (context != null && context.services() instanceof PluginServiceScope scope) {
            scope.close();
        } else {
            serviceRegistry.unregisterAll(runtime.pluginId(), runtime.version());
        }
    }

    /**
     * Stop then start.
     * <p>
     * There is a window here in which the plugin serves nothing. That is acceptable for an operator
     * action; upgrades that must not lose availability use side-by-side loading and an atomic
     * switch instead, which is why {@code PluginClassLoaderFactory} hands out an independent
     * ClassLoader per call.
     *
     * @param runtime the plugin to restart
     */
    public void restart(final PluginRuntime runtime) {
        stop(runtime);
        start(runtime);
    }

    /** Steps 3 to 5 of the teardown, shared by the stop and failure paths. */
    private void cleanUp(final PluginRuntime runtime) {
        releaseResources(runtime);
        final PluginClassLoaderHandle handle = runtime.classLoaderHandle();
        if (handle != null) {
            closeQuietly(handle);
            metrics.classLoaderDestroyed();
        }
        runtime.unloaded();
    }

    private void releaseResources(final PluginRuntime runtime) {
        final DefaultResourceRegistry resources = runtime.resources();
        if (resources != null) {
            resources.closeAll();
        }
    }

    private void closeQuietly(final PluginClassLoaderHandle handle) {
        if (handle != null) {
            handle.close();
        }
    }

    private void registerHealthCheck(final PluginRuntime runtime,
                                     final Plugin instance,
                                     final PluginContext context) {
        try {
            // Discover the optional capability without executing plugin code on the start thread.
            if (instance.getClass().getMethod("healthCheck").getDeclaringClass() == Plugin.class) {
                return;
            }
            context.services().register(PluginHealthCheck.class, () -> {
                final Optional<PluginHealthCheck> probe = Objects.requireNonNull(instance.healthCheck(), "healthCheck");
                return probe.isPresent() ? probe.get().check() : PluginHealthCheck.HealthCheckResult.ok("No health probe configured");
            });
        } catch (final ReflectiveOperationException | RuntimeException e) {
            log.warn("Failed to register health check for plugin {} version {}", runtime.pluginId(), runtime.version(), e);
        }
    }

    private Plugin instantiate(final PluginRuntime runtime, final ClassLoader classLoader) throws Exception {
        final String entrypoint = runtime.descriptor().entrypointClass();
        final Class<?> entrypointClass = classLoader.loadClass(entrypoint);

        if (!Plugin.class.isAssignableFrom(entrypointClass)) {
            // Almost always a ClassLoader problem rather than a coding one: the plugin compiled
            // against Plugin, but its jar shipped a copy that the policy failed to treat as
            // parent-first, so the two are unrelated types at runtime.
            throw new IllegalStateException(
                    "Entrypoint " + entrypoint + " of plugin " + runtime.pluginId()
                    + " does not implement " + Plugin.class.getName()
                    + " (loaded by " + describeClassLoader(entrypointClass.getClassLoader())
                    + ", API loaded by " + describeClassLoader(Plugin.class.getClassLoader()) + ')');
        }
        return (Plugin) entrypointClass.getDeclaredConstructor().newInstance();
    }

    /**
     * Runs plugin code with its own ClassLoader as the thread context ClassLoader, restoring the
     * caller's afterwards so the runtime's own thread is left as it was found.
     */
    private void runWithPluginClassLoader(final ClassLoader classLoader, final PluginAction action) throws Exception {
        final Thread current = Thread.currentThread();
        final ClassLoader original = current.getContextClassLoader();
        try {
            current.setContextClassLoader(classLoader);
            action.run();
        } finally {
            current.setContextClassLoader(original);
        }
    }

    private static String describeClassLoader(final ClassLoader classLoader) {
        return classLoader == null ? "bootstrap" : classLoader.toString();
    }

    @FunctionalInterface
    private interface PluginAction {
        void run() throws Exception;
    }
}

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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.killbill.billing.lpr.api.Plugin;
import org.killbill.billing.lpr.api.PluginState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The runtime's public face: what is installed, what is running, and how to change that.
 * <p>
 * Everything here is an orchestration of three collaborators that each hold one concern -- the
 * repository knows the filesystem, the lifecycle knows state transitions, the service registry
 * knows who provides what. This class only sequences them, which is why an upgrade can be
 * expressed as "start the candidate, then stop the incumbent" rather than as a special case
 * threaded through all three.
 * <p>
 * Safe for concurrent use.
 */
public class DefaultPluginManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultPluginManager.class);

    private final PluginRepository repository;
    private final DefaultPluginLifecycleManager lifecycle;
    private final DefaultServiceRegistry serviceRegistry;
    private final LprMetricsCollector metrics;
    private final Map<String, PluginRuntime> plugins = new ConcurrentHashMap<>();
    private final Map<String, PluginRuntime> previousRuntimes = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<PluginActivationListener> activationListeners = new CopyOnWriteArrayList<>();

    public DefaultPluginManager(final PluginRepository repository,
                                final DefaultPluginLifecycleManager lifecycle,
                                final DefaultServiceRegistry serviceRegistry) {
        this(repository, lifecycle, serviceRegistry, null);
    }

    public DefaultPluginManager(final PluginRepository repository,
                                final DefaultPluginLifecycleManager lifecycle,
                                final DefaultServiceRegistry serviceRegistry,
                                final LprMetricsCollector metrics) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.serviceRegistry = Objects.requireNonNull(serviceRegistry, "serviceRegistry");
        this.metrics = metrics != null ? metrics : new LprMetricsCollector(NoOpLprMetrics.INSTANCE);
        this.metrics.trackPluginStates(() -> allRuntimes().stream().map(PluginRuntime::state).toList());
    }

    /**
     * Registers a listener that is notified after successful version switches and rollbacks.
     *
     * @param listener the observer; must not be null
     */
    public void addActivationListener(final PluginActivationListener listener) {
        activationListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Reads the plugin directory and registers what it finds, without starting anything.
     * <p>
     * Separate from {@link #startAll} because discovery and startup happen at different points in
     * the host's lifecycle, and because knowing what is installed is useful even when a plugin
     * cannot start.
     *
     * @return how many plugins were discovered
     */
    public synchronized int discover() {
        final Map<String, PluginRuntime> discovered = new LinkedHashMap<>();
        for (final PluginRuntime runtime : repository.scan()) {
            final PluginRuntime previous = discovered.putIfAbsent(runtime.pluginId(), runtime);
            if (previous != null) {
                log.warn("Ignoring duplicate plugin id {}", runtime.pluginId());
            }
        }

        // Discovery is also used after INSTALL_PLUGIN and filesystem notifications, while other
        // plugins are serving traffic. Replacing the whole map here loses the only references that
        // can stop those live instances and release their ClassLoaders. Keep a live runtime until
        // the lifecycle explicitly stops it; its descriptor's installedVersions() still reflects
        // the newly scanned filesystem state.
        for (final Map.Entry<String, PluginRuntime> entry : List.copyOf(plugins.entrySet())) {
            final PluginRuntime current = entry.getValue();
            if (current.state().isRunning()) {
                discovered.put(entry.getKey(), current);
            }
        }

        plugins.clear();
        plugins.putAll(discovered);
        log.info("Discovered {} plugin(s) in {}", plugins.size(), repository.root());
        return plugins.size();
    }

    /**
     * Starts every discovered plugin.
     * <p>
     * One plugin failing does not stop the others: a billing system with a broken analytics plugin
     * should still take payments. Plugins that must not be missing are named in
     * {@code mandatoryPluginIds}, and those failing does abort startup, matching the behaviour of
     * {@code org.killbill.billing.plugin.mandatory.plugins}.
     *
     * @param mandatoryPluginIds plugins whose failure should abort startup
     * @return ids of the plugins that started
     * @throws IllegalStateException if a mandatory plugin did not start
     */
    public synchronized List<String> startAll(final Collection<String> mandatoryPluginIds) {
        final Collection<String> mandatory = Objects.requireNonNullElse(mandatoryPluginIds, List.of());
        final List<String> started = new ArrayList<>();
        final Map<String, Exception> failures = new LinkedHashMap<>();

        for (final PluginRuntime runtime : plugins.values()) {
            try {
                if (lifecycle.start(runtime)) {
                    started.add(runtime.pluginId());
                }
            } catch (final RuntimeException e) {
                failures.put(runtime.pluginId(), e);
            }
        }

        final List<String> missingMandatory = mandatory.stream()
                                                       .filter(id -> !isRunning(id))
                                                       .toList();
        if (!missingMandatory.isEmpty()) {
            throw new IllegalStateException("Mandatory plugin(s) did not start: " + missingMandatory
                                            + ". Failures: " + failures.keySet());
        }
        if (!failures.isEmpty()) {
            log.error("{} plugin(s) failed to start and are unavailable: {}", failures.size(), failures.keySet());
        }
        return List.copyOf(started);
    }

    /**
     * Stops every running plugin.
     * <p>
     * Failures are logged and swallowed: this runs on the shutdown path, where refusing to continue
     * would leave the remaining plugins holding their resources.
     */
    public synchronized void stopAll() {
        for (final PluginRuntime runtime : allRuntimes()) {
            try {
                lifecycle.stop(runtime);
            } catch (final RuntimeException e) {
                log.warn("Failed to stop plugin {}", runtime.pluginId(), e);
            }
        }
        previousRuntimes.clear();
    }

    /**
     * @param pluginId the plugin
     * @return true if this call started it
     */
    public synchronized boolean start(final String pluginId) {
        return lifecycle.start(require(pluginId));
    }

    /**
     * @param pluginId the plugin
     * @return true if this call stopped it
     */
    public synchronized boolean stop(final String pluginId) {
        final PluginRuntime previous = previousRuntimes.remove(pluginId);
        if (previous != null) { lifecycle.stop(previous); }
        return lifecycle.stop(require(pluginId));
    }

    /**
     * @param pluginId the plugin
     */
    public synchronized void restart(final String pluginId) {
        stop(pluginId);
        start(pluginId);
    }

    /**
     * Switches a plugin to a different installed version.
     * <p>
     * The candidate is started <b>before</b> the incumbent is stopped, so a payment or tax plugin
     * is never absent from the service registry during an upgrade. If the candidate fails to start,
     * the incumbent keeps serving and the activation is abandoned -- an upgrade that cannot start
     * must not take the working version down with it.
     * <p>
     * The registry tolerates both versions briefly: lookups by plugin id return whichever
     * registration is present, and the candidate's registrations replace the incumbent's as they
     * are made.
     *
     * @param pluginId the plugin
     * @param version  an installed version
     * @throws IllegalStateException if the candidate cannot start
     */
    public synchronized void activateVersion(final String pluginId, final String version) {
        switchVersion(pluginId, version, false);
    }

    private void switchVersion(final String pluginId, final String version, final boolean rollingBack) {
        final PluginRuntime incumbent = require(pluginId);
        if (incumbent.version().equals(version) && incumbent.state().isRunning()) {
            return;
        }
        completeUpgrade(pluginId, incumbent.version());
        final PluginRuntime candidate = repository.loadVersion(pluginId, version);
        final boolean wasRunning = incumbent.state().isRunning();
        if (wasRunning) {
            serviceRegistry.selectVersion(pluginId, incumbent.version());
        }
        serviceRegistry.stageVersion(pluginId, version);
        try {
            lifecycle.start(candidate);
            repository.setActiveVersion(pluginId, version, wasRunning ? incumbent.version() : null);
        } catch (final RuntimeException e) {
            lifecycle.stop(candidate);
            serviceRegistry.clearStagedVersion(pluginId);
            throw e;
        }
        plugins.put(pluginId, candidate);
        final boolean retain = !rollingBack && wasRunning
                && activationListeners.stream().anyMatch(PluginActivationListener::retainPreviousVersion);
        if (retain) {
            previousRuntimes.put(pluginId, incumbent);
        }
        if (!rollingBack) {
            fireActivated(pluginId, version, wasRunning ? Optional.of(incumbent.version()) : Optional.empty());
        }
        serviceRegistry.clearStagedVersion(pluginId);
        if (plugins.get(pluginId) == candidate) {
            serviceRegistry.selectVersion(pluginId, version);
        }
        if (wasRunning && !retain) {
            lifecycle.stop(incumbent);
        }
    }

    public synchronized void rollback(final String pluginId) {
        rollback(pluginId, "Operator rollback");
    }

    private void rollback(final String pluginId, final String reason) {
        final PluginRuntime current = require(pluginId);
        final String failedVersion = current.version();
        final PluginRuntime previous = previousRuntimes.get(pluginId);
        final String version = previous != null ? previous.version() : repository.previousVersion(pluginId)
                .orElseThrow(() -> new IllegalStateException("No previous version for " + pluginId));
        if (previous != null) {
            repository.setActiveVersion(pluginId, version, failedVersion);
            plugins.put(pluginId, previous);
            previousRuntimes.remove(pluginId);
            serviceRegistry.selectVersion(pluginId, version);
            lifecycle.stop(current);
        } else {
            switchVersion(pluginId, version, true);
        }
        fireRolledBack(pluginId, version, failedVersion, reason);
    }

    public synchronized boolean rollbackIfCurrent(final String pluginId, final String expectedVersion, final String reason) {
        if (!require(pluginId).version().equals(expectedVersion) || !previousRuntimes.containsKey(pluginId)) {
            return false;
        }
        rollback(pluginId, reason);
        return true;
    }

    public synchronized void completeUpgrade(final String pluginId, final String expectedVersion) {
        if (require(pluginId).version().equals(expectedVersion)) {
            final PluginRuntime previous = previousRuntimes.remove(pluginId);
            if (previous != null) {
                lifecycle.stop(previous);
            }
        }
    }

    private void fireActivated(final String pluginId,
                               final String newVersion,
                               final Optional<String> previousVersion) {
        for (final PluginActivationListener listener : activationListeners) {
            try {
                listener.onActivated(pluginId, newVersion, previousVersion);
            } catch (final RuntimeException e) {
                log.warn("Activation listener failed for {} {}", pluginId, newVersion, e);
            }
        }
    }

    private void fireRolledBack(final String pluginId,
                                final String currentVersion,
                                final String failedVersion,
                                final String reason) {
        for (final PluginActivationListener listener : activationListeners) {
            try {
                listener.onRolledBack(pluginId, currentVersion, failedVersion, reason);
            } catch (final RuntimeException e) {
                log.warn("Rollback listener failed for {}", pluginId, e);
            }
        }
    }

    /**
     * @param pluginId the plugin
     * @return its current state, or empty if it is not installed
     */
    public Optional<PluginState> state(final String pluginId) {
        return Optional.ofNullable(plugins.get(pluginId)).map(PluginRuntime::state);
    }

    /**
     * @param pluginId the plugin
     * @return true if it is currently serving traffic
     */
    public boolean isRunning(final String pluginId) {
        return state(pluginId).map(PluginState::isRunning).orElse(false);
    }

    /**
     * @param pluginId the plugin
     * @return a snapshot for the management API, or empty if it is not installed
     */
    public Optional<PluginInfo> get(final String pluginId) {
        return Optional.ofNullable(plugins.get(pluginId)).map(this::describe);
    }

    /**
     * @return a snapshot of every installed plugin, ordered by id
     */
    public List<PluginInfo> list() {
        return plugins.values().stream()
                      .map(this::describe)
                      .sorted(java.util.Comparator.comparing(PluginInfo::pluginId))
                      .toList();
    }

    /**
     * The plugins currently in {@link org.killbill.billing.lpr.api.PluginState#ACTIVE} state.
     * <p>
     * The view is a snapshot: a plugin that stops while the caller iterates simply will not appear.
     *
     * @return running plugin views; empty if none
     */
    public List<RunningPlugin> runningPlugins() {
        return allRuntimes().stream().map(this::asRunningPlugin).flatMap(Optional::stream).toList();
    }

    private List<PluginRuntime> allRuntimes() {
        final List<PluginRuntime> runtimes = new ArrayList<>(plugins.values());
        runtimes.addAll(previousRuntimes.values());
        return runtimes;
    }

    private Optional<RunningPlugin> asRunningPlugin(final PluginRuntime runtime) {
        synchronized (runtime) {
            if (!runtime.state().isRunning() || runtime.instance() == null || runtime.classLoaderHandle() == null) {
                return Optional.empty();
            }
            final Plugin instance = runtime.instance();
            final ClassLoader loader = runtime.classLoaderHandle().classLoader();
            return Optional.of(new RunningPlugin() {
                public String pluginId() { return runtime.pluginId(); }
                public String version() { return runtime.version(); }
                public Plugin instance() { return instance; }
                public ClassLoader classLoader() { return loader; }
            });
        }
    }

    private PluginInfo describe(final PluginRuntime runtime) {
        return new PluginInfo(runtime.pluginId(),
                              runtime.descriptor().name(),
                              runtime.version(),
                              runtime.state(),
                              servicesOf(runtime.pluginId()),
                              repository.installedVersions(runtime.pluginId()),
                              runtime.failureReason().orElse(null));
    }

    /**
     * Derived from the registry rather than from the descriptor, so the answer reflects what the
     * plugin actually published, not what it intended to.
     */
    private List<String> servicesOf(final String pluginId) {
        return serviceRegistry.publishedTypes(pluginId).stream().map(Class::getName).sorted().toList();
    }

    private PluginRuntime require(final String pluginId) {
        final PluginRuntime runtime = plugins.get(Objects.requireNonNull(pluginId, "pluginId"));
        if (runtime == null) {
            throw new IllegalArgumentException("No such plugin: " + pluginId
                                               + ". Installed: " + plugins.keySet());
        }
        return runtime;
    }
}

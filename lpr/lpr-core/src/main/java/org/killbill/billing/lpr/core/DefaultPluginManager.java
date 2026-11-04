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
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.killbill.billing.lpr.api.Plugin;
import org.killbill.billing.lpr.spi.PluginRepository;
import org.killbill.billing.lpr.spi.PluginDeployment;
import org.killbill.billing.lpr.spi.RepositorySelection;
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
    private final Map<String, RepositorySelection> appliedSelections = new ConcurrentHashMap<>();
    private final Map<String, PluginRuntime> plugins = new ConcurrentHashMap<>();
    private final Map<String, PluginRuntime> previousRuntimes = new ConcurrentHashMap<>();
    private final Map<VersionKey, PluginRuntime> retiringRuntimes = new ConcurrentHashMap<>();
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
        for (final PluginDeployment deployment : repository.scan()) {
            final PluginRuntime runtime = runtimeOf(deployment);
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
            if (current.state().isRunning() || current.state() == PluginState.STOPPING) {
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
                if (startDiscovered(runtime)) { started.add(runtime.pluginId()); }
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

    private boolean startDiscovered(final PluginRuntime runtime) {
        final RepositorySelection selected = repository.selection(runtime.pluginId());
        if (!selected.enabled()) {
            appliedSelections.put(runtime.pluginId(), selected);
            return false;
        }
        if (selected.activeVersion() != null && !selected.activeVersion().equals(runtime.version())) {
            switchVersion(runtime.pluginId(), selected.activeVersion(), false, selected, false);
            return true;
        }
        if (!lifecycle.start(runtime)) { return false; }
        try {
            assertSelection(runtime.pluginId(), selected);
            appliedSelections.put(runtime.pluginId(), selected);
            return true;
        } catch (final RuntimeException stale) {
            stopRuntime(runtime);
            throw stale;
        }
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
                stopRuntime(runtime);
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
        final PluginRuntime current = require(pluginId);
        if (current.state() == PluginState.STOPPING) {
            throw new IllegalStateException("Plugin is still stopping: " + pluginId);
        }
        final RepositorySelection selected = repository.selection(pluginId);
        final String version = selected.activeVersion() != null ? selected.activeVersion() : current.version();
        final boolean wasActive = current.state().isRunning() && current.version().equals(version);
        switchVersion(pluginId, version, false, selected, true);
        return !wasActive;
    }

    /**
     * @param pluginId the plugin
     * @return true if this call stopped it
     */
    public synchronized boolean stop(final String pluginId) {
        final PluginRuntime current = require(pluginId);
        final RepositorySelection selected = repository.selection(pluginId);
        if (selected.enabled()) {
            final String version = selected.activeVersion() != null ? selected.activeVersion() : current.version();
            final RepositorySelection stopped = repository.updateSelection(pluginId, selected.revision(), version,
                                                                           selected.previousVersion(), false);
            appliedSelections.put(pluginId, stopped);
        }
        return stopLocally(pluginId);
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
        switchVersion(pluginId, version, false, repository.selection(pluginId), true);
    }

    private void switchVersion(final String pluginId, final String version, final boolean rollingBack,
                               final RepositorySelection expected, final boolean publish) {
        final PluginRuntime incumbent = require(pluginId);
        if (incumbent.version().equals(version) && incumbent.state().isRunning()) {
            final RepositorySelection selected = commitSelection(pluginId, version, expected, publish);
            final RepositorySelection previousSelection = appliedSelections.put(pluginId, selected);
            final PluginRuntime retained = previousRuntimes.get(pluginId);
            if (retained != null && !selected.equals(previousSelection)) {
                fireActivated(pluginId, version, Optional.of(retained.version()));
            }
            return;
        }
        completeUpgrade(pluginId, incumbent.version());
        if (isVersionLoaded(pluginId, version)) {
            throw new IllegalStateException("Plugin version is still loaded: " + pluginId + '/' + version);
        }
        final PluginRuntime candidate = runtimeOf(repository.loadVersion(pluginId, version));
        final boolean wasRunning = incumbent.state().isRunning();
        if (wasRunning) {
            serviceRegistry.selectVersion(pluginId, incumbent.version());
        }
        serviceRegistry.stageVersion(pluginId, version);
        final RepositorySelection committed = startCandidate(candidate, expected, publish);
        plugins.put(pluginId, candidate);
        appliedSelections.put(pluginId, committed);
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
            stopRuntime(incumbent);
        }
    }

    private RepositorySelection startCandidate(final PluginRuntime candidate, final RepositorySelection expected,
                                                final boolean publish) {
        final String pluginId = candidate.pluginId();
        try {
            lifecycle.start(candidate);
            return commitSelection(pluginId, candidate.version(), expected, publish);
        } catch (final RuntimeException e) {
            try { stopRuntime(candidate); }
            finally { serviceRegistry.clearStagedVersion(pluginId); }
            throw e;
        }
    }

    public synchronized void rollback(final String pluginId) {
        rollback(pluginId, "Operator rollback");
    }

    private void rollback(final String pluginId, final String reason) {
        final PluginRuntime current = require(pluginId);
        final RepositorySelection expected = repository.selection(pluginId);
        if (!expected.equals(appliedSelections.get(pluginId))) {
            throw new ConcurrentModificationException("Cannot roll back stale local selection for " + pluginId);
        }
        final String failedVersion = current.version();
        final PluginRuntime previous = previousRuntimes.get(pluginId);
        final String version = previous != null ? previous.version() : repository.previousVersion(pluginId)
                .orElseThrow(() -> new IllegalStateException("No previous version for " + pluginId));
        if (previous != null) {
            final RepositorySelection committed = repository.updateSelection(pluginId, expected.revision(), version, failedVersion, true);
            plugins.put(pluginId, previous);
            appliedSelections.put(pluginId, committed);
            previousRuntimes.remove(pluginId);
            serviceRegistry.selectVersion(pluginId, version);
            stopRuntime(current);
        } else {
            switchVersion(pluginId, version, true, expected, true);
        }
        fireRolledBack(pluginId, version, failedVersion, reason);
    }

    public synchronized boolean rollbackIfCurrent(final String pluginId, final String expectedVersion, final String reason) {
        return rollbackIfCurrent(pluginId, expectedVersion, appliedRevision(pluginId), reason);
    }

    public synchronized boolean rollbackIfCurrent(final String pluginId, final String expectedVersion,
                                                   final long expectedRevision, final String reason) {
        if (appliedRevision(pluginId) != expectedRevision || !require(pluginId).version().equals(expectedVersion) || !previousRuntimes.containsKey(pluginId)
            || !repository.selection(pluginId).equals(appliedSelections.get(pluginId))) {
            return false;
        }
        rollback(pluginId, reason);
        return true;
    }

    public synchronized void completeUpgrade(final String pluginId, final String expectedVersion) {
        completeUpgrade(pluginId, expectedVersion, appliedRevision(pluginId));
    }

    public synchronized void completeUpgrade(final String pluginId, final String expectedVersion, final long expectedRevision) {
        if (appliedRevision(pluginId) == expectedRevision && require(pluginId).version().equals(expectedVersion)) {
            final PluginRuntime previous = previousRuntimes.remove(pluginId);
            if (previous != null) {
                stopRuntime(previous);
            }
        }
    }

    public long appliedRevision(final String pluginId) {
        final RepositorySelection selected = appliedSelections.get(pluginId);
        return selected != null ? selected.revision() : -1;
    }

    /** Applies durable shared intent without publishing another command. Failed plugins retry next tick. */
    public synchronized void reconcile() {
        discover();
        for (final String pluginId : List.copyOf(plugins.keySet())) {
            try { reconcilePlugin(pluginId); }
            catch (final RuntimeException e) { log.warn("Cannot reconcile plugin {}", pluginId, e); }
        }
    }

    private void reconcilePlugin(final String pluginId) {
        final RepositorySelection selected = repository.selection(pluginId);
        final List<String> installed = repository.installedVersions(pluginId);
        if (!selected.enabled() || installed.isEmpty()) {
            stopLocally(pluginId);
            appliedSelections.put(pluginId, selected);
            return;
        }
        final String version = selected.activeVersion() != null ? selected.activeVersion() : installed.get(0);
        if (!installed.contains(version)) {
            throw new IllegalStateException("Desired plugin version is unavailable: " + pluginId + '/' + version);
        }
        switchVersion(pluginId, version, false, selected, false);
    }

    private boolean stopLocally(final String pluginId) {
        final PluginRuntime previous = previousRuntimes.remove(pluginId);
        if (previous != null) { stopRuntime(previous); }
        for (final PluginRuntime retired : List.copyOf(retiringRuntimes.values())) {
            if (retired.pluginId().equals(pluginId) && retired != plugins.get(pluginId)) { stopRuntime(retired); }
        }
        return stopRuntime(require(pluginId));
    }

    private RepositorySelection commitSelection(final String pluginId, final String version,
                                                 final RepositorySelection expected, final boolean publish) {
        if (publish && (!expected.enabled() || !version.equals(expected.activeVersion()))) {
            final String previous = expected.activeVersion() != null ? expected.activeVersion() : require(pluginId).version();
            return repository.updateSelection(pluginId, expected.revision(), version,
                                              version.equals(previous) ? expected.previousVersion() : previous, true);
        }
        assertSelection(pluginId, expected);
        return expected;
    }

    private void assertSelection(final String pluginId, final RepositorySelection expected) {
        if (!repository.selection(pluginId).equals(expected)) {
            throw new ConcurrentModificationException("Desired selection changed while starting " + pluginId);
        }
    }

    private static PluginRuntime runtimeOf(final PluginDeployment deployment) {
        return new PluginRuntime(deployment.descriptor(), deployment.artifact());
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
        retiringRuntimes.entrySet().removeIf(entry -> entry.getValue().state() == PluginState.STOPPED);
        final List<PluginRuntime> runtimes = new ArrayList<>(plugins.values());
        runtimes.addAll(previousRuntimes.values());
        runtimes.addAll(retiringRuntimes.values());
        return runtimes.stream().distinct().toList();
    }

    /** True until a version has released its loader, including timed-out stop callbacks. */
    public synchronized boolean isVersionLoaded(final String pluginId, final String version) {
        return allRuntimes().stream().anyMatch(runtime -> runtime.pluginId().equals(pluginId)
                && runtime.version().equals(version) && runtime.classLoaderHandle() != null);
    }

    private boolean stopRuntime(final PluginRuntime runtime) {
        final VersionKey key = new VersionKey(runtime.pluginId(), runtime.version());
        try {
            return lifecycle.stop(runtime);
        } finally {
            if (runtime.classLoaderHandle() != null) {
                retiringRuntimes.put(key, runtime);
            } else {
                retiringRuntimes.remove(key, runtime);
            }
        }
    }

    private record VersionKey(String pluginId, String version) { }

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

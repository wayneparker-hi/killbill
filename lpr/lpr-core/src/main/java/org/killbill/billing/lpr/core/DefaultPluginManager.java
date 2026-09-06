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
    private final Map<String, PluginRuntime> plugins = new ConcurrentHashMap<>();

    public DefaultPluginManager(final PluginRepository repository,
                                final DefaultPluginLifecycleManager lifecycle,
                                final DefaultServiceRegistry serviceRegistry) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.serviceRegistry = Objects.requireNonNull(serviceRegistry, "serviceRegistry");
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
    public int discover() {
        plugins.clear();
        for (final PluginRuntime runtime : repository.scan()) {
            final PluginRuntime previous = plugins.putIfAbsent(runtime.pluginId(), runtime);
            if (previous != null) {
                log.warn("Ignoring duplicate plugin id {}", runtime.pluginId());
            }
        }
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
    public List<String> startAll(final Collection<String> mandatoryPluginIds) {
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
    public void stopAll() {
        for (final PluginRuntime runtime : plugins.values()) {
            try {
                lifecycle.stop(runtime);
            } catch (final RuntimeException e) {
                log.warn("Failed to stop plugin {}", runtime.pluginId(), e);
            }
        }
    }

    /**
     * @param pluginId the plugin
     * @return true if this call started it
     */
    public boolean start(final String pluginId) {
        return lifecycle.start(require(pluginId));
    }

    /**
     * @param pluginId the plugin
     * @return true if this call stopped it
     */
    public boolean stop(final String pluginId) {
        return lifecycle.stop(require(pluginId));
    }

    /**
     * @param pluginId the plugin
     */
    public void restart(final String pluginId) {
        lifecycle.restart(require(pluginId));
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
    public void activateVersion(final String pluginId, final String version) {
        final PluginRuntime incumbent = require(pluginId);
        if (incumbent.version().equals(version) && incumbent.state().isRunning()) {
            return;
        }

        final PluginRuntime candidate = repository.loadVersion(pluginId, version);
        final boolean wasRunning = incumbent.state().isRunning();

        try {
            lifecycle.start(candidate);
        } catch (final RuntimeException e) {
            log.error("Version {} of plugin {} failed to start; keeping version {}",
                      version, pluginId, incumbent.version(), e);
            throw e;
        }

        if (wasRunning) {
            lifecycle.stop(incumbent);
        }
        plugins.put(pluginId, candidate);
        repository.setActiveVersion(pluginId, version);
        log.info("Plugin {} switched from version {} to {}", pluginId, incumbent.version(), version);
    }

    /**
     * Returns a plugin to the version that ran before the current one.
     *
     * @param pluginId the plugin
     * @throws IllegalStateException if no previous version is recorded
     */
    public void rollback(final String pluginId) {
        final String previous = repository.previousVersion(pluginId)
                                          .orElseThrow(() -> new IllegalStateException(
                                                  "No previous version recorded for plugin " + pluginId));
        log.info("Rolling plugin {} back to version {}", pluginId, previous);
        activateVersion(pluginId, previous);
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

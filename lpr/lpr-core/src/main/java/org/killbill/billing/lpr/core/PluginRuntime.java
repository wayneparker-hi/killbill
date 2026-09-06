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

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.killbill.billing.lpr.api.Plugin;
import org.killbill.billing.lpr.api.PluginContext;
import org.killbill.billing.lpr.api.PluginState;
import org.killbill.billing.lpr.spi.PluginArtifact;
import org.killbill.billing.lpr.spi.PluginClassLoaderHandle;
import org.killbill.billing.lpr.spi.PluginDescriptor;

/**
 * Everything the runtime knows about one installed plugin version, including its current state.
 * <p>
 * State transitions go through {@link #transition}, which is a compare-and-set. That is what makes
 * two concurrent {@code start()} calls resolve to one winner and one no-op instead of two
 * half-started plugins racing to register the same services -- a real possibility here, since
 * plugins can be started by the lifecycle at boot, by an operator through the management API, and
 * by a cluster-wide node command, all at once.
 * <p>
 * The loaded pieces (ClassLoader, instance, context) exist only between a successful start and the
 * following stop. They are cleared on the way down so that nothing keeps the plugin's ClassLoader
 * reachable after unloading.
 */
public class PluginRuntime {

    private final PluginDescriptor descriptor;
    private final PluginArtifact artifact;
    private final AtomicReference<PluginState> state = new AtomicReference<>(PluginState.INSTALLED);

    private volatile PluginClassLoaderHandle classLoaderHandle;
    private volatile Plugin instance;
    private volatile PluginContext context;
    private volatile DefaultResourceRegistry resources;
    private volatile String failureReason;

    public PluginRuntime(final PluginDescriptor descriptor, final PluginArtifact artifact) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.artifact = Objects.requireNonNull(artifact, "artifact");
        if (!descriptor.pluginId().equals(artifact.pluginId())) {
            throw new IllegalArgumentException("Descriptor is for plugin " + descriptor.pluginId()
                                               + " but artifact is for " + artifact.pluginId());
        }
    }

    public String pluginId() {
        return descriptor.pluginId();
    }

    public String version() {
        return descriptor.version();
    }

    public PluginDescriptor descriptor() {
        return descriptor;
    }

    public PluginArtifact artifact() {
        return artifact;
    }

    public PluginState state() {
        return state.get();
    }

    /**
     * @return why the plugin last failed, if it is in {@link PluginState#FAILED}
     */
    public Optional<String> failureReason() {
        return Optional.ofNullable(failureReason);
    }

    /**
     * Attempts a state transition.
     *
     * @param expected the state the caller believes the plugin is in
     * @param next     the state to move to
     * @return true if this caller won the transition; false if another thread got there first
     */
    boolean transition(final PluginState expected, final PluginState next) {
        return state.compareAndSet(expected, next);
    }

    /**
     * Moves to {@link PluginState#FAILED} from wherever the plugin currently is.
     * <p>
     * Unconditional on purpose: failure can surface mid-transition, and refusing to record it
     * because the state moved on would leave the plugin looking healthy.
     */
    void markFailed(final String reason) {
        failureReason = reason;
        state.set(PluginState.FAILED);
    }

    void loaded(final PluginClassLoaderHandle handle,
                final Plugin pluginInstance,
                final PluginContext pluginContext,
                final DefaultResourceRegistry resourceRegistry) {
        this.classLoaderHandle = handle;
        this.instance = pluginInstance;
        this.context = pluginContext;
        this.resources = resourceRegistry;
        this.failureReason = null;
    }

    /**
     * Drops every reference to loaded plugin state.
     * <p>
     * Not bookkeeping: as long as the runtime holds the instance or its context, the plugin's
     * ClassLoader stays reachable and its classes are never reclaimed, so "unloaded" would be a
     * claim rather than a fact (CLAUDE.md, A4).
     */
    void unloaded() {
        this.classLoaderHandle = null;
        this.instance = null;
        this.context = null;
        this.resources = null;
    }

    PluginClassLoaderHandle classLoaderHandle() {
        return classLoaderHandle;
    }

    Plugin instance() {
        return instance;
    }

    PluginContext context() {
        return context;
    }

    DefaultResourceRegistry resources() {
        return resources;
    }

    @Override
    public String toString() {
        return "PluginRuntime{" + pluginId() + '/' + version() + ", state=" + state.get() + '}';
    }
}

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

package org.killbill.billing.platform.plugin.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import org.killbill.billing.lpr.core.DefaultPluginManager;
import org.killbill.billing.lpr.core.DefaultServiceRegistry;
import org.killbill.billing.runtime.api.DefaultPluginInfo;
import org.killbill.billing.runtime.api.DefaultPluginServiceInfo;
import org.killbill.billing.runtime.api.PluginInfo;
import org.killbill.billing.runtime.api.PluginServiceInfo;
import org.killbill.billing.runtime.api.PluginState;
import org.killbill.billing.runtime.api.PluginStateChange;
import org.killbill.billing.runtime.api.PluginsInfoApi;
import org.killbill.billing.util.nodes.KillbillNodesApi;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports what the plugin runtime is running, and reacts when something on disk changes underneath it.
 * <p>
 * This is the Kill Bill-facing view of {@link DefaultPluginManager}: the manager speaks in plugin ids
 * and an eight-state lifecycle, while the rest of Kill Bill (node info, the {@code /plugins} REST
 * resources) speaks in {@link PluginInfo} and a two-state {@code RUNNING}/{@code STOPPED}. Collapsing
 * the states here rather than widening {@code PluginState} keeps the narrowing in one place, where the
 * reason for it is visible.
 */
public class DefaultPluginsInfoApi implements PluginsInfoApi {

    private static final Logger log = LoggerFactory.getLogger(DefaultPluginsInfoApi.class);

    private final DefaultPluginManager pluginManager;
    private final DefaultServiceRegistry serviceRegistry;

    /**
     * Injected as a {@code Provider} to break a genuine dependency cycle: the node service needs this
     * API to publish plugin state, and this API needs the node service to notify it of changes.
     * <p>
     * The OSGi layer broke the same cycle with a mutable holder that Guice filled by setter injection
     * after construction. A {@code Provider} does it without the mutable field, and without the window
     * in which the holder is constructed but still empty.
     */
    private final Provider<KillbillNodesApi> nodesApi;

    @Inject
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
                        justification = "The service registry is an injected singleton, deliberately shared. "
                                        + "This class reports what plugins are publishing right now, so it must "
                                        + "read the live registry; a defensive copy would report a snapshot "
                                        + "taken at injector-creation time, which is always empty.")
    public DefaultPluginsInfoApi(final DefaultPluginManager pluginManager,
                                 final DefaultServiceRegistry serviceRegistry,
                                 final Provider<KillbillNodesApi> nodesApi) {
        this.pluginManager = pluginManager;
        this.serviceRegistry = serviceRegistry;
        this.nodesApi = nodesApi;
    }

    /**
     * Every version on disk, not just the ones loaded.
     * <p>
     * A plugin with three versions installed produces three entries, exactly one of which is marked
     * {@code selectedForStart}. Callers use this to offer a rollback target, so a view restricted to
     * the loaded version would not be enough.
     */
    @Override
    public Iterable<PluginInfo> getPluginsInfo() {
        final List<PluginInfo> result = new ArrayList<>();
        for (final org.killbill.billing.lpr.core.PluginInfo plugin : pluginManager.list()) {
            result.add(new DefaultPluginInfo(plugin.pluginId(),
                                             plugin.name(),
                                             plugin.version(),
                                             toPluginState(plugin.state()),
                                             servicesOf(plugin.pluginId()),
                                             true));

            for (final String installed : plugin.installedVersions()) {
                if (!installed.equals(plugin.version())) {
                    // On disk but not loaded, so it publishes nothing and is not running.
                    result.add(new DefaultPluginInfo(plugin.pluginId(),
                                                     plugin.name(),
                                                     installed,
                                                     PluginState.STOPPED,
                                                     Set.of(),
                                                     false));
                }
            }
        }
        result.sort(Comparator.comparing(PluginInfo::getPluginName)
                              .thenComparing(info -> info.getVersion() == null ? "" : info.getVersion()));
        return result;
    }

    /**
     * Reacts to a plugin appearing on, or being disabled on, the filesystem.
     *
     * @param newState      what changed; only {@code NEW_VERSION} and {@code DISABLED} are meaningful
     * @param pluginId      the plugin whose directory changed
     * @param pluginVersion the version involved
     */
    @Override
    public void notifyOfStateChanged(final PluginStateChange newState,
                                     final String pluginId,
                                     final String pluginVersion) {
        try {
            switch (newState) {
                case NEW_VERSION:
                    // Deliberately not started here. Making a version visible and choosing to run it
                    // are separate decisions; the second one arrives as an explicit start command.
                    pluginManager.discover();
                    break;

                case DISABLED:
                    if (pluginManager.isRunning(pluginId)) {
                        pluginManager.stop(pluginId);
                    }
                    pluginManager.discover();
                    break;

                default:
                    throw new IllegalStateException("Invalid PluginStateChange " + newState);
            }

            notifyNodes(pluginId, pluginVersion);
        } catch (final RuntimeException e) {
            // A failure to reflect a filesystem change must not take down the caller, which is a
            // node command handler or an install hook with nothing useful to do about it.
            log.error("Failed to handle {} for plugin {} version {}", newState, pluginId, pluginVersion, e);
        }
    }

    private void notifyNodes(final String pluginId, final String pluginVersion) {
        final KillbillNodesApi api = nodesApi.get();
        if (api == null) {
            return;
        }
        final org.killbill.billing.lpr.core.PluginInfo current = pluginManager.get(pluginId).orElse(null);
        final PluginInfo changed = new DefaultPluginInfo(pluginId,
                                                         current != null ? current.name() : pluginId,
                                                         pluginVersion,
                                                         current != null ? toPluginState(current.state()) : PluginState.STOPPED,
                                                         Set.of(),
                                                         current != null && pluginVersion.equals(current.version()));
        api.notifyPluginChanged(changed, getPluginsInfo());
    }

    /**
     * What the plugin currently answers to, by the same rule the registration bridge applies, so that
     * a service reported here can actually be looked up under the name reported.
     */
    private Set<PluginServiceInfo> servicesOf(final String pluginId) {
        return serviceRegistry.publishedServices(pluginId).stream()
                              .map(reference -> (PluginServiceInfo) new DefaultPluginServiceInfo(
                                      reference.type().getName(),
                                      KillbillPluginServiceBridge.registrationNameOf(reference)))
                              .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Collapses the runtime's lifecycle onto the two states Kill Bill's APIs expose.
     * <p>
     * Only {@code ACTIVE} counts as running. {@code STARTING} and {@code STOPPING} report as stopped
     * because callers use this to decide whether traffic can be sent, and in neither state can it.
     */
    static PluginState toPluginState(final org.killbill.billing.lpr.api.PluginState state) {
        return state != null && state.isRunning() ? PluginState.RUNNING : PluginState.STOPPED;
    }
}

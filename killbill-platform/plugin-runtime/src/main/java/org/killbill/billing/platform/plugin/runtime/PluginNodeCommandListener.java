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

import java.io.IOException;
import java.net.URI;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import org.killbill.billing.lpr.core.DefaultPluginManager;
import org.killbill.billing.lpr.management.ArtifactSource;
import org.killbill.billing.lpr.management.PluginInstaller;
import org.killbill.billing.notification.plugin.api.BroadcastMetadata;
import org.killbill.billing.notification.plugin.api.ExtBusEvent;
import org.killbill.billing.notification.plugin.api.ExtBusEventType;
import org.killbill.billing.runtime.api.DefaultPluginInfo;
import org.killbill.billing.runtime.api.PluginInfo;
import org.killbill.billing.runtime.api.PluginState;
import org.killbill.billing.runtime.api.PluginsInfoApi;
import org.killbill.billing.util.nodes.KillbillNodesApi;
import org.killbill.billing.util.nodes.PluginNodeCommandMetadata;
import org.killbill.billing.util.nodes.SystemNodeCommandType;
import org.killbill.commons.eventbus.AllowConcurrentEvents;
import org.killbill.commons.eventbus.Subscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.joda.JodaModule;

/**
 * Installs, removes, starts, stops and restarts plugins on this node in response to a cluster-wide
 * command.
 * <p>
 * An operator calls {@code /1.0/kb/nodesInfo} on any node; that node broadcasts a
 * {@code BROADCAST_SERVICE} event, and every node -- including the one that received the call --
 * acts on it here. Without this, {@code SystemNodeCommandType.START_PLUGIN} and its siblings are
 * accepted by the REST layer and then silently do nothing.
 * <p>
 * Replaces the OSGi {@code OSGIListener}, and takes over the two commands it never handled.
 * {@code INSTALL_PLUGIN} and {@code UNINSTALL_PLUGIN} were declared alongside the others but
 * implemented by the KPM plugin, so a deployment without KPM accepted them and did nothing. With
 * KPM gone they are handled here, which is also what makes them work the same way on every node
 * instead of only where KPM happened to be installed.
 * <p>
 * This is deliberately the <em>only</em> management surface. A second REST API for the same
 * operations would need its own authentication, its own audit trail, and its own answer for what
 * happens on the other nodes in the cluster -- all of which this path already has.
 */
public class PluginNodeCommandListener {

    private static final Logger log = LoggerFactory.getLogger(PluginNodeCommandListener.class);

    private static final Set<SystemNodeCommandType> HANDLED = Set.of(SystemNodeCommandType.INSTALL_PLUGIN,
                                                                     SystemNodeCommandType.UNINSTALL_PLUGIN,
                                                                     SystemNodeCommandType.START_PLUGIN,
                                                                     SystemNodeCommandType.STOP_PLUGIN,
                                                                     SystemNodeCommandType.RESTART_PLUGIN);

    /**
     * Where {@code INSTALL_PLUGIN} fetches from, when the command carries a {@code pluginKey} that
     * is a URI rather than a name.
     */
    private static final String PROPERTY_URI = "uri";
    private static final String PROPERTY_SHA1 = "sha1";
    private static final String PROPERTY_DESCRIPTOR = "descriptor";

    private final ObjectMapper objectMapper;
    private final DefaultPluginManager pluginManager;
    private final PluginInstaller installer;
    private final PluginsInfoApi pluginsInfoApi;

    /**
     * A {@code Provider} for the same reason {@link DefaultPluginsInfoApi} uses one: the nodes
     * service depends on {@code PluginsInfoApi}, which this class also holds, and injecting both
     * eagerly closes the cycle.
     */
    private final Provider<KillbillNodesApi> nodesApi;

    @Inject
    public PluginNodeCommandListener(final DefaultPluginManager pluginManager,
                                     final PluginInstaller installer,
                                     final PluginsInfoApi pluginsInfoApi,
                                     final Provider<KillbillNodesApi> nodesApi) {
        this.pluginManager = pluginManager;
        this.installer = installer;
        this.pluginsInfoApi = pluginsInfoApi;
        this.nodesApi = nodesApi;
        this.objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JodaModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @AllowConcurrentEvents
    @Subscribe
    public void handleKillbillEvent(final ExtBusEvent event) {
        if (event.getEventType() != ExtBusEventType.BROADCAST_SERVICE) {
            return;
        }
        try {
            handleBroadcast(event);
        } catch (final IOException | RuntimeException e) {
            // A malformed or unactionable command must not poison the bus subscription for every
            // other node command.
            log.warn("Failed to handle node command from event {}", event, e);
        }
    }

    private void handleBroadcast(final ExtBusEvent event) throws IOException {
        final BroadcastMetadata metadata = objectMapper.readValue(event.getMetaData(), BroadcastMetadata.class);
        final SystemNodeCommandType commandType = commandTypeOrNull(metadata.getCommandType());
        if (commandType == null || !HANDLED.contains(commandType)) {
            return;
        }

        final PluginNodeCommandMetadata command =
                (PluginNodeCommandMetadata) objectMapper.readValue(metadata.getEventJson(),
                                                                   commandType.getCommandMetadataClass());
        final String pluginId = command.getPluginName();
        if (pluginId == null) {
            log.warn("Ignoring {}: the command carries no plugin name", commandType);
            return;
        }

        if (commandType == SystemNodeCommandType.INSTALL_PLUGIN) {
            install(command);
            notifyNodes(pluginId);
            return;
        }

        if (pluginManager.get(pluginId).isEmpty()) {
            log.warn("Ignoring {} for unknown plugin {}. Installed: {}",
                     commandType, pluginId, pluginManager.list().stream().map(p -> p.pluginId()).toList());
            return;
        }

        // A version on the command means "make this version the running one", which is the same
        // operation as an upgrade or a rollback -- not a separate code path.
        if (command.getPluginVersion() != null
            && commandType != SystemNodeCommandType.STOP_PLUGIN
            && commandType != SystemNodeCommandType.UNINSTALL_PLUGIN) {
            pluginManager.activateVersion(pluginId, command.getPluginVersion());
        }

        switch (commandType) {
            case START_PLUGIN:
                pluginManager.start(pluginId);
                break;
            case STOP_PLUGIN:
                pluginManager.stop(pluginId);
                break;
            case RESTART_PLUGIN:
                pluginManager.restart(pluginId);
                break;
            case UNINSTALL_PLUGIN:
                uninstall(pluginId, command.getPluginVersion());
                break;
            default:
                throw new IllegalStateException("Unexpected command " + commandType);
        }
        log.info("Applied node command {} to plugin {}", commandType, pluginId);

        notifyNodes(pluginId);
    }

    /**
     * Fetches and lays out a new plugin version, then makes the runtime aware of it.
     * <p>
     * Installing does not start: the command has said where to get a version, not that it should
     * take over from the one currently serving. A separate {@code START_PLUGIN} (or an explicit
     * version on it) does that, which is what makes a staged rollout possible.
     */
    private void install(final PluginNodeCommandMetadata command) {
        final String pluginId = command.getPluginName();
        final String version = command.getPluginVersion();
        if (version == null) {
            log.warn("Ignoring INSTALL_PLUGIN for {}: no version given", pluginId);
            return;
        }
        final String uri = property(command, PROPERTY_URI);
        if (uri == null) {
            log.warn("Ignoring INSTALL_PLUGIN for {} {}: no '{}' property saying where to fetch it from",
                     pluginId, version, PROPERTY_URI);
            return;
        }

        ArtifactSource source = ArtifactSource.of(URI.create(uri));
        final String sha1 = property(command, PROPERTY_SHA1);
        if (sha1 != null) {
            source = source.withSha1(sha1);
        }

        // A null descriptor means "use the plugin.yaml the jar carries", which is the normal case.
        // The property exists for artifacts that predate the descriptor, or to correct one.
        installer.install(pluginId, version, source, property(command, PROPERTY_DESCRIPTOR));

        // The repository has changed underneath the runtime; make it look again.
        pluginManager.discover();
        log.info("Installed plugin {} version {}", pluginId, version);
    }


    /**
     * Stops the version before deleting it.
     * <p>
     * The installer deliberately refuses to reason about what is running, so the ordering has to be
     * imposed here: deleting the jar of a live plugin leaves a ClassLoader that will fail on the
     * next class it has not yet loaded.
     */
    private void uninstall(final String pluginId, final String version) {
        if (version == null) {
            log.warn("Ignoring UNINSTALL_PLUGIN for {}: no version given", pluginId);
            return;
        }
        if (pluginManager.isRunning(pluginId)) {
            pluginManager.stop(pluginId);
        }
        installer.uninstall(pluginId, version);
        pluginManager.discover();
    }

    private static String property(final PluginNodeCommandMetadata command, final String key) {
        if (command.getProperties() == null) {
            return null;
        }
        return command.getProperties().stream()
                      .filter(p -> key.equals(p.getKey()))
                      .map(p -> p.getValue() == null ? null : String.valueOf(p.getValue()))
                      .findFirst()
                      .orElse(null);
    }

    private void notifyNodes(final String pluginId) {
        final KillbillNodesApi api = nodesApi.get();
        if (api == null) {
            return;
        }
        final var current = pluginManager.get(pluginId).orElse(null);
        final PluginInfo changed = new DefaultPluginInfo(pluginId,
                                                         current != null ? current.name() : pluginId,
                                                         current != null ? current.version() : null,
                                                         current != null
                                                         ? DefaultPluginsInfoApi.toPluginState(current.state())
                                                         : PluginState.STOPPED,
                                                         Set.of(),
                                                         true);
        api.notifyPluginChanged(changed, pluginsInfoApi.getPluginsInfo());
    }

    private static SystemNodeCommandType commandTypeOrNull(final String command) {
        for (final SystemNodeCommandType type : SystemNodeCommandType.values()) {
            if (type.name().equals(command)) {
                return type;
            }
        }
        return null;
    }
}

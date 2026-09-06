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

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.killbill.billing.lpr.core.DefaultPluginManager;
import org.killbill.billing.platform.api.KillbillService;
import org.killbill.billing.platform.api.LifecycleHandlerType;
import org.killbill.billing.platform.api.LifecycleHandlerType.LifecycleLevel;
import jakarta.inject.Named;

import org.killbill.bus.api.PersistentBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

/**
 * Hangs the plugin runtime off Kill Bill's lifecycle.
 * <p>
 * It occupies the same three phases the OSGi service did, and that is deliberate:
 * <ul>
 *   <li>{@code INIT_PLUGIN} -- read the plugin directory and work out what is installed</li>
 *   <li>{@code START_PLUGIN} -- start them</li>
 *   <li>{@code STOP_PLUGIN} -- stop them</li>
 * </ul>
 * Both start phases belong to the <b>STARTUP_PRE</b> sequence, so plugins are running before any
 * core service reaches {@code START_SERVICE}. That ordering is inherited, not chosen, and it has a
 * consequence plugin authors must know: the APIs a plugin can reach during {@code start()} are
 * wired but not yet serving. Work that needs the core belongs in an event handler, not in
 * {@code start()}. Changing the order would be a smaller change here than it was under OSGi, but it
 * would break every plugin written against the existing contract.
 */
public class PluginRuntimeService implements KillbillService {

    private static final Logger log = LoggerFactory.getLogger(PluginRuntimeService.class);

    private final DefaultPluginManager pluginManager;
    private final PluginRuntimeConfig config;
    private final PluginEventDispatcher eventDispatcher;
    private final PersistentBus externalBus;
    private final PluginNodeCommandListener nodeCommandListener;

    @Inject
    public PluginRuntimeService(final DefaultPluginManager pluginManager,
                                final PluginRuntimeConfig config,
                                final PluginEventDispatcher eventDispatcher,
                                @Named("externalBus") final PersistentBus externalBus,
                                final PluginNodeCommandListener nodeCommandListener) {
        this.pluginManager = Objects.requireNonNull(pluginManager, "pluginManager");
        this.config = Objects.requireNonNull(config, "config");
        this.eventDispatcher = Objects.requireNonNull(eventDispatcher, "eventDispatcher");
        this.externalBus = Objects.requireNonNull(externalBus, "externalBus");
        this.nodeCommandListener = Objects.requireNonNull(nodeCommandListener, "nodeCommandListener");
    }

    @Override
    public String getName() {
        return KILLBILL_SERVICES.PLUGIN_SERVICE.getServiceName();
    }

    @Override
    public int getRegistrationOrdering() {
        return KILLBILL_SERVICES.PLUGIN_SERVICE.getRegistrationOrdering();
    }

    /**
     * Reads the plugin directory. Nothing is loaded or run yet, so a malformed plugin surfaces here
     * as a skipped entry rather than as a failure to boot.
     */
    @LifecycleHandlerType(LifecycleLevel.INIT_PLUGIN)
    public void initialize() {
        final Path root = Path.of(config.getPluginInstallDir());
        log.info("Discovering plugins in {}", root);
        final int discovered = pluginManager.discover();
        log.info("Discovered {} plugin(s)", discovered);
    }

    /**
     * Starts the plugins. A plugin that fails is left {@code FAILED} and the rest carry on, unless
     * it was named mandatory -- a deployment that cannot take payments should not come up pretending
     * it can.
     */
    @LifecycleHandlerType(LifecycleLevel.START_PLUGIN)
    public void start() {
        // Subscribe before starting plugins, so a plugin that publishes a NotificationPluginApi in
        // its own start() cannot miss events that arrive immediately afterwards.
        try {
            eventDispatcher.register();
            externalBus.register(nodeCommandListener);
        } catch (final PersistentBus.EventBusException e) {
            // Plugins are still worth starting: everything except bus notifications keeps working,
            // and failing the boot here would take down a deployment over one optional capability.
            log.error("Plugins will not receive Kill Bill events and node commands will not reach them: "
                      + "could not subscribe to the external bus", e);
        }

        if (!config.isPluginStartEnabled()) {
            log.warn("Plugin startup is disabled; {} plugin(s) discovered but none will run",
                     pluginManager.list().size());
            return;
        }
        final List<String> started = pluginManager.startAll(mandatoryPlugins());
        log.info("Started {} plugin(s): {}", started.size(), started);
    }

    /**
     * Unsubscribes before stopping plugins, mirroring the start order.
     * <p>
     * The reason is the same one that governs the runtime's own teardown order: cut off new work
     * first, then tear down what serves it. Stopping plugins first would leave the dispatcher
     * briefly delivering events to a registry that is emptying underneath it.
     */
    @LifecycleHandlerType(LifecycleLevel.STOP_PLUGIN)
    public void stop() {
        try {
            externalBus.unregister(nodeCommandListener);
            eventDispatcher.unregister();
        } catch (final PersistentBus.EventBusException e) {
            log.warn("Failed to unsubscribe from the external bus", e);
        }
        log.info("Stopping plugins");
        pluginManager.stopAll();
    }

    private List<String> mandatoryPlugins() {
        final String configured = config.getMandatoryPlugins();
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        return Arrays.stream(configured.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}

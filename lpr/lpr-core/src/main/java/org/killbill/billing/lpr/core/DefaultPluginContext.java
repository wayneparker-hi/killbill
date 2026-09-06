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
import java.util.Map;
import java.util.Objects;

import org.killbill.billing.lpr.api.EventBus;
import org.killbill.billing.lpr.api.PluginConfig;
import org.killbill.billing.lpr.api.PluginContext;
import org.killbill.billing.lpr.api.ResourceRegistry;
import org.killbill.billing.lpr.api.ServiceRegistry;

/**
 * The capability boundary between one plugin and the runtime.
 * <p>
 * What this object exposes is exactly what a plugin may do. Note what is absent: the plugin
 * manager, the plugin registry, the lifecycle manager, the ClassLoader. A plugin extends the
 * platform; handing it the means to start and stop other plugins would let an extension take over
 * the runtime hosting it.
 * <p>
 * Platform services are supplied as a fixed map rather than looked up lazily. The runtime wires
 * them before any plugin starts, so a missing service is a deployment error worth failing on
 * immediately, not a condition to rediscover on every call.
 */
public class DefaultPluginContext implements PluginContext {

    private final String pluginId;
    private final String version;
    private final PluginConfig config;
    private final Path pluginRoot;
    private final Path jarPath;
    private final ServiceRegistry services;
    private final EventBus eventBus;
    private final ResourceRegistry resources;
    private final Map<Class<?>, Object> platformServices;

    public DefaultPluginContext(final String pluginId,
                                final String version,
                                final PluginConfig config,
                                final Path pluginRoot,
                                final Path jarPath,
                                final ServiceRegistry services,
                                final EventBus eventBus,
                                final ResourceRegistry resources,
                                final Map<Class<?>, Object> platformServices) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.version = Objects.requireNonNull(version, "version");
        this.config = Objects.requireNonNull(config, "config");
        this.pluginRoot = Objects.requireNonNull(pluginRoot, "pluginRoot");
        this.jarPath = Objects.requireNonNull(jarPath, "jarPath");
        this.services = Objects.requireNonNull(services, "services");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.platformServices = Map.copyOf(Objects.requireNonNullElse(platformServices, Map.of()));
    }

    @Override
    public String pluginId() {
        return pluginId;
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public PluginConfig config() {
        return config;
    }

    @Override
    public ServiceRegistry services() {
        return services;
    }

    @Override
    public EventBus eventBus() {
        return eventBus;
    }

    @Override
    public ResourceRegistry resources() {
        return resources;
    }

    @Override
    public Path pluginRoot() {
        return pluginRoot;
    }

    @Override
    public Path jarPath() {
        return jarPath;
    }

    @Override
    public <T> T getPlatformService(final Class<T> type) {
        Objects.requireNonNull(type, "type");
        final Object service = platformServices.get(type);
        if (service == null) {
            throw new IllegalArgumentException("Plugin " + pluginId + " asked for platform service "
                                               + type.getName() + ", which this runtime does not publish. "
                                               + "Available: " + platformServices.keySet());
        }
        return type.cast(service);
    }

    @Override
    public String toString() {
        return "PluginContext{pluginId=" + pluginId + ", version=" + version + '}';
    }
}

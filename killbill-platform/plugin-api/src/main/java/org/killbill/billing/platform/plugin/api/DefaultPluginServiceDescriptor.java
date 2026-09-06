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

package org.killbill.billing.platform.plugin.api;

import java.util.Objects;

/**
 * The descriptor implementation, for the runtime and for services the core provides itself.
 * <p>
 * Kill Bill registers a handful of built-in implementations -- the external payment provider, the
 * invoice payment control plugin, the no-op invoice provider -- and each previously did so through
 * an anonymous class implementing three methods. A record removes that ceremony.
 *
 * @param pluginId         publishing plugin's identity
 * @param pluginVersion    publishing plugin's version
 * @param registrationName the name the core looks the service up by
 */
public record DefaultPluginServiceDescriptor(String pluginId, String pluginVersion, String registrationName)
        implements PluginServiceDescriptor {

    public DefaultPluginServiceDescriptor {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(pluginVersion, "pluginVersion");
        Objects.requireNonNull(registrationName, "registrationName");
    }

    /**
     * For services the core itself provides, where identity and lookup name coincide and there is
     * no meaningful plugin version.
     *
     * @param pluginId doubles as the registration name
     * @return a descriptor for a built-in service
     */
    public static DefaultPluginServiceDescriptor builtIn(final String pluginId) {
        return new DefaultPluginServiceDescriptor(pluginId, "built-in", pluginId);
    }

    @Override
    public String getPluginId() {
        return pluginId;
    }

    @Override
    public String getPluginVersion() {
        return pluginVersion;
    }

    @Override
    public String getRegistrationName() {
        return registrationName;
    }
}

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

package org.killbill.billing.runtime.api;

import java.util.Objects;
import java.util.Set;

/**
 * A plugin as reported to operators and stored in {@code node_infos}.
 * <p>
 * Lives here rather than inside the runtime because {@code NodeInfo} needs to build one when
 * deserialising a peer node's state. Its previous home was a nested class inside the OSGi layer's
 * {@code DefaultPluginsInfoApi}, which is why the util module had a compile dependency on the
 * plugin runtime implementation for the sake of two constructors.
 *
 * @param pluginKey        the key the plugin was installed under
 * @param pluginName       the name it registers services under
 * @param version          the version currently loaded
 * @param pluginState      whether it is running
 * @param services         the services it registered
 * @param selectedForStart whether this is the version that starts by default
 */
public record DefaultPluginInfo(String pluginKey,
                                String pluginName,
                                String version,
                                PluginState pluginState,
                                Set<PluginServiceInfo> services,
                                boolean selectedForStart) implements PluginInfo {

    public DefaultPluginInfo {
        Objects.requireNonNull(pluginName, "pluginName");
        services = Set.copyOf(Objects.requireNonNullElse(services, Set.of()));
    }

    @Override
    public String getPluginKey() {
        return pluginKey;
    }

    @Override
    public String getPluginName() {
        return pluginName;
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public PluginState getPluginState() {
        return pluginState;
    }

    @Override
    public Set<PluginServiceInfo> getServices() {
        return services;
    }

    @Override
    public boolean isSelectedForStart() {
        return selectedForStart;
    }
}

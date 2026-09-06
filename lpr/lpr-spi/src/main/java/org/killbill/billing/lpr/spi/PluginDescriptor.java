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

package org.killbill.billing.lpr.spi;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What a plugin declares about itself, parsed from its {@code plugin.yaml}.
 * <p>
 * The entrypoint is declared, not discovered. The OSGi build inferred it by scanning the jar for a
 * class extending {@code KillbillActivatorBase}, which meant a plugin's entry point depended on a
 * build-time bytecode scan that nothing in the source made visible. Naming it here costs one line
 * in a file and makes the answer greppable.
 *
 * @param pluginId        stable identity, used everywhere the runtime names this plugin
 * @param name            human-readable name for logs and the management API
 * @param version         semantic version
 * @param entrypointClass fully qualified name of the {@code Plugin} implementation
 * @param config          configuration defaults declared by the plugin; may be empty
 */
public record PluginDescriptor(String pluginId,
                               String name,
                               String version,
                               String entrypointClass,
                               Map<String, String> config) {

    /**
     * Plugin ids appear in URLs, directory names, configuration keys and metrics dimensions, so
     * they are restricted to what is safe in all of those: lowercase, digits, dot and dash.
     * <p>
     * The same shape the OSGi layer enforced on {@code killbill.pluginName}, minus its 40-character
     * cap, which existed for OSGi symbolic names rather than for any reason of ours.
     */
    private static final Pattern VALID_PLUGIN_ID = Pattern.compile("[a-z0-9][a-z0-9.-]*");

    public PluginDescriptor {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(entrypointClass, "entrypointClass");

        if (!VALID_PLUGIN_ID.matcher(pluginId).matches()) {
            throw new IllegalArgumentException("Invalid plugin id '" + pluginId + "': must match "
                                               + VALID_PLUGIN_ID.pattern()
                                               + " (a runtime identity, not a Java class name)");
        }
        config = Map.copyOf(Objects.requireNonNullElse(config, Map.of()));
    }

    /**
     * @param pluginId        stable identity
     * @param version         semantic version
     * @param entrypointClass fully qualified {@code Plugin} implementation
     * @return a descriptor whose name defaults to the id and which declares no configuration
     */
    public static PluginDescriptor of(final String pluginId, final String version, final String entrypointClass) {
        return new PluginDescriptor(pluginId, pluginId, version, entrypointClass, Map.of());
    }
}

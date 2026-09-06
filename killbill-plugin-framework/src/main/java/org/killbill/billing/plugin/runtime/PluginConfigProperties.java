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

package org.killbill.billing.plugin.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

import org.killbill.billing.lpr.api.PluginContext;
import org.killbill.billing.platform.api.KillbillConfigSource;

/**
 * A plugin's settings: its own descriptor first, then {@code killbill.properties}.
 * <p>
 * Replaces {@code OSGIConfigPropertiesService}, which could only see the global properties file --
 * every plugin's settings shared one flat namespace, and two plugins wanting a {@code .timeout} had
 * to agree on prefixes. A plugin's {@code plugin.yaml} is scoped to that plugin and cannot collide,
 * so it takes precedence; the properties file remains as the deployment-wide override and as the
 * path a plugin migrated from OSGi keeps working on without being reconfigured.
 */
public class PluginConfigProperties {

    private final PluginContext context;

    public PluginConfigProperties(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /**
     * @param propertyName the key
     * @return its value, or null if neither source has it
     */
    public String getString(final String propertyName) {
        return find(propertyName).orElse(null);
    }

    /**
     * @param propertyName the key
     * @param fallback     what to use when neither source has it
     * @return the value, never null unless the fallback is
     */
    public String getString(final String propertyName, final String fallback) {
        return find(propertyName).orElse(fallback);
    }

    /**
     * @param propertyName the key
     * @return the value if either source has it
     */
    public Optional<String> find(final String propertyName) {
        Objects.requireNonNull(propertyName, "propertyName");
        return context.config()
                      .find(propertyName)
                      .or(() -> Optional.ofNullable(platformConfig())
                                        .map(source -> source.getString(propertyName)));
    }

    /**
     * Everything the plugin's own descriptor declares.
     * <p>
     * Deliberately not merged with {@code killbill.properties}: that file holds the whole
     * deployment's configuration, and handing a plugin a {@code Properties} containing every other
     * plugin's credentials would be a poor default. Ask for the keys you need by name.
     *
     * @return a copy; never null
     */
    public Properties getProperties() {
        final Properties properties = new Properties();
        properties.putAll(context.config().all());
        return properties;
    }

    /**
     * @return the plugin's own settings, as declared in its descriptor
     */
    public Map<String, String> asMap() {
        return new LinkedHashMap<>(context.config().all());
    }

    /**
     * The platform's configuration, when this deployment publishes it.
     * <p>
     * A test runtime often does not, and a plugin that only reads its own descriptor should keep
     * working there -- so its absence resolves to "no value" rather than to an exception.
     */
    private KillbillConfigSource platformConfig() {
        try {
            return context.getPlatformService(KillbillConfigSource.class);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }
}

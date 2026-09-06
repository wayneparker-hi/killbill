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

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.killbill.billing.lpr.api.PluginConfig;

/**
 * A plugin's effective configuration: an immutable snapshot the runtime resolved by layering
 * system, plugin and tenant settings before the plugin started.
 * <p>
 * {@link #get} throws on a missing key while {@link #find} returns empty. That split is the point:
 * a value the descriptor declares required should fail loudly at startup, where the operator can
 * see it, rather than surfacing as a null halfway through someone's payment.
 */
public class DefaultPluginConfig implements PluginConfig {

    private final String pluginId;
    private final Map<String, String> values;

    /**
     * @param pluginId owning plugin, used in error messages
     * @param values   resolved configuration; copied
     */
    public DefaultPluginConfig(final String pluginId, final Map<String, String> values) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.values = Map.copyOf(Objects.requireNonNullElse(values, Map.of()));
    }

    @Override
    public String get(final String key) {
        final String value = values.get(Objects.requireNonNull(key, "key"));
        if (value == null) {
            throw new IllegalArgumentException("Plugin " + pluginId + " requires configuration key '"
                                               + key + "', which is not set");
        }
        return value;
    }

    @Override
    public Optional<String> find(final String key) {
        return Optional.ofNullable(values.get(Objects.requireNonNull(key, "key")));
    }

    @Override
    public <T> Optional<T> find(final String key, final Class<T> type) {
        Objects.requireNonNull(type, "type");
        return find(key).map(raw -> convert(key, raw, type));
    }

    @Override
    public Map<String, String> all() {
        return values;
    }

    @SuppressWarnings("unchecked")
    private <T> T convert(final String key, final String raw, final Class<T> type) {
        try {
            if (type == String.class) {
                return (T) raw;
            }
            if (type == Boolean.class || type == boolean.class) {
                return (T) Boolean.valueOf(raw);
            }
            if (type == Integer.class || type == int.class) {
                return (T) Integer.valueOf(raw.trim());
            }
            if (type == Long.class || type == long.class) {
                return (T) Long.valueOf(raw.trim());
            }
            if (type == Double.class || type == double.class) {
                return (T) Double.valueOf(raw.trim());
            }
            if (type.isEnum()) {
                return (T) Enum.valueOf(type.asSubclass(Enum.class), raw.trim());
            }
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("Plugin " + pluginId + ": configuration key '" + key
                                               + "' has value '" + raw + "', which is not a valid "
                                               + type.getSimpleName(), e);
        }
        throw new IllegalArgumentException("Plugin " + pluginId + ": configuration key '" + key
                                           + "' cannot be converted to " + type.getName()
                                           + "; supported types are String, boolean, int, long, double and enums");
    }

    @Override
    public String toString() {
        return "PluginConfig{pluginId=" + pluginId + ", keys=" + values.keySet() + '}';
    }
}

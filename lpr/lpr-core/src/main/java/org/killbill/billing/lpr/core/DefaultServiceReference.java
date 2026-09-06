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

import org.killbill.billing.lpr.api.ServiceReference;

/**
 * One published service and the identity behind it.
 * <p>
 * Immutable, and deliberately compared by identity: two plugins may register equal-looking
 * services, and withdrawing one must not withdraw the other. Value equality here would make
 * {@code List.remove} pick an arbitrary match.
 *
 * @param <T> service type
 */
final class DefaultServiceReference<T> implements ServiceReference<T> {

    private final String pluginId;
    private final String version;
    private final Class<T> type;
    private final T service;
    private final Map<String, Object> properties;

    DefaultServiceReference(final String pluginId,
                            final String version,
                            final Class<T> type,
                            final T service,
                            final Map<String, Object> properties) {
        this.pluginId = pluginId;
        this.version = version;
        this.type = type;
        this.service = service;
        // Copied, so a plugin mutating the map it passed in cannot reorder lookups afterwards.
        this.properties = properties == null ? Map.of() : Map.copyOf(properties);
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
    public Class<T> type() {
        return type;
    }

    @Override
    public T service() {
        return service;
    }

    @Override
    public Map<String, Object> properties() {
        return properties;
    }

    int priority() {
        final Object priority = properties.get(DefaultServiceRegistry.PRIORITY_PROPERTY);
        return priority instanceof Integer ? (Integer) priority : 0;
    }

    @Override
    public boolean equals(final Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public String toString() {
        return "ServiceReference{plugin=" + pluginId + '/' + version
               + ", type=" + type.getName()
               + ", properties=" + properties + '}';
    }
}

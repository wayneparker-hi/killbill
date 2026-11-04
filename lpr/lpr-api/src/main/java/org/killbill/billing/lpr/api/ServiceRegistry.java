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

package org.killbill.billing.lpr.api;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Where plugins publish implementations for the core to call, and where the core finds them.
 * <p>
 * A registration's lifetime is bound to its plugin: entries appear while the plugin is
 * {@link PluginState#STARTING} and are withdrawn before it reaches {@link PluginState#STOPPED}.
 * A stopped plugin is never reachable through lookup -- that invariant is what keeps callers from
 * invoking code whose ClassLoader has been closed.
 * <p>
 * Implementations are safe for concurrent use.
 */
public interface ServiceRegistry {

    /**
     * Publishes a service under the calling plugin's identity.
     *
     * @param type    the service interface, loaded by the parent ClassLoader
     * @param service the implementation
     * @param <T>     service type
     * @return a handle that withdraws the service when closed
     */
    <T> ServiceRegistration register(Class<T> type, T service);

    /**
     * Publishes a service with selector properties, for cases where several plugins implement the
     * same interface and the caller picks by attribute (provider, currency, payment method, ...).
     *
     * @param type       the service interface
     * @param service    the implementation
     * @param properties selector properties; copied, never referenced
     * @param <T>        service type
     * @return a handle that withdraws the service when closed
     */
    <T> ServiceRegistration register(Class<T> type, T service, Map<String, Object> properties);

    /** Publishes an explicit contract version; conflicting property declarations are rejected. */
    default <T> ServiceRegistration register(final Class<T> type, final T service, final ServiceApiVersion apiVersion,
                                             final Map<String, Object> properties) {
        Objects.requireNonNull(apiVersion, "apiVersion");
        final Map<String, Object> declared = new HashMap<>(properties);
        if (declared.containsKey(ServiceApiVersion.PROPERTY)
            && !apiVersion.toString().equals(declared.get(ServiceApiVersion.PROPERTY))) {
            throw new IllegalArgumentException("Conflicting service API versions");
        }
        declared.put(ServiceApiVersion.PROPERTY, apiVersion.toString());
        return register(type, service, declared);
    }

    /**
     * Looks up a service published by a named plugin. This is the lookup Kill Bill's core uses:
     * it resolves a configured plugin name to an implementation.
     *
     * @param type     the service interface
     * @param pluginId the publishing plugin
     * @param <T>      service type
     * @return the service, or empty if that plugin publishes no such service right now
     */
    <T> Optional<T> getService(Class<T> type, String pluginId);

    /** Finds a compatible provider before handing its implementation to the caller. */
    default <T> Optional<T> getService(final Class<T> type, final String pluginId, final ServiceApiVersion required) {
        Objects.requireNonNull(pluginId, "pluginId");
        return getServices(type, required).stream().filter(reference -> reference.pluginId().equals(pluginId))
                .map(ServiceReference::service).findFirst();
    }

    /**
     * All live registrations for a service type, ordered by descending priority then by plugin id
     * so that iteration order is stable across restarts.
     *
     * @param type the service interface
     * @param <T>  service type
     * @return references; empty if none
     */
    <T> List<ServiceReference<T>> getServices(Class<T> type);

    /** All visible providers supporting this consumer contract, retaining registry ordering. */
    default <T> List<ServiceReference<T>> getServices(final Class<T> type, final ServiceApiVersion required) {
        Objects.requireNonNull(required, "required");
        return getServices(type).stream().filter(reference -> reference.apiVersion().supports(required)).toList();
    }

    /**
     * Ids of the plugins currently publishing a service type.
     *
     * @param type the service interface
     * @return plugin ids; empty if none
     */
    List<String> getPluginIds(Class<?> type);
}

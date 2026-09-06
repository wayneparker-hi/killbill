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

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.killbill.billing.platform.plugin.api.PluginServiceDescriptor;
import org.killbill.billing.platform.plugin.api.PluginServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A typed registry with no behaviour beyond the map.
 * <p>
 * Most plugin service types need exactly this. The OSGi layer had a separate class per type, each
 * an identical fifty lines around a {@code ConcurrentHashMap}; the ones that differ (payment, which
 * knows a default provider) still get their own class.
 *
 * @param <T> the service type
 */
public class MapBackedPluginServiceRegistry<T> implements PluginServiceRegistry<T> {

    private static final Logger log = LoggerFactory.getLogger(MapBackedPluginServiceRegistry.class);

    private final Class<T> serviceType;
    private final Map<String, T> servicesByName = new ConcurrentHashMap<>();

    public MapBackedPluginServiceRegistry(final Class<T> serviceType) {
        this.serviceType = Objects.requireNonNull(serviceType, "serviceType");
    }

    @Override
    public void registerService(final PluginServiceDescriptor descriptor, final T service) {
        log.info("Registering {} '{}'", serviceType.getSimpleName(), descriptor.getRegistrationName());
        servicesByName.put(descriptor.getRegistrationName(), service);
    }

    @Override
    public void unregisterService(final String registrationName) {
        log.info("Unregistering {} '{}'", serviceType.getSimpleName(), registrationName);
        servicesByName.remove(registrationName);
    }

    @Override
    public T getServiceForName(final String registrationName) {
        return registrationName == null ? null : servicesByName.get(registrationName);
    }

    @Override
    public Set<String> getAllServices() {
        return servicesByName.keySet();
    }

    @Override
    public Class<T> getServiceType() {
        return serviceType;
    }

    @Override
    public String toString() {
        return "PluginServiceRegistry{" + serviceType.getSimpleName() + ", registered=" + servicesByName.keySet() + '}';
    }
}

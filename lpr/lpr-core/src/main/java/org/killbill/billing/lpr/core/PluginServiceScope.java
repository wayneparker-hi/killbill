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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.killbill.billing.lpr.api.ServiceReference;
import org.killbill.billing.lpr.api.ServiceRegistration;
import org.killbill.billing.lpr.api.ServiceRegistry;

/** A context's registration capability expires before its plugin begins stopping. */
final class PluginServiceScope implements ServiceRegistry, AutoCloseable {
    private final DefaultServiceRegistry store;
    private final ServiceRegistry delegate;
    private final String pluginId;
    private final String version;
    private boolean closed;

    PluginServiceScope(final DefaultServiceRegistry store, final String pluginId, final String version) {
        this.store = store;
        this.delegate = store.forPlugin(pluginId, version);
        this.pluginId = pluginId;
        this.version = version;
    }

    @Override
    public synchronized <T> ServiceRegistration register(final Class<T> type, final T service) {
        return register(type, service, Map.of());
    }

    @Override
    public synchronized <T> ServiceRegistration register(final Class<T> type, final T service,
                                                         final Map<String, Object> properties) {
        if (closed) { throw new IllegalStateException("Plugin service scope is closed: " + pluginId); }
        return delegate.register(type, service, properties);
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            store.unregisterAll(pluginId, version);
        }
    }

    @Override
    public <T> Optional<T> getService(final Class<T> type, final String id) { return delegate.getService(type, id); }
    @Override
    public <T> List<ServiceReference<T>> getServices(final Class<T> type) { return delegate.getServices(type); }
    @Override
    public List<String> getPluginIds(final Class<?> type) { return delegate.getPluginIds(type); }
}

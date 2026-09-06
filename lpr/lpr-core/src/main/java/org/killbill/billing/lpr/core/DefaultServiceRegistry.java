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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.killbill.billing.lpr.api.ServiceReference;
import org.killbill.billing.lpr.api.ServiceRegistration;
import org.killbill.billing.lpr.api.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one place that knows which plugin currently provides what.
 * <p>
 * Kill Bill's core resolves a configured plugin name to an implementation on every payment,
 * invoice, catalog and usage call, so two properties are load-bearing:
 * <ul>
 *   <li><b>A stopped plugin is unreachable.</b> {@link #unregisterAll(String)} runs before a plugin
 *       reaches {@code STOPPED}, so no lookup can hand out an implementation whose ClassLoader is
 *       about to be closed. The predecessor OSGi layer relied on the same invariant; getting it
 *       wrong produces the classic stale-service crash long after the plugin went away.</li>
 *   <li><b>Iteration order is stable.</b> Several plugins may implement the same interface, and
 *       core code such as the invoice dispatcher calls them in sequence. Ordering by descending
 *       priority then plugin id means the sequence does not change between restarts.</li>
 * </ul>
 * Plugins never see this class. They get a {@link #forPlugin} view, which stamps their identity
 * onto every registration so a plugin cannot publish a service in another plugin's name.
 * <p>
 * Safe for concurrent use.
 */
public class DefaultServiceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultServiceRegistry.class);

    /** Selector property carrying an {@link Integer} ranking; higher wins. Absent means zero. */
    public static final String PRIORITY_PROPERTY = "priority";

    private final Map<Class<?>, CopyOnWriteArrayList<DefaultServiceReference<?>>> servicesByType =
            new ConcurrentHashMap<>();

    /**
     * Hosts that mirror registrations into structures of their own. Copy-on-write because listeners
     * are added once at startup and then read on every registration.
     */
    private final CopyOnWriteArrayList<ServiceRegistryListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * A plugin-facing view that stamps {@code pluginId} and {@code version} onto every
     * registration.
     *
     * @param pluginId the plugin
     * @param version  the plugin version
     * @return a registry to hand to that plugin's context
     */
    public ServiceRegistry forPlugin(final String pluginId, final String version) {
        return new PluginScopedServiceRegistry(this,
                                               Objects.requireNonNull(pluginId, "pluginId"),
                                               Objects.requireNonNull(version, "version"));
    }

    /**
     * Registers a host-side listener. See {@link ServiceRegistryListener} for what it is for.
     *
     * @param listener notified as services come and go
     */
    public void addListener(final ServiceRegistryListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Publishes a service on behalf of a plugin.
     *
     * @param pluginId   the publishing plugin
     * @param version    the publishing plugin's version
     * @param type       the service interface
     * @param service    the implementation
     * @param properties selector properties; copied
     * @param <T>        service type
     * @return a handle that withdraws the service when closed
     */
    public <T> ServiceRegistration register(final String pluginId,
                                            final String version,
                                            final Class<T> type,
                                            final T service,
                                            final Map<String, Object> properties) {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(service, "service");

        if (!type.isInstance(service)) {
            // Nearly always means the service was loaded by a ClassLoader that resolved `type` to a
            // different Class object -- that is, the policy is not treating this package as
            // parent-first. Failing here beats a ClassCastException at the first business call.
            throw new IllegalArgumentException("Plugin " + pluginId + " registered a "
                                               + service.getClass().getName() + " as " + type.getName()
                                               + ", which it is not an instance of");
        }

        final DefaultServiceReference<T> reference =
                new DefaultServiceReference<>(pluginId, version, type, service, properties);

        servicesByType.compute(type, (t, existing) -> {
            final CopyOnWriteArrayList<DefaultServiceReference<?>> peers =
                    existing != null ? existing : new CopyOnWriteArrayList<>();
            peers.add(reference);
            return peers;
        });

        log.debug("Plugin {} registered {} (priority {})", pluginId, type.getName(), reference.priority());
        notifyRegistered(reference);
        return new DefaultServiceRegistration(this, reference);
    }

    /**
     * Looks up the service published by a named plugin. This is the shape Kill Bill's core needs:
     * configuration names a plugin, and the call has to reach that plugin's implementation.
     *
     * @param type     the service interface
     * @param pluginId the publishing plugin
     * @param <T>      service type
     * @return the service, or empty if that plugin publishes no such service right now
     */
    public <T> Optional<T> getService(final Class<T> type, final String pluginId) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(pluginId, "pluginId");

        for (final DefaultServiceReference<?> reference : referencesFor(type)) {
            if (reference.pluginId().equals(pluginId)) {
                return Optional.of(type.cast(reference.service()));
            }
        }
        return Optional.empty();
    }

    /**
     * @param type the service interface
     * @param <T>  service type
     * @return live references, highest priority first, ties broken by plugin id
     */
    @SuppressWarnings("unchecked")
    public <T> List<ServiceReference<T>> getServices(final Class<T> type) {
        Objects.requireNonNull(type, "type");

        final List<ServiceReference<T>> ordered = new ArrayList<>();
        for (final DefaultServiceReference<?> reference : referencesFor(type)) {
            ordered.add((ServiceReference<T>) reference);
        }
        ordered.sort(Comparator
                             .comparingInt((ServiceReference<T> r) -> priorityOf(r))
                             .reversed()
                             .thenComparing(ServiceReference::pluginId));
        return List.copyOf(ordered);
    }

    /**
     * @param type the service interface
     * @return ids of plugins currently publishing it, in the same order as {@link #getServices}
     */
    public List<String> getPluginIds(final Class<?> type) {
        return getServices(type).stream().map(ServiceReference::pluginId).toList();
    }

    /**
     * What a plugin is currently publishing.
     * <p>
     * Read from the registry rather than from the plugin's descriptor, so the answer reflects what
     * the plugin actually registered. A plugin that declared it provides a payment API but failed
     * before registering it should not be reported as providing one.
     *
     * @param pluginId the plugin
     * @return the service interfaces it publishes; empty if none
     */
    public List<Class<?>> publishedTypes(final String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        return servicesByType.entrySet().stream()
                             .filter(entry -> entry.getValue().stream()
                                                   .anyMatch(reference -> reference.pluginId().equals(pluginId)))
                             .map(Map.Entry::getKey)
                             .toList();
    }

    /**
     * The same view as {@link #publishedTypes(String)}, but as the registrations themselves.
     * <p>
     * Callers that only need the interface list should use {@code publishedTypes}; this exists for
     * the ones that also need the properties a service was registered under -- reporting a plugin's
     * services by the name they answer to, for instance.
     *
     * @param pluginId the plugin
     * @return its current registrations; empty if none
     */
    public List<ServiceReference<?>> publishedServices(final String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        return servicesByType.values().stream()
                             .flatMap(Collection::stream)
                             .filter(reference -> reference.pluginId().equals(pluginId))
                             .<ServiceReference<?>>map(reference -> reference)
                             .toList();
    }

    /**
     * Withdraws everything published by one <b>version</b> of a plugin.
     * <p>
     * The lifecycle calls this while the plugin is {@code STOPPING}, before {@code Plugin.stop()}
     * and long before the ClassLoader closes, so that in-flight callers finish against a live
     * implementation while new callers can no longer find one.
     * <p>
     * The version matters. During an upgrade both versions are loaded at once and share a plugin
     * id, so withdrawing by id alone would take the incoming version's registrations down together
     * with the outgoing one's -- leaving the plugin installed, running, and providing nothing.
     *
     * @param pluginId the plugin being stopped
     * @param version  the version being stopped
     * @return how many registrations were withdrawn
     */
    public int unregisterAll(final String pluginId, final String version) {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(version, "version");
        return removeMatching(reference -> reference.pluginId().equals(pluginId)
                                           && reference.version().equals(version));
    }

    /**
     * Withdraws every version's registrations for a plugin. Used when tearing the runtime down,
     * where nothing is coming back up and precision costs more than it buys.
     *
     * @param pluginId the plugin
     * @return how many registrations were withdrawn
     */
    public int unregisterAll(final String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        return removeMatching(reference -> reference.pluginId().equals(pluginId));
    }

    private int removeMatching(final java.util.function.Predicate<DefaultServiceReference<?>> doomed) {

        // Collected inside the compute so the tally cannot drift when another thread registers
        // concurrently; reading the size before and after would be two unrelated snapshots.
        final List<DefaultServiceReference<?>> removed = new ArrayList<>();
        for (final Class<?> type : List.copyOf(servicesByType.keySet())) {
            servicesByType.computeIfPresent(type, (t, peers) -> {
                peers.stream().filter(doomed).forEach(removed::add);
                peers.removeIf(doomed);
                return peers.isEmpty() ? null : peers;
            });
        }
        // Notified outside the compute: a listener may take its own locks, and holding a map bin
        // while calling into host code is how deadlocks are built.
        removed.forEach(this::notifyUnregistered);
        return removed.size();
    }

    private void notifyRegistered(final DefaultServiceReference<?> reference) {
        for (final ServiceRegistryListener listener : listeners) {
            try {
                listener.onRegistered(reference);
            } catch (final RuntimeException e) {
                log.error("Service registry listener failed for {}", reference, e);
            }
        }
    }

    private void notifyUnregistered(final DefaultServiceReference<?> reference) {
        for (final ServiceRegistryListener listener : listeners) {
            try {
                listener.onUnregistered(reference);
            } catch (final RuntimeException e) {
                log.error("Service registry listener failed for {}", reference, e);
            }
        }
    }

    void unregister(final DefaultServiceReference<?> reference) {
        final boolean[] wasPresent = {false};
        servicesByType.computeIfPresent(reference.type(), (type, peers) -> {
            wasPresent[0] = peers.remove(reference);
            return peers.isEmpty() ? null : peers;
        });
        if (wasPresent[0]) {
            notifyUnregistered(reference);
        }
    }

    private List<DefaultServiceReference<?>> referencesFor(final Class<?> type) {
        final CopyOnWriteArrayList<DefaultServiceReference<?>> found = servicesByType.get(type);
        return found == null ? List.of() : found;
    }

    private static int priorityOf(final ServiceReference<?> reference) {
        final Object priority = reference.properties().get(PRIORITY_PROPERTY);
        return priority instanceof Integer ? (Integer) priority : 0;
    }

    /**
     * What a plugin actually holds: the shared store plus its own identity, so it can only ever
     * publish under its own name.
     */
    private static final class PluginScopedServiceRegistry implements ServiceRegistry {

        private final DefaultServiceRegistry store;
        private final String pluginId;
        private final String version;

        private PluginScopedServiceRegistry(final DefaultServiceRegistry store,
                                            final String pluginId,
                                            final String version) {
            this.store = store;
            this.pluginId = pluginId;
            this.version = version;
        }

        @Override
        public <T> ServiceRegistration register(final Class<T> type, final T service) {
            return store.register(pluginId, version, type, service, Map.of());
        }

        @Override
        public <T> ServiceRegistration register(final Class<T> type,
                                                final T service,
                                                final Map<String, Object> properties) {
            return store.register(pluginId, version, type, service, properties);
        }

        @Override
        public <T> Optional<T> getService(final Class<T> type, final String targetPluginId) {
            return store.getService(type, targetPluginId);
        }

        @Override
        public <T> List<ServiceReference<T>> getServices(final Class<T> type) {
            return store.getServices(type);
        }

        @Override
        public List<String> getPluginIds(final Class<?> type) {
            return store.getPluginIds(type);
        }

        @Override
        public String toString() {
            return "ServiceRegistry{pluginId=" + pluginId + '}';
        }
    }
}

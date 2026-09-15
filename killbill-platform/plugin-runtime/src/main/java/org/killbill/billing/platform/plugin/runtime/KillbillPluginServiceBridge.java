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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import org.killbill.billing.lpr.api.ServiceReference;
import org.killbill.billing.lpr.core.ServiceRegistryListener;
import org.killbill.billing.platform.plugin.api.DefaultPluginServiceDescriptor;
import org.killbill.billing.platform.plugin.api.PluginServiceProperties;
import org.killbill.billing.platform.plugin.api.PluginServiceRegistry;
import org.killbill.billing.platform.plugin.api.SinglePluginServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mirrors plugin service registrations into the per-type registries Kill Bill's business modules
 * inject.
 * <p>
 * The runtime holds one registry keyed by service type; the business modules want one registry
 * <i>per</i> service type, injected where that type is called from, so a payment call is a single
 * map lookup. This class keeps the second in step with the first.
 * <p>
 * It is the direct descendant of {@code KillbillActivator.serviceChanged}, minus OSGi: same job of
 * watching registrations and routing them to the matching typed registry, same plugin-name
 * validation, same context-ClassLoader wrapping.
 * <p>
 * Registries are supplied rather than discovered, and the set is whatever the host binds -- a
 * deployment without a catalog plugin registry simply does not pass one.
 */
public class KillbillPluginServiceBridge implements ServiceRegistryListener {

    private static final Logger log = LoggerFactory.getLogger(KillbillPluginServiceBridge.class);

    /**
     * Registration names end up in URLs, configuration values and database columns (a payment
     * method row stores the plugin name it was created with), so they are restricted to what is
     * safe everywhere. Inherited from the OSGi layer, minus its 40-character cap, which existed for
     * OSGi symbolic names.
     */
    private static final Pattern VALID_REGISTRATION_NAME = Pattern.compile("[a-z][a-z0-9._-]*");

    private final List<PluginServiceRegistry<?>> registries;
    private final List<SinglePluginServiceRegistry<?>> singleRegistries;

    /*
     * The typed Kill Bill registries are keyed by registration name, whereas LPR keeps old and new
     * versions distinct during an upgrade. Retain the bridge's routing history so removing an old
     * registration cannot accidentally withdraw the newer service that replaced it under the same
     * name. Identity is intentional: a ServiceReference is one registration, even when a plugin
     * publishes the same type and name more than once.
     */
    private final Map<ServiceReference<?>, RoutedRegistration> routedByReference = new IdentityHashMap<>();
    private final Map<RouteKey, List<RoutedRegistration>> routedByTarget = new HashMap<>();

    /**
     * @param registries one per plugin service type; nulls are ignored so a host can pass optional
     *                   bindings straight through
     */
    public KillbillPluginServiceBridge(final List<PluginServiceRegistry<?>> registries) {
        this(registries, List.of());
    }

    /**
     * @param registries       one per plugin service type
     * @param singleRegistries types of which there can be only one implementation
     */
    public KillbillPluginServiceBridge(final List<PluginServiceRegistry<?>> registries,
                                       final List<SinglePluginServiceRegistry<?>> singleRegistries) {
        this.registries = List.copyOf(withoutNulls(registries));
        this.singleRegistries = List.copyOf(withoutNulls(singleRegistries));
        log.info("Plugin service bridge wired for {} service type(s): {}",
                 this.registries.size() + this.singleRegistries.size(),
                 java.util.stream.Stream.concat(
                         this.registries.stream().map(r -> r.getServiceType().getSimpleName()),
                         this.singleRegistries.stream().map(r -> r.getServiceType().getSimpleName()))
                     .sorted().toList());
    }

    private static <T> List<T> withoutNulls(final List<T> values) {
        final List<T> present = new ArrayList<>();
        for (final T value : Objects.requireNonNullElse(values, List.<T>of())) {
            if (value != null) {
                present.add(value);
            }
        }
        return present;
    }

    @Override
    public synchronized void onRegistered(final ServiceReference<?> reference) {
        final String registrationName = registrationNameOf(reference);
        if (!VALID_REGISTRATION_NAME.matcher(registrationName).matches()) {
            log.warn("Plugin {} tried to register {} under the invalid name '{}'; ignoring. "
                     + "Names must match {}",
                     reference.pluginId(), reference.type().getName(), registrationName,
                     VALID_REGISTRATION_NAME.pattern());
            return;
        }

        final Optional<PluginServiceRegistry<?>> registry = findRegistryFor(reference.type());
        if (registry.isPresent()) {
            route(reference, register(registry.get(), reference, registrationName));
            return;
        }
        final Optional<SinglePluginServiceRegistry<?>> single = findSingleRegistryFor(reference.type());
        if (single.isPresent()) {
            route(reference, registerSingle(single.get(), reference, registrationName));
            return;
        }
        log.debug("Plugin {} published {}, which this deployment does not consume",
                  reference.pluginId(), reference.type().getName());
    }

    @Override
    public synchronized void onUnregistered(final ServiceReference<?> reference) {
        final RoutedRegistration routed = routedByReference.remove(reference);
        if (routed == null) {
            // The registration was invalid or belongs to a service type this deployment does not
            // consume, so it never reached a typed registry.
            return;
        }

        final List<RoutedRegistration> registrations = routedByTarget.get(routed.key());
        final boolean wasCurrent = registrations.get(registrations.size() - 1) == routed;
        registrations.remove(routed);
        if (registrations.isEmpty()) {
            routedByTarget.remove(routed.key());
        }
        if (!wasCurrent) {
            return;
        }

        log.info("Withdrawing {} '{}' published by plugin {} version {}",
                 reference.type().getSimpleName(), routed.registrationName(), reference.pluginId(), reference.version());
        routed.unregisterAction().run();
        if (!registrations.isEmpty()) {
            // The most recent remaining registration becomes visible again. This also covers a
            // direct unregister of the newest registration without making an older live provider
            // disappear.
            registrations.get(registrations.size() - 1).registerAction().run();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> RoutedRegistration register(final PluginServiceRegistry<T> registry,
                                            final ServiceReference<?> reference,
                                            final String registrationName) {
        final Class<T> serviceType = registry.getServiceType();
        final T service = (T) reference.service();

        log.info("Registering {} '{}' published by plugin {} version {}",
                 serviceType.getSimpleName(), registrationName, reference.pluginId(), reference.version());

        final DefaultPluginServiceDescriptor descriptor =
                new DefaultPluginServiceDescriptor(reference.pluginId(), reference.version(), registrationName);
        final T proxied = PluginServiceContextClassLoaderProxy.wrap(service, serviceType);
        return new RoutedRegistration(registry,
                                      registrationName,
                                      () -> registry.registerService(descriptor, proxied),
                                      () -> registry.unregisterService(registrationName));
    }

    @SuppressWarnings("unchecked")
    private <T> RoutedRegistration registerSingle(final SinglePluginServiceRegistry<T> registry,
                                                  final ServiceReference<?> reference,
                                                  final String registrationName) {
        final Class<T> serviceType = registry.getServiceType();
        final DefaultPluginServiceDescriptor descriptor =
                new DefaultPluginServiceDescriptor(reference.pluginId(), reference.version(), registrationName);
        final T proxied = PluginServiceContextClassLoaderProxy.wrap((T) reference.service(), serviceType);
        return new RoutedRegistration(registry,
                                      registrationName,
                                      () -> registry.registerService(descriptor, proxied),
                                      () -> registry.unregisterService(registrationName));
    }

    private void route(final ServiceReference<?> reference, final RoutedRegistration routed) {
        routed.registerAction().run();
        routedByReference.put(reference, routed);
        routedByTarget.computeIfAbsent(routed.key(), ignored -> new ArrayList<>()).add(routed);
    }

    private Optional<SinglePluginServiceRegistry<?>> findSingleRegistryFor(final Class<?> serviceType) {
        return singleRegistries.stream()
                               .filter(registry -> registry.getServiceType().isAssignableFrom(serviceType))
                               .findFirst();
    }

    /**
     * Matched with {@code isAssignableFrom} rather than by exact type so that a plugin registering
     * a subtype still lands in the right registry -- the OSGi layer relied on the same, which is
     * how an {@code HttpServlet} reaches the {@code Servlet} registry.
     */
    private Optional<PluginServiceRegistry<?>> findRegistryFor(final Class<?> serviceType) {
        return registries.stream()
                         .filter(registry -> registry.getServiceType().isAssignableFrom(serviceType))
                         .findFirst();
    }

    /**
     * The plugin id, unless the plugin asked to be found under a different name.
     * <p>
     * Defaulting is the change from OSGi, where every plugin had to set {@code killbill.pluginName}
     * explicitly and registration silently did nothing if it forgot.
     */
    static String registrationNameOf(final ServiceReference<?> reference) {
        final Object declared = reference.properties().get(PluginServiceProperties.REGISTRATION_NAME);
        return declared instanceof String name && !name.isBlank() ? name.trim() : reference.pluginId();
    }

    private record RoutedRegistration(Object registry,
                                      String registrationName,
                                      Runnable registerAction,
                                      Runnable unregisterAction) {

        private RouteKey key() {
            return new RouteKey(registry, registrationName);
        }
    }

    /** Uses registry identity: two distinct registry instances must never share a route stack. */
    private static final class RouteKey {

        private final Object registry;
        private final String registrationName;

        private RouteKey(final Object registry, final String registrationName) {
            this.registry = registry;
            this.registrationName = registrationName;
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof RouteKey that
                   && registry == that.registry
                   && registrationName.equals(that.registrationName);
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(registry) + registrationName.hashCode();
        }
    }
}

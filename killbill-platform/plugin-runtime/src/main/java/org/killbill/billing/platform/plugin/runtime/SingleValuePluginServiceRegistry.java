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

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.killbill.billing.platform.plugin.api.PluginServiceDescriptor;
import org.killbill.billing.platform.plugin.api.SinglePluginServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the one implementation of a service type there can only be one of.
 * <p>
 * The metric registry is the case this exists for: whichever plugin supplies it, callers ask for
 * "the" registry rather than for a named one. A second registration replaces the first and says so,
 * because two plugins both providing one is a deployment mistake worth seeing in the log.
 *
 * @param <T> the service type
 */
public class SingleValuePluginServiceRegistry<T> implements SinglePluginServiceRegistry<T> {

    private static final Logger log = LoggerFactory.getLogger(SingleValuePluginServiceRegistry.class);

    private final Class<T> serviceType;
    private final AtomicReference<Registration<T>> current = new AtomicReference<>();
    private final List<Runnable> registrationListeners = new CopyOnWriteArrayList<>();

    public SingleValuePluginServiceRegistry(final Class<T> serviceType) {
        this.serviceType = Objects.requireNonNull(serviceType, "serviceType");
    }

    @Override
    public void registerService(final PluginServiceDescriptor descriptor, final T service) {
        final Registration<T> previous =
                current.getAndSet(new Registration<>(descriptor.getRegistrationName(), service));
        if (previous != null) {
            log.warn("Plugin '{}' replaced '{}' as the provider of {}; only one is used",
                     descriptor.getRegistrationName(), previous.registrationName(), serviceType.getSimpleName());
        } else {
            log.info("Plugin '{}' provides {}", descriptor.getRegistrationName(), serviceType.getSimpleName());
        }
        registrationListeners.forEach(this::runQuietly);
    }

    @Override
    public void addRegistrationListener(final Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        registrationListeners.add(listener);
        // Replay immediately if a plugin already registered: a listener added afterwards would
        // otherwise wait for a change that may never come.
        if (current.get() != null) {
            runQuietly(listener);
        }
    }

    private void runQuietly(final Runnable listener) {
        try {
            listener.run();
        } catch (final RuntimeException e) {
            log.warn("Registration listener for {} failed", serviceType.getSimpleName(), e);
        }
    }

    @Override
    public void unregisterService(final String registrationName) {
        // Compare before clearing: a plugin that lost the race above must not withdraw the winner
        // when it later stops.
        current.updateAndGet(existing ->
            existing != null && existing.registrationName().equals(registrationName) ? null : existing);
    }

    @Override
    public T getService() {
        final Registration<T> registration = current.get();
        return registration == null ? null : registration.service();
    }

    @Override
    public Class<T> getServiceType() {
        return serviceType;
    }

    private record Registration<T>(String registrationName, T service) { }
}

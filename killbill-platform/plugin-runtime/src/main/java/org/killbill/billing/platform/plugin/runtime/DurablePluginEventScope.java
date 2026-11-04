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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.killbill.billing.lpr.api.DurableEvent;
import org.killbill.billing.lpr.api.DurableEventBus;
import org.killbill.billing.lpr.api.DurableEventHandler;
import org.killbill.billing.lpr.api.ServiceRegistration;
import org.killbill.billing.lpr.api.ServiceRegistry;
import org.killbill.billing.lpr.api.Subscription;

/** One plugin/version capability; retained closed handles do not retain plugin callbacks/loaders. */
final class DurablePluginEventScope implements DurableEventBus {
    private final PersistentPluginEventBus bus;
    private final String pluginId;
    private final ServiceRegistry services;
    private final Map<String, ConsumerSubscription> subscriptions = new HashMap<>();
    private volatile boolean closed;

    DurablePluginEventScope(final PersistentPluginEventBus bus, final String pluginId, final ServiceRegistry services) {
        this.bus = bus;
        this.pluginId = pluginId;
        this.services = services;
    }

    @Override
    public void publish(final String targetPluginId, final String subscriptionId, final DurableEvent event) {
        requireOpen();
        Objects.requireNonNull(event, "event");
        bus.publish(new PersistentPluginEvent(1, pluginId, targetPluginId, subscriptionId,
                                              event.id(), event.topic(), event.payload()));
    }

    @Override
    public synchronized Subscription subscribe(final String subscriptionId, final DurableEventHandler handler) {
        requireOpen();
        PersistentPluginEvent.validateAddress(subscriptionId);
        Objects.requireNonNull(handler, "handler");
        final ConsumerSubscription existing = subscriptions.get(subscriptionId);
        if (existing != null && existing.isActive()) {
            throw new IllegalArgumentException("Duplicate durable subscription: " + subscriptionId);
        }
        final ConsumerSubscription subscription = new ConsumerSubscription(handler);
        subscription.registration = services.register(DurableEventHandler.class, subscription,
                                                       Map.of("subscriptionId", subscriptionId));
        subscriptions.put(subscriptionId, subscription);
        return subscription;
    }

    private void requireOpen() {
        if (closed) { throw new IllegalStateException("Durable event scope is closed: " + pluginId); }
    }

    @Override
    public synchronized void close() {
        closed = true;
        subscriptions.values().forEach(ConsumerSubscription::close);
        subscriptions.clear();
    }

    private static final class ConsumerSubscription implements DurableEventHandler, Subscription {
        private final AtomicReference<DurableEventHandler> handler;
        private ServiceRegistration registration;

        ConsumerSubscription(final DurableEventHandler handler) {
            this.handler = new AtomicReference<>(handler);
        }

        @Override
        public void handle(final DurableEvent event) {
            final DurableEventHandler callback = handler.get();
            if (callback == null) { throw new IllegalStateException("Durable subscription is closed"); }
            final Thread thread = Thread.currentThread();
            final ClassLoader previous = thread.getContextClassLoader();
            try {
                thread.setContextClassLoader(callback.getClass().getClassLoader());
                callback.handle(event);
            } finally {
                thread.setContextClassLoader(previous);
            }
        }

        @Override
        public Class<?> eventType() { return DurableEvent.class; }
        @Override
        public boolean isActive() { return handler.get() != null; }
        @Override
        public synchronized void close() {
            handler.set(null);
            if (registration != null) {
                registration.close();
                registration = null;
            }
        }
    }
}

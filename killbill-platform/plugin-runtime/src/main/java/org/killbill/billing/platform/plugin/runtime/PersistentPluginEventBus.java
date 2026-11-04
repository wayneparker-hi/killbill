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

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Semaphore;

import org.killbill.billing.lpr.api.DurableEventBus;
import org.killbill.billing.lpr.api.ServiceRegistry;
import org.killbill.billing.lpr.spi.DurableEventBusFactory;
import org.killbill.bus.api.PersistentBus;
import org.killbill.commons.eventbus.Subscribe;
import org.killbill.queue.QueueObjectMapper;

import com.google.inject.Inject;
import jakarta.inject.Named;

/** Kill Bill persistence adapter. Queue workers provide scheduling, retries and FAILED history. */
public final class PersistentPluginEventBus implements DurableEventBusFactory, AutoCloseable {
    private final PersistentBus bus;
    private final org.killbill.billing.lpr.core.DefaultServiceRegistry services;
    private final boolean enabled;
    private final int maxBytes;
    private final Semaphore publications;
    private final Semaphore deliveries;
    private volatile boolean active;
    private boolean registered;

    @Inject
    public PersistentPluginEventBus(@Named("externalBus") final PersistentBus bus,
                                    final org.killbill.billing.lpr.core.DefaultServiceRegistry services,
                                    final PluginRuntimeConfig config) {
        this.bus = Objects.requireNonNull(bus, "bus");
        this.services = Objects.requireNonNull(services, "services");
        this.enabled = config.isPluginDurableEventsEnabled();
        this.maxBytes = config.getPluginDurableEventMaxBytes();
        if (maxBytes < 1 || maxBytes > 2048 || config.getPluginDurableEventMaxPublications() < 1
            || config.getPluginDurableEventMaxDeliveries() < 1) {
            throw new IllegalArgumentException("Durable event limits must be positive, envelope budget at most 2048 bytes");
        }
        publications = new Semaphore(config.getPluginDurableEventMaxPublications());
        deliveries = new Semaphore(config.getPluginDurableEventMaxDeliveries());
    }

    public synchronized void start() throws PersistentBus.EventBusException {
        if (enabled && !active) {
            if (!registered) {
                bus.registerReliable(this);
                registered = true;
            }
            active = true;
        }
    }

    @Override
    public DurableEventBus open(final String pluginId, final ServiceRegistry scopedServices) {
        requireActive();
        PersistentPluginEvent.validateAddress(pluginId);
        return new DurablePluginEventScope(this, pluginId, scopedServices);
    }

    void publish(final PersistentPluginEvent event) {
        requireActive();
        if (!publications.tryAcquire()) {
            throw new IllegalStateException("Durable event publication capacity exhausted");
        }
        try {
            if (event.payload().length() > maxBytes
                || QueueObjectMapper.get().writeValueAsString(event).getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                throw new IllegalArgumentException("Durable event exceeds envelope byte budget: " + maxBytes);
            }
            bus.postReliable(event);
        } catch (final com.fasterxml.jackson.core.JsonProcessingException | PersistentBus.EventBusException e) {
            throw new IllegalStateException("Durable event was not acknowledged by storage", e);
        } finally {
            publications.release();
        }
    }

    @Subscribe
    @org.killbill.commons.eventbus.AllowConcurrentEvents
    public void onEvent(final PersistentPluginEvent event) {
        requireActive();
        if (event.schemaVersion() != 1) {
            throw new IllegalArgumentException("Unsupported plugin event schema: " + event.schemaVersion());
        }
        if (!deliveries.tryAcquire()) {
            throw new IllegalStateException("Durable event delivery capacity exhausted");
        }
        try {
            final org.killbill.billing.lpr.api.DurableEventHandler consumer = services.getServices(org.killbill.billing.lpr.api.DurableEventHandler.class).stream()
                    .filter(ref -> ref.pluginId().equals(event.targetPluginId()))
                    .filter(ref -> event.subscriptionId().equals(ref.properties().get("subscriptionId")))
                    .map(org.killbill.billing.lpr.api.ServiceReference::service).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No visible durable event consumer: "
                                                               + event.targetPluginId() + "/" + event.subscriptionId()));
            consumer.handle(event.message());
        } finally {
            deliveries.release();
        }
    }

    private void requireActive() {
        if (!active) { throw new IllegalStateException("Durable plugin events are disabled or unavailable"); }
    }

    @Override
    public synchronized void close() {
        // Keep this platform-only listener while STOP_BUS drains the queue. Removing it earlier
        // would acknowledge undelivered envelopes as events without subscribers.
        active = false;
    }
}

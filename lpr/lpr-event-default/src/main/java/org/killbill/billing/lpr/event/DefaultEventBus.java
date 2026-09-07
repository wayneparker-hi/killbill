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

package org.killbill.billing.lpr.event;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.killbill.billing.lpr.api.EventBus;
import org.killbill.billing.lpr.api.EventHandler;
import org.killbill.billing.lpr.api.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-process event dispatch.
 * <p>
 * Deliberately thin. The runtime needs subscribe, unsubscribe and publish within one JVM, and
 * nothing more -- a few hundred lines rather than a dependency on Guava's {@code EventBus}, whose
 * type would then have leaked into the plugin contract and become impossible to replace. Guava
 * remains available as an adapter if a workload ever justifies it.
 * <p>
 * Delivery is synchronous on the publishing thread. That keeps causality obvious and avoids
 * inventing a thread pool whose lifetime nobody owns; a handler that needs to do slow work should
 * hand it to its own managed executor.
 * <p>
 * A handler that throws is logged and skipped. One misbehaving plugin must not stop an event
 * reaching the others, and must not fail the core operation that published it.
 */
public class DefaultEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(DefaultEventBus.class);

    /**
     * Keyed by subscribed type. Publishing walks the keys and tests {@code isInstance}, rather than
     * caching a resolved type hierarchy: subscriptions come and go with plugins, and a stale cache
     * here would deliver events to a stopped plugin.
     */
    private final Map<Class<?>, CopyOnWriteArrayList<DefaultSubscription<?>>> subscriptionsByType =
            new ConcurrentHashMap<>();

    @Override
    public void publish(final Object event) {
        Objects.requireNonNull(event, "event");

        for (final Map.Entry<Class<?>, CopyOnWriteArrayList<DefaultSubscription<?>>> entry
                : subscriptionsByType.entrySet()) {
            if (!entry.getKey().isInstance(event)) {
                continue;
            }
            for (final DefaultSubscription<?> subscription : entry.getValue()) {
                subscription.deliver(event);
            }
        }
    }

    @Override
    public <T> Subscription subscribe(final Class<T> eventType, final EventHandler<T> handler) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(handler, "handler");

        final DefaultSubscription<T> subscription = new DefaultSubscription<>(eventType, handler, this);
        // The add happens inside compute, not after it. Doing `computeIfAbsent(...).add(...)`
        // would leave a window in which unsubscribe drops the bucket as empty and this add lands
        // in a list no longer reachable from the map, silently losing the subscription.
        subscriptionsByType.compute(eventType, (type, existing) -> {
            final CopyOnWriteArrayList<DefaultSubscription<?>> peers =
                    existing != null ? existing : new CopyOnWriteArrayList<>();
            peers.add(subscription);
            return peers;
        });
        return subscription;
    }

    /**
     * @param eventType subscribed type
     * @return live subscriptions for that exact type; for tests and diagnostics
     */
    public List<Subscription> subscriptionsFor(final Class<?> eventType) {
        final CopyOnWriteArrayList<DefaultSubscription<?>> found = subscriptionsByType.get(eventType);
        return found == null ? List.of() : List.copyOf(found);
    }

    void unsubscribe(final DefaultSubscription<?> subscription) {
        // Removal and the emptiness check must be one atomic map operation, for the mirror image
        // of the reason above: testing `peers.isEmpty()` and then calling `remove(key, peers)`
        // would compare the bucket against itself, so it would succeed even if a concurrent
        // subscribe had just refilled it, discarding a live subscription.
        subscriptionsByType.computeIfPresent(subscription.eventType(), (type, peers) -> {
            peers.remove(subscription);
            // Dropping empty buckets keeps publish() from walking types nobody listens to, and
            // leaves no trace of a stopped plugin's event types.
            return peers.isEmpty() ? null : peers;
        });
    }

    /**
     * One handler's registration.
     * <p>
     * Delivery runs with the handler's own ClassLoader as the thread context ClassLoader. Plugin
     * code routinely reaches for the TCCL -- {@code ServiceLoader}, JDBC drivers, XML factories,
     * most reflection-based libraries -- and without this it would see the publisher's, which is
     * usually the core's, and fail to find its own classes.
     */
    private static final class DefaultSubscription<T> implements Subscription {

        private final Class<T> eventType;
        private final EventHandler<T> handler;
        private final ClassLoader handlerClassLoader;
        private final DefaultEventBus bus;
        private volatile boolean active = true;

        private DefaultSubscription(final Class<T> eventType,
                                    final EventHandler<T> handler,
                                    final DefaultEventBus bus) {
            this.eventType = eventType;
            this.handler = handler;
            this.handlerClassLoader = handler.getClass().getClassLoader();
            this.bus = bus;
        }

        @SuppressWarnings("unchecked")
        private void deliver(final Object event) {
            if (!active) {
                return;
            }
            final Thread current = Thread.currentThread();
            final ClassLoader original = current.getContextClassLoader();
            try {
                current.setContextClassLoader(handlerClassLoader);
                handler.handle((T) event);
            } catch (final RuntimeException e) {
                log.warn("Event handler for {} failed", eventType.getName(), e);
            } finally {
                current.setContextClassLoader(original);
            }
        }

        @Override
        public Class<?> eventType() {
            return eventType;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public void close() {
            if (!active) {
                return;
            }
            active = false;
            bus.unsubscribe(this);
        }

        @Override
        public String toString() {
            return "Subscription{eventType=" + eventType.getName() + ", active=" + active + '}';
        }
    }
}

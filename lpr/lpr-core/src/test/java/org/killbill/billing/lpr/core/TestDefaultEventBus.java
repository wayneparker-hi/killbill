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
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.killbill.billing.lpr.api.Subscription;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TestDefaultEventBus {

    private record PaymentEvent(String id) { }

    private record RefundEvent(String id) { }

    @Test(groups = "fast")
    public void testSubscribersReceiveEventsOfTheirType() {
        final DefaultEventBus bus = new DefaultEventBus();
        final List<String> seen = new ArrayList<>();

        bus.subscribe(PaymentEvent.class, event -> seen.add(event.id()));

        bus.publish(new PaymentEvent("p1"));
        bus.publish(new RefundEvent("r1"));
        bus.publish(new PaymentEvent("p2"));

        assertEquals(seen, List.of("p1", "p2"));
    }

    /**
     * Subscribing to a supertype has to work, because that is how a plugin listens to "everything"
     * -- Kill Bill's own notification plugins subscribe to one broad event type, not to a list.
     */
    @Test(groups = "fast")
    public void testSubscribingToASupertypeReceivesSubtypes() {
        final DefaultEventBus bus = new DefaultEventBus();
        final List<Object> seen = new ArrayList<>();

        bus.subscribe(Object.class, seen::add);

        bus.publish(new PaymentEvent("p1"));
        bus.publish(new RefundEvent("r1"));

        assertEquals(seen.size(), 2);
    }

    @Test(groups = "fast")
    public void testClosingASubscriptionStopsDelivery() {
        final DefaultEventBus bus = new DefaultEventBus();
        final List<String> seen = new ArrayList<>();

        final Subscription subscription = bus.subscribe(PaymentEvent.class, event -> seen.add(event.id()));
        bus.publish(new PaymentEvent("before"));

        subscription.close();
        bus.publish(new PaymentEvent("after"));

        assertEquals(seen, List.of("before"));
        assertFalse(subscription.isActive());
        assertTrue(bus.subscriptionsFor(PaymentEvent.class).isEmpty());
    }

    /**
     * One plugin's bad handler must not stop an event reaching the others, and must not surface to
     * the core operation that published it.
     */
    @Test(groups = "fast")
    public void testAFailingHandlerDoesNotAffectOtherSubscribersOrThePublisher() {
        final DefaultEventBus bus = new DefaultEventBus();
        final List<String> seen = new ArrayList<>();

        bus.subscribe(PaymentEvent.class, event -> {
            throw new IllegalStateException("plugin blew up");
        });
        bus.subscribe(PaymentEvent.class, event -> seen.add(event.id()));

        bus.publish(new PaymentEvent("p1"));

        assertEquals(seen, List.of("p1"));
    }

    @Test(groups = "fast")
    public void testClosingTwiceIsANoOp() {
        final DefaultEventBus bus = new DefaultEventBus();
        final Subscription subscription = bus.subscribe(PaymentEvent.class, event -> { });

        subscription.close();
        subscription.close();

        assertFalse(subscription.isActive());
    }

    /**
     * Guards the subscribe/unsubscribe race that empty-bucket cleanup invites: if the add is not
     * inside the same atomic map operation as the emptiness check, a concurrent unsubscribe can
     * drop the bucket a fresh subscription just landed in, and that subscription silently never
     * receives anything again.
     */
    @Test(groups = "fast")
    public void testConcurrentSubscribeAndUnsubscribeNeverLosesASubscription() throws Exception {
        final DefaultEventBus bus = new DefaultEventBus();
        final int rounds = 500;
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < rounds; round++) {
                final Subscription churn = bus.subscribe(PaymentEvent.class, event -> { });
                final ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();
                final CountDownLatch start = new CountDownLatch(1);

                final var unsubscriber = pool.submit(() -> {
                    await(start);
                    churn.close();
                });
                final var subscriber = pool.submit(() -> {
                    await(start);
                    return bus.subscribe(PaymentEvent.class, event -> seen.add(event.id()));
                });

                start.countDown();
                unsubscriber.get(10, TimeUnit.SECONDS);
                final Subscription survivor = subscriber.get(10, TimeUnit.SECONDS);

                bus.publish(new PaymentEvent("round-" + round));
                assertEquals(seen.size(), 1, "Subscription lost in round " + round);
                survivor.close();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

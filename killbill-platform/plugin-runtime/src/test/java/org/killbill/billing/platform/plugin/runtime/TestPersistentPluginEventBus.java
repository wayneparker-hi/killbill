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
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.killbill.billing.platform.plugin.runtime;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.killbill.billing.lpr.api.DurableEvent;
import org.killbill.billing.lpr.api.DurableEventHandler;
import org.killbill.billing.lpr.core.DefaultServiceRegistry;
import org.killbill.bus.api.PersistentBus;
import org.killbill.queue.QueueObjectMapper;
import org.testng.annotations.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

public class TestPersistentPluginEventBus {
    static PluginRuntimeConfig enabled() {
        return new TestPluginRuntimeConfig() {
            @Override public boolean isPluginDurableEventsEnabled() { return true; }
            @Override public int getPluginDurableEventMaxPublications() { return 1; }
            @Override public int getPluginDurableEventMaxDeliveries() { return 1; }
        };
    }

    static PersistentPluginEvent envelope(final String target, final String subscription) {
        return new PersistentPluginEvent(1, "sender", target, subscription, UUID.randomUUID(), "test", "payload");
    }

    @Test(groups = "fast")
    public void testWireFormatRoundTripAndStrictPublication() throws Exception {
        final var bus = mock(PersistentBus.class);
        final var registry = new DefaultServiceRegistry();
        try (final var adapter = new PersistentPluginEventBus(bus, registry, enabled())) {
            adapter.start();
            final var scope = adapter.open("sender", registry.forPlugin("sender", "1.0.0"));
            final var message = new DurableEvent(UUID.randomUUID(), "invoice", "{\"value\":1}");
            scope.publish("consumer", "invoices", message);
            final var captured = org.mockito.ArgumentCaptor.forClass(PersistentPluginEvent.class);
            verify(bus).postReliable(captured.capture());
            assertEquals(captured.getValue().getSearchKey2(), Long.valueOf(0), "Core external queue requires a non-null search_key2");
            verify(bus, never()).post(any());
            final String json = QueueObjectMapper.get().writeValueAsString(captured.getValue());
            assertFalse(json.contains("@class"));
            assertEquals(QueueObjectMapper.get().readValue(json, PersistentPluginEvent.class).message(), message);
            doThrow(new PersistentBus.EventBusException("storage failed")).when(bus).postReliable(any());
            assertThrows(IllegalStateException.class, () -> scope.publish("consumer", "invoices", message));
            doNothing().when(bus).postReliable(any());
            scope.publish("consumer", "invoices", message); // permits released after failure
        }
        verify(bus, never()).unregister(any(PersistentPluginEventBus.class));
    }

    @Test(groups = "fast")
    public void testVersionSelectionRollbackAndOldCleanup() throws Exception {
        final var registry = new DefaultServiceRegistry();
        try (final var adapter = new PersistentPluginEventBus(mock(PersistentBus.class), registry, enabled())) {
            adapter.start();
            final var old = adapter.open("target", registry.forPlugin("target", "1.0.0"));
            final var next = adapter.open("target", registry.forPlugin("target", "2.0.0"));
            final var counts = List.of(new AtomicInteger(), new AtomicInteger());
            old.subscribe("jobs", message -> counts.get(0).incrementAndGet());
            registry.selectVersion("target", "1.0.0");
            registry.stageVersion("target", "2.0.0");
            next.subscribe("jobs", message -> counts.get(1).incrementAndGet());
            adapter.onEvent(envelope("target", "jobs"));
            assertEquals(counts.get(0).get(), 1);
            assertEquals(counts.get(1).get(), 0);
            registry.selectVersion("target", "2.0.0");
            registry.clearStagedVersion("target");
            adapter.onEvent(envelope("target", "jobs"));
            registry.selectVersion("target", "1.0.0");
            adapter.onEvent(envelope("target", "jobs"));
            assertEquals(counts.get(0).get(), 2);
            registry.selectVersion("target", "2.0.0");
            old.close();
            adapter.onEvent(envelope("target", "jobs"));
            assertEquals(counts.get(1).get(), 2);
            next.close();
            assertTrue(registry.getServices(DurableEventHandler.class).isEmpty());
            assertThrows(IllegalStateException.class, () -> adapter.onEvent(envelope("target", "jobs")));
        }
    }

    @Test(groups = "fast")
    public void testMissingUnhealthyAndFailedConsumersRequestRetry() throws Exception {
        final var registry = new DefaultServiceRegistry();
        try (final var adapter = new PersistentPluginEventBus(mock(PersistentBus.class), registry, enabled())) {
            adapter.start();
            final var scope = adapter.open("target", registry.forPlugin("target", "1.0.0"));
            final var other = adapter.open("other", registry.forPlugin("other", "1.0.0"));
            other.subscribe("jobs", ignored -> {});
            assertThrows(IllegalStateException.class, () -> adapter.onEvent(envelope("target", "jobs")));
            scope.subscribe("other", ignored -> fail("Wrong subscription"));
            assertThrows(IllegalStateException.class, () -> adapter.onEvent(envelope("target", "jobs")));
            scope.subscribe("jobs", ignored -> { throw new IllegalArgumentException("retry"); });
            assertThrows(IllegalArgumentException.class, () -> adapter.onEvent(envelope("target", "jobs")));
            registry.setHealthPredicate((id, version) -> false);
            assertThrows(IllegalStateException.class, () -> adapter.onEvent(envelope("target", "jobs")));
            scope.close();
        }
    }

    @Test(groups = "fast")
    public void testScopeExpiryDuplicateAndAddressValidation() throws Exception {
        final var registry = new DefaultServiceRegistry();
        try (final var adapter = new PersistentPluginEventBus(mock(PersistentBus.class), registry, enabled())) {
            adapter.start();
            final var scope = adapter.open("target", registry.forPlugin("target", "1.0.0"));
            final var sub = scope.subscribe("jobs", ignored -> {});
            assertThrows(IllegalArgumentException.class, () -> scope.subscribe("jobs", ignored -> {}));
            sub.close();
            sub.close();
            assertFalse(sub.isActive());
            scope.subscribe("jobs", ignored -> {});
            for (final String invalid : List.of("", "../target", "a b", "a".repeat(129))) {
                assertThrows(IllegalArgumentException.class, () -> scope.subscribe(invalid, ignored -> {}));
                assertThrows(IllegalArgumentException.class, () -> scope.publish(invalid, "jobs", envelope("target", "jobs").message()));
            }
            scope.close();
            assertThrows(IllegalStateException.class, () -> scope.subscribe("jobs", ignored -> {}));
            assertThrows(IllegalStateException.class, () -> scope.publish("target", "jobs", envelope("target", "jobs").message()));
        }
    }

    @Test(groups = "fast")
    public void testEnvelopeBudgetCountsEscapingAndUtf8() throws Exception {
        final var registry = new DefaultServiceRegistry();
        final var bus = mock(PersistentBus.class);
        try (final var adapter = new PersistentPluginEventBus(bus, registry, enabled())) {
            adapter.start();
            final var scope = adapter.open("sender", registry.forPlugin("sender", "1.0.0"));
            for (final String payload : List.of("a".repeat(2048), "中".repeat(700), "\n".repeat(1000))) {
                assertThrows(IllegalArgumentException.class, () -> scope.publish("target", "jobs", new DurableEvent(UUID.randomUUID(), "test", payload)));
            }
            verify(bus, never()).postReliable(any());
        }
    }

    @Test(groups = "fast")
    public void testPublicationBackpressureAndPermitRelease() throws Exception {
        final var bus = mock(PersistentBus.class);
        final var registry = new DefaultServiceRegistry();
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return null; }).when(bus).postReliable(any());
        try (final var adapter = new PersistentPluginEventBus(bus, registry, enabled()); final var executor = Executors.newSingleThreadExecutor()) {
            adapter.start();
            final var scope = adapter.open("sender", registry.forPlugin("sender", "1.0.0"));
            final var event = envelope("target", "jobs").message();
            final var task = executor.submit(() -> scope.publish("target", "jobs", event));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> scope.publish("target", "jobs", event));
            } finally { release.countDown(); }
            task.get(5, TimeUnit.SECONDS);
            scope.publish("target", "jobs", event);
        }
    }

    @Test(groups = "fast")
    public void testDeliveryBackpressureAndTcclRestoration() throws Exception {
        final var registry = new DefaultServiceRegistry();
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        try (final var adapter = new PersistentPluginEventBus(mock(PersistentBus.class), registry, enabled()); final var executor = Executors.newSingleThreadExecutor()) {
            adapter.start();
            final var delegate = new org.killbill.commons.eventbus.EventBus();
            delegate.register(adapter);
            final var scope = adapter.open("target", registry.forPlugin("target", "1.0.0"));
            scope.subscribe("jobs", ignored -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (final InterruptedException e) { throw new IllegalStateException(e); }
            });
            final var task = executor.submit(() -> { delegate.postWithException(envelope("target", "jobs")); return null; });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(org.killbill.commons.eventbus.EventBusException.class, () -> delegate.postWithException(envelope("target", "jobs")));
            } finally { release.countDown(); }
            task.get(5, TimeUnit.SECONDS);
            final var previous = Thread.currentThread().getContextClassLoader();
            adapter.onEvent(envelope("target", "jobs"));
            assertSame(Thread.currentThread().getContextClassLoader(), previous);
            scope.close();
        }
    }

    @Test(groups = "fast")
    public void testUnsupportedSchemaAndClosedAdapterAreNotAcknowledged() throws Exception {
        final var registry = new DefaultServiceRegistry();
        try (final var adapter = new PersistentPluginEventBus(mock(PersistentBus.class), registry, enabled())) {
            adapter.start();
            assertThrows(IllegalArgumentException.class, () -> adapter.onEvent(new PersistentPluginEvent(2, "sender", "target", "jobs",
                    UUID.randomUUID(), "test", "payload")));
            adapter.close();
            assertThrows(IllegalStateException.class, () -> adapter.onEvent(envelope("target", "jobs")));
        }
    }

    @Test(groups = "fast")
    public void testDisabledStartupAndInvalidConfiguration() throws Exception {
        final var registry = new DefaultServiceRegistry();
        final var bus = mock(PersistentBus.class);
        try (final var adapter = new PersistentPluginEventBus(bus, registry, new TestPluginRuntimeConfig())) {
            adapter.start();
            assertThrows(IllegalStateException.class, () -> adapter.open("target", registry.forPlugin("target", "1.0.0")));
            verifyNoInteractions(bus);
        }
        assertThrows(IllegalArgumentException.class, () -> new PersistentPluginEventBus(bus, registry, new TestPluginRuntimeConfig() {
            @Override public int getPluginDurableEventMaxBytes() { return 2049; }
        }));
        doThrow(new PersistentBus.EventBusException("unsupported")).when(bus).registerReliable(any());
        try (final var adapter = new PersistentPluginEventBus(bus, registry, enabled())) {
            assertThrows(PersistentBus.EventBusException.class, adapter::start);
            assertThrows(IllegalStateException.class, () -> adapter.open("target", registry.forPlugin("target", "1.0.0")));
        }
    }
}

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.killbill.billing.lpr.api.*;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class TestServiceVersionNegotiation {
    public interface Gateway { String call(); }
    private static final ServiceApiVersion ONE_TWO = ServiceApiVersion.parse("1.2.0");
    private static final ServiceApiVersion TWO = ServiceApiVersion.parse("2.0.0");

    @Test(groups = "fast")
    public void testLegacyMetadataAndDefensiveCopy() {
        final var store = new DefaultServiceRegistry();
        store.forPlugin("legacy", "99.0.0").register(Gateway.class, () -> "legacy");
        final var legacy = store.getServices(Gateway.class).getFirst();
        assertEquals(legacy.apiVersion(), ServiceApiVersion.LEGACY);
        assertEquals(legacy.properties().get(ServiceApiVersion.PROPERTY), "1.0.0");
        final Map<String, Object> props = new HashMap<>(); props.put(ServiceApiVersion.PROPERTY, "1.2.0");
        store.forPlugin("current", "1.0.0").register(Gateway.class, () -> "current", props);
        props.put(ServiceApiVersion.PROPERTY, "2.0.0");
        assertEquals(store.publishedServices("current").getFirst().apiVersion(), ONE_TWO);
        assertThrows(UnsupportedOperationException.class, () -> legacy.properties().put(ServiceApiVersion.PROPERTY, "3.0.0"));
    }

    @Test(groups = "fast")
    public void testInvalidDeclarationsHaveNoRegistrationOrListenerSideEffects() {
        final var metrics = new TestLprMetricsCollector.RecordingMetrics();
        final var store = new DefaultServiceRegistry(new LprMetricsCollector(metrics));
        final var notifications = new AtomicInteger();
        store.addListener(new ServiceRegistryListener() {
            @Override public void onRegistered(final ServiceReference<?> reference) { notifications.incrementAndGet(); }
            @Override public void onUnregistered(final ServiceReference<?> reference) { notifications.incrementAndGet(); }
        });
        for (final Object value : List.of(1, ONE_TWO, "bad", "1.2.0-RC1")) {
            assertThrows(IllegalArgumentException.class, () -> store.forPlugin("bad", "1.0.0")
                    .register(Gateway.class, () -> "bad", Map.of(ServiceApiVersion.PROPERTY, value)));
        }
        assertThrows(IllegalArgumentException.class, () -> store.forPlugin("bad", "1.0.0")
                .register(Gateway.class, () -> "bad", ONE_TWO, Map.of(ServiceApiVersion.PROPERTY, "2.0.0")));
        assertTrue(store.publishedServices("bad").isEmpty());
        assertEquals(notifications.get(), 0);
        store.forPlugin("valid", "1.0.0").register(Gateway.class, () -> "valid");
        assertEquals(metrics.gaugeValue("killbill.lpr.registry.providers." + Gateway.class.getName().replace('.', '_')), 1L);
    }

    @Test(groups = "fast")
    public void testHostRequirementsAndExplicitConsumerLookup() {
        final Map<String, ServiceApiVersion> required = new HashMap<>(); required.put(Gateway.class.getName(), ONE_TWO);
        final var store = new DefaultServiceRegistry(null, required); required.clear();
        store.forPlugin("old", "9.0.0").register(Gateway.class, () -> "old");
        store.forPlugin("new", "1.0.0").register(Gateway.class, () -> "new", TWO, Map.of("priority", 100));
        store.forPlugin("compatible", "1.0.0").register(Gateway.class, () -> "ok", ONE_TWO, Map.of());
        assertEquals(store.getPluginIds(Gateway.class), List.of("compatible"));
        assertTrue(store.getService(Gateway.class, "new").isEmpty());
        assertTrue(store.getService(Gateway.class, "old").isEmpty());
        assertEquals(store.getService(Gateway.class, "compatible").orElseThrow().call(), "ok");
        assertEquals(store.getService(Gateway.class, "new", TWO).orElseThrow().call(), "new");
        assertEquals(store.getServices(Gateway.class, TWO).getFirst().pluginId(), "new");
        assertEquals(store.publishedServices("new").size(), 1);
    }

    @Test(groups = "fast")
    public void testScopedConsumerLookupAndCloseCannotBypassVersionOrLifetime() {
        final var store = new DefaultServiceRegistry();
        final var provider = new PluginServiceScope(store, "provider", "1.0.0");
        provider.register(Gateway.class, () -> "two", TWO, Map.of());
        final var consumer = new PluginServiceScope(store, "consumer", "1.0.0");
        assertTrue(consumer.getService(Gateway.class, "provider").isEmpty());
        assertEquals(consumer.getService(Gateway.class, "provider", TWO).orElseThrow().call(), "two");
        assertEquals(consumer.getServices(Gateway.class, TWO).size(), 1);
        provider.close();
        assertTrue(consumer.getService(Gateway.class, "provider", TWO).isEmpty());
        assertThrows(IllegalStateException.class, () -> provider.register(Gateway.class, () -> "late", TWO, Map.of()));
        assertTrue(store.publishedServices("provider").isEmpty());
    }

    @Test(groups = "fast")
    public void testVersionFilteringRetainsPriorityHealthAndStaging() {
        final var store = new DefaultServiceRegistry();
        for (final String id : List.of("b", "a", "low", "wrong", "staged", "sick")) {
            store.forPlugin(id, "1.0.0").register(Gateway.class, () -> id, id.equals("wrong") ? TWO : ONE_TWO,
                    Map.of("priority", id.equals("low") ? 0 : 10));
        }
        store.stageVersion("staged", "1.0.0");
        store.setHealthPredicate((id, version) -> !id.equals("sick"));
        assertEquals(store.getServices(Gateway.class, ONE_TWO).stream().map(ServiceReference::pluginId).toList(), List.of("a", "b", "low"));
        assertTrue(store.getService(Gateway.class, "sick", ONE_TWO).isEmpty());
        assertTrue(store.getService(Gateway.class, "staged", ONE_TWO).isEmpty());
    }

    @Test(groups = "fast")
    public void testSelectionAndVersionScopedWithdrawalRemainIndependentOfApiVersion() {
        final var store = new DefaultServiceRegistry();
        store.forPlugin("p", "1.0.0").register(Gateway.class, () -> "one", ONE_TWO, Map.of());
        store.forPlugin("p", "2.0.0").register(Gateway.class, () -> "two", TWO, Map.of());
        store.selectVersion("p", "2.0.0");
        assertTrue(store.getService(Gateway.class, "p", ONE_TWO).isEmpty());
        assertEquals(store.getService(Gateway.class, "p", TWO).orElseThrow().call(), "two");
        store.unregisterAll("p", "1.0.0");
        assertEquals(store.getService(Gateway.class, "p", TWO).orElseThrow().call(), "two");
    }
}

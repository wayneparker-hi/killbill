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
import java.util.function.BiPredicate;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TestDefaultServiceRegistryHealthFiltering {

    private interface PaymentGateway {
        String name();
    }

    private record NamedGateway(String name) implements PaymentGateway {
    }

    /**
     * Before a health predicate is wired, every registered service is visible.
     */
    @Test(groups = "fast")
    public void testDefaultPredicateTreatsAllPluginsAsHealthy() {
        final DefaultServiceRegistry registry = new DefaultServiceRegistry();
        registry.register("stripe", "1.0.0", PaymentGateway.class, new NamedGateway("stripe"), Map.of());

        assertTrue(registry.getService(PaymentGateway.class, "stripe").isPresent());
        assertEquals(registry.getPluginIds(PaymentGateway.class), List.of("stripe"));
    }

    /**
     * When the predicate reports a plugin version unhealthy, its services disappear from selectors.
     */
    @Test(groups = "fast")
    public void testUnhealthyPluginIsHiddenFromSelectors() {
        final DefaultServiceRegistry registry = new DefaultServiceRegistry();
        registry.register("stripe", "1.0.0", PaymentGateway.class, new NamedGateway("stripe"), Map.of());
        registry.register("adyen", "2.0.0", PaymentGateway.class, new NamedGateway("adyen"), Map.of());

        registry.setHealthPredicate((pluginId, version) -> !"stripe".equals(pluginId));

        assertTrue(registry.getService(PaymentGateway.class, "stripe").isEmpty());
        assertTrue(registry.getService(PaymentGateway.class, "adyen").isPresent());
        assertEquals(registry.getPluginIds(PaymentGateway.class), List.of("adyen"));
    }

    /**
     * Health is scoped by version so an unhealthy old version does not hide a healthy new one
     * during an upgrade.
     */
    @Test(groups = "fast")
    public void testHealthPredicateIsScopedByVersion() {
        final DefaultServiceRegistry registry = new DefaultServiceRegistry();
        registry.register("stripe", "1.0.0", PaymentGateway.class, new NamedGateway("old"), Map.of());
        registry.register("stripe", "2.0.0", PaymentGateway.class, new NamedGateway("new"), Map.of());

        registry.setHealthPredicate((pluginId, version) -> !"1.0.0".equals(version));

        assertEquals(registry.getServices(PaymentGateway.class).size(), 1);
        assertEquals(registry.getServices(PaymentGateway.class).get(0).version(), "2.0.0");
    }

    /**
     * A throwing predicate must not break lookups; the offending plugin version is simply hidden.
     */
    @Test(groups = "fast")
    public void testThrowingPredicateHidesThePlugin() {
        final DefaultServiceRegistry registry = new DefaultServiceRegistry();
        registry.register("stripe", "1.0.0", PaymentGateway.class, new NamedGateway("stripe"), Map.of());

        registry.setHealthPredicate((pluginId, version) -> {
            throw new RuntimeException("boom");
        });

        assertTrue(registry.getService(PaymentGateway.class, "stripe").isEmpty());
        assertTrue(registry.getServices(PaymentGateway.class).isEmpty());
    }

    /**
     * Clearing the predicate restores full visibility.
     */
    @Test(groups = "fast")
    public void testNullPredicateResetsToHealthy() {
        final DefaultServiceRegistry registry = new DefaultServiceRegistry();
        registry.register("stripe", "1.0.0", PaymentGateway.class, new NamedGateway("stripe"), Map.of());

        registry.setHealthPredicate((pluginId, version) -> false);
        assertTrue(registry.getService(PaymentGateway.class, "stripe").isEmpty());

        registry.setHealthPredicate(null);
        assertTrue(registry.getService(PaymentGateway.class, "stripe").isPresent());
    }

    /**
     * The predicate must not retain references that prevent garbage collection of plugin
     * ClassLoaders. A plain BiPredicate on strings satisfies that.
     */
    @Test(groups = "fast")
    public void testPredicateReceivesOnlyStrings() {
        final DefaultServiceRegistry registry = new DefaultServiceRegistry();
        registry.register("stripe", "1.0.0", PaymentGateway.class, new NamedGateway("stripe"), Map.of());

        final BiPredicate<String, String> recording = (pluginId, version) -> {
            assertEquals(pluginId, "stripe");
            assertEquals(version, "1.0.0");
            return true;
        };
        registry.setHealthPredicate(recording);

        registry.getServices(PaymentGateway.class);
    }
}

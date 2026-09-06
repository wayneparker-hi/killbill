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

import org.killbill.billing.lpr.api.ServiceRegistration;
import org.killbill.billing.lpr.api.ServiceRegistry;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

public class TestDefaultServiceRegistry {

    /** Stands in for the plugin SPIs Kill Bill's core calls (payment, invoice, catalog, ...). */
    private interface PaymentGateway {
        String charge();
    }

    private record FixedGateway(String name) implements PaymentGateway {
        @Override
        public String charge() {
            return name;
        }
    }

    /**
     * The lookup Kill Bill's core actually performs: configuration names a plugin, and the call
     * has to reach that plugin's implementation rather than whichever one registered first.
     */
    @Test(groups = "fast")
    public void testServicesAreLookedUpByPublishingPlugin() {
        final DefaultServiceRegistry registry = new DefaultServiceRegistry();
        registry.register("stripe", "1.0.0", PaymentGateway.class, new FixedGateway("stripe"), Map.of());
        registry.register("adyen", "2.0.0", PaymentGateway.class, new FixedGateway("adyen"), Map.of());

        assertEquals(registry.getService(PaymentGateway.class, "stripe").orElseThrow().charge(), "stripe");
        assertEquals(registry.getService(PaymentGateway.class, "adyen").orElseThrow().charge(), "adyen");
        assertTrue(registry.getService(PaymentGateway.class, "paypal").isEmpty());
    }

    /**
     * Core code such as the invoice dispatcher calls every registered plugin in turn, so the order
     * has to be defined and reproducible rather than whatever the map happens to yield.
     */
    @Test(groups = "fast")
    public void testServicesAreOrderedByPriorityThenPluginId() {
        final DefaultServiceRegistry registry = new DefaultServiceRegistry();
        registry.register("zeta", "1.0.0", PaymentGateway.class, new FixedGateway("zeta"), Map.of());
        registry.register("alpha", "1.0.0", PaymentGateway.class, new FixedGateway("alpha"), Map.of());
        registry.register("high", "1.0.0", PaymentGateway.class, new FixedGateway("high"),
                          Map.of(DefaultServiceRegistry.PRIORITY_PROPERTY, 100));

        assertEquals(registry.getPluginIds(PaymentGateway.class), List.of("high", "alpha", "zeta"));
    }

    /**
     * The invariant that keeps a stopped plugin from being called: once its registrations are
     * withdrawn, no lookup can reach an implementation whose ClassLoader is about to close.
     */
    @Test(groups = "fast")
    public void testStoppingAPluginMakesItsServicesUnreachable() {
        final DefaultServiceRegistry registry = new DefaultServiceRegistry();
        registry.register("stripe", "1.0.0", PaymentGateway.class, new FixedGateway("stripe"), Map.of());
        registry.register("adyen", "1.0.0", PaymentGateway.class, new FixedGateway("adyen"), Map.of());

        assertEquals(registry.unregisterAll("stripe"), 1);

        assertTrue(registry.getService(PaymentGateway.class, "stripe").isEmpty());
        assertEquals(registry.getPluginIds(PaymentGateway.class), List.of("adyen"));
    }

    /**
     * A service that is not an instance of the interface it is registered under almost always
     * means the two sides resolved that interface through different ClassLoaders. Rejecting it at
     * registration turns a mystifying ClassCastException at the first business call into an
     * immediate, attributable startup failure.
     */
    @Test(groups = "fast")
    public void testRegisteringAServiceOfTheWrongTypeIsRejected() {
        final DefaultServiceRegistry registry = new DefaultServiceRegistry();

        @SuppressWarnings("unchecked")
        final Class<PaymentGateway> lyingType = (Class<PaymentGateway>) (Class<?>) Runnable.class;

        assertThrows(IllegalArgumentException.class,
                     () -> registry.register("liar", "1.0.0", lyingType, new FixedGateway("x"), Map.of()));
    }

    /** A plugin must not be able to publish under another plugin's name. */
    @Test(groups = "fast")
    public void testPluginScopedViewStampsThePublishersIdentity() {
        final DefaultServiceRegistry registry = new DefaultServiceRegistry();
        final ServiceRegistry stripeView = registry.forPlugin("stripe", "1.2.0");

        stripeView.register(PaymentGateway.class, new FixedGateway("stripe"));

        assertEquals(registry.getServices(PaymentGateway.class).size(), 1);
        assertEquals(registry.getServices(PaymentGateway.class).get(0).pluginId(), "stripe");
        assertEquals(registry.getServices(PaymentGateway.class).get(0).version(), "1.2.0");
    }

    /**
     * Retracting one service while staying active, for a plugin that fails its own health check
     * and wants to stop receiving traffic without shutting down.
     */
    @Test(groups = "fast")
    public void testClosingOneRegistrationLeavesTheRestOfThePluginIntact() {
        final DefaultServiceRegistry registry = new DefaultServiceRegistry();
        final ServiceRegistry view = registry.forPlugin("stripe", "1.0.0");

        final ServiceRegistration gateway = view.register(PaymentGateway.class, new FixedGateway("stripe"));
        view.register(Runnable.class, () -> { });

        gateway.close();

        assertFalse(gateway.isActive());
        assertTrue(registry.getService(PaymentGateway.class, "stripe").isEmpty());
        assertTrue(registry.getService(Runnable.class, "stripe").isPresent());
    }

    /**
     * Two plugins can register services that look identical. Withdrawing one must not withdraw the
     * other, which is why references are compared by identity rather than by value.
     */
    @Test(groups = "fast")
    public void testEqualLookingServicesFromDifferentPluginsAreTrackedSeparately() {
        final DefaultServiceRegistry registry = new DefaultServiceRegistry();
        final FixedGateway shared = new FixedGateway("same");

        final ServiceRegistration first = registry.register("a", "1.0.0", PaymentGateway.class, shared, Map.of());
        registry.register("b", "1.0.0", PaymentGateway.class, shared, Map.of());

        first.close();

        assertEquals(registry.getPluginIds(PaymentGateway.class), List.of("b"));
        assertSame(registry.getService(PaymentGateway.class, "b").orElseThrow(), shared);
    }

    @Test(groups = "fast")
    public void testPropertiesAreCopiedSoLaterMutationCannotReorderLookups() {
        final DefaultServiceRegistry registry = new DefaultServiceRegistry();
        final java.util.Map<String, Object> mutable = new java.util.HashMap<>();
        mutable.put(DefaultServiceRegistry.PRIORITY_PROPERTY, 1);

        registry.register("a", "1.0.0", PaymentGateway.class, new FixedGateway("a"), mutable);
        mutable.put(DefaultServiceRegistry.PRIORITY_PROPERTY, 999);
        registry.register("b", "1.0.0", PaymentGateway.class, new FixedGateway("b"), Map.of());

        assertEquals(registry.getServices(PaymentGateway.class).get(0).properties()
                             .get(DefaultServiceRegistry.PRIORITY_PROPERTY), 1);
        assertEquals(registry.getPluginIds(PaymentGateway.class), List.of("a", "b"));
    }
}

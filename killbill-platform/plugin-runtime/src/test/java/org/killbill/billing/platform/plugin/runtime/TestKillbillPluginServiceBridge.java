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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.killbill.billing.lpr.core.DefaultServiceRegistry;
import org.killbill.billing.platform.plugin.api.PluginServiceDescriptor;
import org.killbill.billing.platform.plugin.api.PluginServiceProperties;
import org.killbill.billing.platform.plugin.api.PluginServiceRegistry;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * The seam between the plugin runtime and Kill Bill's business modules.
 * <p>
 * The runtime keeps one registry keyed by service type; the business modules want one registry per
 * type, injected where that type is called from. Everything that can go wrong at this seam is
 * silent: a service registered under the wrong name is simply never found, and a lookup that
 * returns nothing looks the same as a plugin that was never installed.
 */
public class TestKillbillPluginServiceBridge {

    /** Stands in for PaymentPluginApi and the other plugin SPIs. */
    public interface Gateway {
        String charge();
    }

    /** A second type, to check that registrations are routed rather than broadcast. */
    public interface TaxCalculator {
        String calculate();
    }

    private DefaultServiceRegistry runtimeRegistry;
    private RecordingRegistry<Gateway> gatewayRegistry;
    private RecordingRegistry<TaxCalculator> taxRegistry;

    @BeforeMethod(groups = "fast")
    public void beforeMethod() {
        runtimeRegistry = new DefaultServiceRegistry();
        gatewayRegistry = new RecordingRegistry<>(Gateway.class);
        taxRegistry = new RecordingRegistry<>(TaxCalculator.class);
        runtimeRegistry.addListener(new KillbillPluginServiceBridge(List.of(gatewayRegistry, taxRegistry)));
    }

    @Test(groups = "fast")
    public void testAPluginRegistrationReachesTheRegistryForItsType() {
        runtimeRegistry.register("acme", "1.0.0", Gateway.class, () -> "charged", Map.of());

        assertEquals(gatewayRegistry.getAllServices(), Set.of("acme"));
        assertEquals(gatewayRegistry.getServiceForName("acme").charge(), "charged");
        assertTrue(taxRegistry.getAllServices().isEmpty(), "Registration was broadcast rather than routed");
    }

    /**
     * The default that removes a line of boilerplate from every plugin. Under OSGi each plugin had
     * to set {@code killbill.pluginName} explicitly, and registration silently did nothing if it
     * forgot -- a failure that looked exactly like the plugin not being installed.
     */
    @Test(groups = "fast")
    public void testTheRegistrationNameDefaultsToThePluginId() {
        runtimeRegistry.register("acme", "1.0.0", Gateway.class, () -> "charged", Map.of());

        assertEquals(gatewayRegistry.lastDescriptor.getRegistrationName(), "acme");
        assertEquals(gatewayRegistry.lastDescriptor.getPluginId(), "acme");
        assertEquals(gatewayRegistry.lastDescriptor.getPluginVersion(), "1.0.0");
    }

    /**
     * A plugin can still choose its lookup name, because the name and the identity are not always
     * the same thing: a payment method row stores the plugin name it was created with, and that
     * name has to keep resolving after a rename.
     */
    @Test(groups = "fast")
    public void testAPluginCanPublishUnderADifferentName() {
        runtimeRegistry.register("acme-payment-plugin", "1.0.0", Gateway.class, () -> "charged",
                                 Map.of(PluginServiceProperties.REGISTRATION_NAME, "acme"));

        assertEquals(gatewayRegistry.getAllServices(), Set.of("acme"));
    }

    /**
     * Names reach URLs, configuration and database columns. Rejecting a bad one loudly beats
     * letting it through to fail later somewhere unrelated.
     */
    @Test(groups = "fast")
    public void testAnInvalidRegistrationNameIsRefused() {
        runtimeRegistry.register("acme", "1.0.0", Gateway.class, () -> "charged",
                                 Map.of(PluginServiceProperties.REGISTRATION_NAME, "Acme Payments!"));

        assertTrue(gatewayRegistry.getAllServices().isEmpty());
    }

    @Test(groups = "fast")
    public void testStoppingAPluginWithdrawsItFromTheTypedRegistry() {
        runtimeRegistry.register("acme", "1.0.0", Gateway.class, () -> "charged", Map.of());
        assertEquals(gatewayRegistry.getAllServices(), Set.of("acme"));

        runtimeRegistry.unregisterAll("acme", "1.0.0");

        assertTrue(gatewayRegistry.getAllServices().isEmpty());
        assertNull(gatewayRegistry.getServiceForName("acme"));
    }

    /**
     * A deployment need not consume every service type a plugin offers. Publishing one nobody wants
     * is not an error -- an analytics plugin registering a metrics hook on a deployment without one
     * should just start normally.
     */
    @Test(groups = "fast")
    public void testAServiceTypeThisDeploymentDoesNotConsumeIsIgnored() {
        runtimeRegistry.register("acme", "1.0.0", Runnable.class, () -> { }, Map.of());

        assertTrue(gatewayRegistry.getAllServices().isEmpty());
        assertTrue(taxRegistry.getAllServices().isEmpty());
    }

    /**
     * Matched by assignability, not exact type, so a plugin registering a subtype still lands in
     * the right registry. This is how an {@code HttpServlet} reaches the {@code Servlet} registry.
     */
    @Test(groups = "fast")
    public void testASubtypeIsRoutedToItsSupertypesRegistry() {
        runtimeRegistry.register("acme", "1.0.0", PremiumGateway.class, () -> "premium", Map.of());

        assertEquals(gatewayRegistry.getAllServices(), Set.of("acme"),
                     "A subtype of the registered service type did not reach its registry");
    }

    public interface PremiumGateway extends Gateway {
    }

    /** Captures what the bridge hands over, so tests can assert on the descriptor too. */
    private static final class RecordingRegistry<T> implements PluginServiceRegistry<T> {

        private final Class<T> serviceType;
        private final Map<String, T> services = new ConcurrentHashMap<>();
        private PluginServiceDescriptor lastDescriptor;

        private RecordingRegistry(final Class<T> serviceType) {
            this.serviceType = serviceType;
        }

        @Override
        public void registerService(final PluginServiceDescriptor descriptor, final T service) {
            lastDescriptor = descriptor;
            services.put(descriptor.getRegistrationName(), service);
        }

        @Override
        public void unregisterService(final String registrationName) {
            services.remove(registrationName);
        }

        @Override
        public T getServiceForName(final String registrationName) {
            return services.get(registrationName);
        }

        @Override
        public Set<String> getAllServices() {
            return services.keySet();
        }

        @Override
        public Class<T> getServiceType() {
            return serviceType;
        }
    }
}

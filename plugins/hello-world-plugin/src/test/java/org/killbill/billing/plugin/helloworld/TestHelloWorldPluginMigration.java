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

package org.killbill.billing.plugin.helloworld;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.lpr.api.PluginState;
import org.killbill.billing.lpr.classloader.DefaultPluginClassLoaderFactory;
import org.killbill.billing.lpr.core.DefaultEventBus;
import org.killbill.billing.lpr.core.DefaultPluginLifecycleManager;
import org.killbill.billing.lpr.core.DefaultPluginManager;
import org.killbill.billing.lpr.core.DefaultServiceRegistry;
import org.killbill.billing.lpr.core.PluginInfo;
import org.killbill.billing.lpr.core.PluginRepository;
import org.killbill.billing.lpr.descriptor.yaml.YamlDescriptorParser;
import org.killbill.billing.lpr.spi.ClassLoaderPolicy;
import org.killbill.billing.lpr.testkit.PluginJarBuilder;
import org.killbill.billing.payment.plugin.api.PaymentPluginApi;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;
import org.killbill.billing.payment.plugin.api.PaymentTransactionInfoPlugin;
import org.killbill.billing.payment.provider.DefaultPaymentProviderPluginRegistry;
import org.killbill.billing.platform.plugin.api.PluginServiceRegistry;
import org.killbill.billing.platform.plugin.runtime.KillbillPluginServiceBridge;
import org.killbill.billing.util.config.definition.PaymentConfig;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/**
 * The migration, end to end.
 * <p>
 * This is the test the whole exercise was aimed at. A plugin that used to be an OSGi bundle is
 * packaged as an ordinary jar with a {@code plugin.yaml}, laid out on disk, discovered by the
 * runtime, loaded through an isolating ClassLoader, started, and routed into the very same
 * {@code DefaultPaymentProviderPluginRegistry} that Kill Bill's payment core reads on every
 * payment. A payment call then goes through it and a real result comes back.
 * <p>
 * Nothing here is stubbed except {@code PaymentConfig}, which the registry only reads a default
 * plugin name from.
 * <p>
 * The plugin is deliberately loaded <b>from a jar</b> rather than from the test classpath. On the
 * classpath its classes would be loaded by the application ClassLoader and everything would work
 * trivially; in a jar they go through the plugin ClassLoader, which is what production does and
 * what can actually break.
 */
public class TestHelloWorldPluginMigration {

    private static final String PLUGIN_ID = "hello-world";
    private static final String PLUGIN_VERSION = "1.0.0";

    private Path root;
    private DefaultServiceRegistry runtimeRegistry;
    private DefaultPaymentProviderPluginRegistry paymentRegistry;
    private DefaultPluginManager pluginManager;

    @BeforeClass(groups = "fast")
    public void beforeClass() throws Exception {
        root = Files.createTempDirectory("hello-world-migration-");
        installPluginAsItWouldBeDeployed();

        // The Kill Bill side: the real registry the payment core injects.
        paymentRegistry = new DefaultPaymentProviderPluginRegistry(stubPaymentConfig());

        // The runtime side, assembled as PluginRuntimeModule assembles it.
        runtimeRegistry = new DefaultServiceRegistry();
        runtimeRegistry.addListener(new KillbillPluginServiceBridge(List.<PluginServiceRegistry<?>>of(paymentRegistry)));

        final PluginRepository repository = new PluginRepository(root.resolve("plugins"), new YamlDescriptorParser());
        final DefaultPluginLifecycleManager lifecycle = new DefaultPluginLifecycleManager(
                new DefaultPluginClassLoaderFactory(),
                ClassLoaderPolicy.defaultPolicy(),
                getClass().getClassLoader(),
                runtimeRegistry,
                new DefaultEventBus(),
                java.util.Map.of());
        pluginManager = new DefaultPluginManager(repository, lifecycle, runtimeRegistry);
    }

    @AfterClass(groups = "fast", alwaysRun = true)
    public void afterClass() {
        if (pluginManager != null) {
            pluginManager.stopAll();
        }
    }

    /**
     * Discovery and startup, then the assertion that matters: Kill Bill's payment registry can find
     * the plugin under the name a payment method row would refer to.
     */
    @Test(groups = "fast")
    public void testThePluginIsDiscoveredStartedAndVisibleToThePaymentCore() {
        assertEquals(pluginManager.discover(), 1, "The plugin was not discovered on disk");
        assertEquals(pluginManager.startAll(List.of(PLUGIN_ID)), List.of(PLUGIN_ID));

        final PluginInfo info = pluginManager.get(PLUGIN_ID).orElseThrow();
        assertEquals(info.state(), PluginState.ACTIVE);
        assertEquals(info.version(), PLUGIN_VERSION);
        assertEquals(info.services(), List.of(PaymentPluginApi.class.getName()));

        // The registration name differs from the plugin id on purpose: payment methods created
        // against the OSGi build stored 'hello-world-plugin', and those rows must keep resolving.
        assertEquals(paymentRegistry.getAllServices(), java.util.Set.of(HelloWorldPlugin.REGISTRATION_NAME));
        assertNotNull(paymentRegistry.getServiceForName(HelloWorldPlugin.REGISTRATION_NAME));
    }

    /**
     * A payment driven through the interface Kill Bill's core calls, answered by code loaded from
     * the plugin jar. This is the round trip that "the plugin loads" does not prove.
     */
    @Test(groups = "fast", dependsOnMethods = "testThePluginIsDiscoveredStartedAndVisibleToThePaymentCore")
    public void testAPaymentReachesThePluginAndAResultComesBack() throws Exception {
        final PaymentPluginApi plugin = paymentRegistry.getServiceForName(HelloWorldPlugin.REGISTRATION_NAME);

        final UUID accountId = UUID.randomUUID();
        final UUID paymentId = UUID.randomUUID();
        final UUID transactionId = UUID.randomUUID();
        final BigDecimal amount = new BigDecimal("42.00");

        final PaymentTransactionInfoPlugin result = plugin.purchasePayment(
                accountId, paymentId, transactionId, UUID.randomUUID(), amount, Currency.USD, List.of(), null);

        assertNotNull(result, "The plugin returned nothing; the call did not reach a working implementation");
        assertEquals(result.getKbPaymentId(), paymentId);
        assertEquals(result.getKbTransactionPaymentId(), transactionId);
        assertEquals(result.getAmount(), amount);
        assertEquals(result.getCurrency(), Currency.USD);
        assertEquals(result.getStatus(), PaymentPluginStatus.PROCESSED);

        // Configuration from plugin.yaml reached the plugin and shaped its behaviour.
        assertTrue(result.getFirstPaymentReferenceId().startsWith("Hello from LPR"),
                   "Expected the configured greeting in the gateway reference, got: "
                   + result.getFirstPaymentReferenceId());

        // The plugin holds its own state across calls.
        assertEquals(plugin.getPaymentInfo(accountId, paymentId, List.of(), null).size(), 1);
    }

    /**
     * The isolation that made the ClassLoader work worth doing: the plugin's classes are loaded
     * from its jar, not shared with the core, even though the same class names happen to be on the
     * test classpath. The API types, by contrast, must be shared -- otherwise the cast the registry
     * performs would fail.
     */
    @Test(groups = "fast", dependsOnMethods = "testThePluginIsDiscoveredStartedAndVisibleToThePaymentCore")
    public void testThePluginRunsFromItsJarWhileSharingTheApiTypes() {
        final PaymentPluginApi plugin = paymentRegistry.getServiceForName(HelloWorldPlugin.REGISTRATION_NAME);

        // Registered as PaymentPluginApi and reachable as one: proof the API type resolved to a
        // single Class object on both sides of the boundary.
        assertTrue(PaymentPluginApi.class.isInstance(plugin));

        final ClassLoader pluginClassLoader = plugin.getClass().getClassLoader();
        assertNotSame(pluginClassLoader, getClass().getClassLoader(),
                      "The plugin was loaded by the application ClassLoader; it is not isolated, and this "
                      + "test would pass even with the ClassLoader policy broken");

        assertSame(loadThroughPluginClassLoader(pluginClassLoader, PaymentPluginApi.class.getName()),
                   PaymentPluginApi.class,
                   "The plugin resolved the payment SPI to its own copy");
    }

    /** Stopping withdraws it from Kill Bill's registry, so no payment can reach a stopped plugin. */
    @Test(groups = "fast", dependsOnMethods = {"testAPaymentReachesThePluginAndAResultComesBack",
                                               "testThePluginRunsFromItsJarWhileSharingTheApiTypes"})
    public void testStoppingThePluginRemovesItFromThePaymentCore() {
        assertTrue(pluginManager.stop(PLUGIN_ID));

        assertEquals(pluginManager.state(PLUGIN_ID).orElseThrow(), PluginState.STOPPED);
        assertTrue(paymentRegistry.getAllServices().isEmpty());
        assertNull(paymentRegistry.getServiceForName(HelloWorldPlugin.REGISTRATION_NAME));
    }

    /**
     * Lays the plugin out exactly as a deployment would: its compiled classes packaged into a jar,
     * beside the descriptor it ships, under {@code plugins/<id>/<version>/}.
     */
    private void installPluginAsItWouldBeDeployed() throws IOException {
        final Path versionDir = root.resolve("plugins").resolve(PLUGIN_ID).resolve(PLUGIN_VERSION);
        Files.createDirectories(versionDir);

        new PluginJarBuilder().packageDirectory(Path.of("target", "classes"),
                                                versionDir.resolve(PluginRepository.PLUGIN_JAR));

        Files.copy(Path.of("src", "main", "resources", "plugin.yaml"),
                   versionDir.resolve(new YamlDescriptorParser().descriptorFileName()));
    }

    private Class<?> loadThroughPluginClassLoader(final ClassLoader classLoader, final String className) {
        try {
            return classLoader.loadClass(className);
        } catch (final ClassNotFoundException e) {
            throw new IllegalStateException("Plugin ClassLoader cannot see " + className, e);
        }
    }

    /**
     * {@code DefaultPaymentProviderPluginRegistry} reads one value from its config. A proxy avoids
     * pulling in a mocking framework, and avoids implementing several dozen methods by hand.
     */
    private PaymentConfig stubPaymentConfig() {
        return (PaymentConfig) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{PaymentConfig.class},
                (proxy, method, args) -> {
                    if ("getDefaultPaymentProvider".equals(method.getName())) {
                        return HelloWorldPlugin.REGISTRATION_NAME;
                    }
                    return method.getReturnType().isPrimitive() ? defaultPrimitive(method.getReturnType()) : null;
                });
    }

    private Object defaultPrimitive(final Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}

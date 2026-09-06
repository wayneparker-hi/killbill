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

package org.killbill.billing.lpr.testkit;

import java.util.List;
import java.util.Map;

import org.killbill.billing.lpr.api.PluginState;
import org.killbill.billing.lpr.core.PluginInfo;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

/**
 * The runtime doing its actual job, with nothing stubbed.
 * <p>
 * Every test here compiles a plugin, packages it as a jar, writes a real {@code plugin.yaml}, lays
 * it out on disk the way a deployment does, and drives it through the same code the server runs.
 * The unit tests elsewhere check each piece in isolation; these check that the pieces agree -- the
 * descriptor's entrypoint resolves through the ClassLoader the repository built, the service the
 * plugin registers is the one the core finds, and the version the repository selects is the one
 * that ends up serving traffic.
 */
public class TestPluginRuntimeEndToEnd {

    /** Stands in for a Kill Bill plugin SPI. In {@code org.killbill.*}, so it resolves parent-first. */
    public interface PaymentGateway {
        String charge(String amount);
    }

    /** A platform service, as the core publishes to plugins. */
    public interface Clock {
        String now();
    }

    @Test(groups = "fast")
    public void testAPluginIsDiscoveredStartedAndServesTheCore() {
        try (PluginTestRuntime runtime = PluginTestRuntime.create()) {
            runtime.install(PluginFixture.plugin("acme-payment", "1.0.0")
                                         .named("Acme Payments")
                                         .entrypoint("com.acme.AcmePlugin", gatewayPluginSource("com.acme",
                                                                                                "AcmePlugin",
                                                                                                "acme")));

            assertEquals(runtime.discover(), 1);
            assertEquals(runtime.state("acme-payment").orElseThrow(), PluginState.INSTALLED);

            runtime.manager().start("acme-payment");

            assertEquals(runtime.state("acme-payment").orElseThrow(), PluginState.ACTIVE);
            assertEquals(runtime.service(PaymentGateway.class, "acme-payment").charge("10.00"),
                         "acme charged 10.00");

            final PluginInfo info = runtime.manager().get("acme-payment").orElseThrow();
            assertEquals(info.name(), "Acme Payments");
            assertEquals(info.version(), "1.0.0");
            assertTrue(info.isRunning());
            assertEquals(info.services(), List.of(PaymentGateway.class.getName()));
        }
    }

    @Test(groups = "fast")
    public void testStoppingAPluginRemovesItFromTheCoreReach() {
        try (PluginTestRuntime runtime = PluginTestRuntime.create()) {
            runtime.install(PluginFixture.plugin("acme-payment", "1.0.0")
                                         .entrypoint("com.acme.AcmePlugin",
                                                     gatewayPluginSource("com.acme", "AcmePlugin", "acme")));
            runtime.startAll();

            runtime.manager().stop("acme-payment");

            assertEquals(runtime.state("acme-payment").orElseThrow(), PluginState.STOPPED);
            assertTrue(runtime.services().getService(PaymentGateway.class, "acme-payment").isEmpty());
            assertThrows(IllegalStateException.class, () -> runtime.service(PaymentGateway.class, "acme-payment"));
        }
    }

    /**
     * The capability the OSGi layer was carrying, now over the real repository path: two plugins
     * that disagree about a library version both work, each seeing its own copy.
     */
    @Test(groups = "fast")
    public void testTwoPluginsCarryIncompatibleCopiesOfTheSameLibrary() {
        try (PluginTestRuntime runtime = PluginTestRuntime.create()) {
            runtime.install(PluginFixture.plugin("alpha-payment", "1.0.0")
                                         .entrypoint("com.alpha.AlphaPlugin",
                                                     libraryUsingPluginSource("com.alpha", "AlphaPlugin", "alpha"))
                                         .withClass("com.shared.Formatter", formatterSource("USD %s")));

            runtime.install(PluginFixture.plugin("beta-payment", "1.0.0")
                                         .entrypoint("com.beta.BetaPlugin",
                                                     libraryUsingPluginSource("com.beta", "BetaPlugin", "beta"))
                                         .withClass("com.shared.Formatter", formatterSource("EUR %s")));

            runtime.startAll();

            // Same class name, incompatible behaviour, both live at once.
            assertEquals(runtime.service(PaymentGateway.class, "alpha-payment").charge("10.00"), "USD 10.00");
            assertEquals(runtime.service(PaymentGateway.class, "beta-payment").charge("10.00"), "EUR 10.00");

            assertNotSame(runtime.service(PaymentGateway.class, "alpha-payment").getClass().getClassLoader(),
                          runtime.service(PaymentGateway.class, "beta-payment").getClass().getClassLoader());
        }
    }

    @Test(groups = "fast")
    public void testDescriptorConfigurationReachesThePlugin() {
        try (PluginTestRuntime runtime = PluginTestRuntime.create()) {
            runtime.install(PluginFixture.plugin("configured-payment", "1.0.0")
                                         .entrypoint("com.cfg.ConfiguredPlugin", configuredPluginSource())
                                         .withConfig("gateway.currency", "GBP")
                                         .withConfig("gateway.timeout", "30000"));
            runtime.startAll();

            assertEquals(runtime.service(PaymentGateway.class, "configured-payment").charge("5.00"),
                         "GBP 5.00 timeout=30000");
        }
    }

    @Test(groups = "fast")
    public void testAPluginCanReachPlatformServices() {
        final Clock clock = () -> "2026-01-01";
        try (PluginTestRuntime runtime = PluginTestRuntime.create(Map.of(Clock.class, clock))) {
            runtime.install(PluginFixture.plugin("clock-payment", "1.0.0")
                                         .entrypoint("com.clk.ClockPlugin", platformServicePluginSource()));
            runtime.startAll();

            assertEquals(runtime.service(PaymentGateway.class, "clock-payment").charge("1.00"),
                         "charged 1.00 at 2026-01-01");
        }
    }

    /**
     * Version selection. The highest installed version runs by default, which is what dropping a
     * new directory in and restarting should do -- and it must be a numeric comparison, since the
     * ordering this replaced would have picked 9.0.0 over 10.0.0.
     */
    @Test(groups = "fast")
    public void testTheHighestInstalledVersionRunsByDefault() {
        try (PluginTestRuntime runtime = PluginTestRuntime.create()) {
            installVersion(runtime, "9.0.0");
            installVersion(runtime, "10.0.0");
            runtime.startAll();

            assertEquals(runtime.manager().get("versioned-payment").orElseThrow().version(), "10.0.0");
            assertEquals(runtime.service(PaymentGateway.class, "versioned-payment").charge("1.00"),
                         "v10.0.0 charged 1.00");
        }
    }

    @Test(groups = "fast")
    public void testTheActiveMarkerPinsAVersion() {
        try (PluginTestRuntime runtime = PluginTestRuntime.create()) {
            installVersion(runtime, "1.0.0");
            installVersion(runtime, "2.0.0");
            runtime.setActiveVersion("versioned-payment", "1.0.0");

            runtime.startAll();

            assertEquals(runtime.manager().get("versioned-payment").orElseThrow().version(), "1.0.0");
        }
    }

    @Test(groups = "fast")
    public void testADisabledVersionIsSkipped() {
        try (PluginTestRuntime runtime = PluginTestRuntime.create()) {
            installVersion(runtime, "1.0.0");
            installVersion(runtime, "2.0.0");
            runtime.disableVersion("versioned-payment", "2.0.0");

            runtime.startAll();

            assertEquals(runtime.manager().get("versioned-payment").orElseThrow().version(), "1.0.0");
        }
    }

    /**
     * The reason upgrades load side by side rather than stopping first.
     * <p>
     * The incoming version checks, from inside its own {@code start()}, whether the outgoing one is
     * still serving. If the manager stopped the incumbent first there would be a window with no
     * payment provider at all -- for a payment or tax plugin that window is failed business, not a
     * blip. Asserting from inside the candidate makes the check deterministic instead of depending
     * on a race.
     */
    @Test(groups = "fast")
    public void testUpgradingLeavesNoWindowWithoutAProvider() {
        try (PluginTestRuntime runtime = PluginTestRuntime.create()) {
            installVersion(runtime, "1.0.0");
            runtime.startAll();
            assertEquals(runtime.manager().get("versioned-payment").orElseThrow().version(), "1.0.0");

            runtime.install(PluginFixture.plugin("versioned-payment", "2.0.0")
                                         .entrypoint("com.ver.VersionedPlugin", incumbentProbingPluginSource()));

            runtime.manager().activateVersion("versioned-payment", "2.0.0");

            assertEquals(runtime.manager().get("versioned-payment").orElseThrow().version(), "2.0.0");
            assertEquals(runtime.service(PaymentGateway.class, "versioned-payment").charge("1.00"),
                         "v2 (incumbent was serving during startup: true)",
                         "The outgoing version was stopped before the incoming one started");
        }
    }

    @Test(groups = "fast")
    public void testRollbackReturnsToThePreviousVersion() {
        try (PluginTestRuntime runtime = PluginTestRuntime.create()) {
            installVersion(runtime, "1.0.0");
            installVersion(runtime, "2.0.0");
            runtime.setActiveVersion("versioned-payment", "1.0.0");
            runtime.startAll();

            runtime.manager().activateVersion("versioned-payment", "2.0.0");
            assertEquals(runtime.manager().get("versioned-payment").orElseThrow().version(), "2.0.0");

            runtime.manager().rollback("versioned-payment");

            assertEquals(runtime.manager().get("versioned-payment").orElseThrow().version(), "1.0.0");
            assertEquals(runtime.service(PaymentGateway.class, "versioned-payment").charge("1.00"),
                         "v1.0.0 charged 1.00");
        }
    }

    /**
     * A billing system with a broken analytics plugin should still take payments. One plugin
     * failing must not stop the others from starting.
     */
    @Test(groups = "fast")
    public void testOneBrokenPluginDoesNotPreventTheOthersFromStarting() {
        try (PluginTestRuntime runtime = PluginTestRuntime.create()) {
            runtime.install(PluginFixture.plugin("good-payment", "1.0.0")
                                         .entrypoint("com.good.GoodPlugin",
                                                     gatewayPluginSource("com.good", "GoodPlugin", "good")));
            runtime.install(PluginFixture.plugin("broken-payment", "1.0.0")
                                         .entrypoint("com.broken.BrokenPlugin", failingPluginSource()));

            runtime.discover();
            final List<String> started = runtime.manager().startAll(List.of());

            assertEquals(started, List.of("good-payment"));
            assertEquals(runtime.state("broken-payment").orElseThrow(), PluginState.FAILED);
            assertTrue(runtime.manager().get("broken-payment").orElseThrow().failureReason().contains("no credentials"));
            assertEquals(runtime.service(PaymentGateway.class, "good-payment").charge("1.00"), "good charged 1.00");
        }
    }

    /** A plugin the deployment declares essential is different: its failure should stop startup. */
    @Test(groups = "fast")
    public void testAMandatoryPluginFailingAbortsStartup() {
        try (PluginTestRuntime runtime = PluginTestRuntime.create()) {
            runtime.install(PluginFixture.plugin("broken-payment", "1.0.0")
                                         .entrypoint("com.broken.BrokenPlugin", failingPluginSource()));
            runtime.discover();

            final IllegalStateException failure = org.testng.Assert.expectThrows(
                    IllegalStateException.class,
                    () -> runtime.manager().startAll(List.of("broken-payment")));
            assertTrue(failure.getMessage().contains("broken-payment"), failure.getMessage());
        }
    }

    /**
     * A plugin whose declared identity disagrees with where it is installed would register services
     * under a name the core never looks up, so it is refused rather than started.
     */
    @Test(groups = "fast")
    public void testAPluginWhoseDescriptorContradictsItsLocationIsRejected() {
        try (PluginTestRuntime runtime = PluginTestRuntime.create()) {
            final var installed = runtime.install(PluginFixture.plugin("honest-payment", "1.0.0")
                                                               .entrypoint("com.honest.HonestPlugin",
                                                                           gatewayPluginSource("com.honest",
                                                                                               "HonestPlugin",
                                                                                               "honest")));
            // Rewrite the descriptor to claim a different identity.
            final var descriptor = installed.resolve("plugin.yaml");
            try {
                java.nio.file.Files.writeString(descriptor,
                                                java.nio.file.Files.readString(descriptor)
                                                                   .replace("id: honest-payment", "id: other-payment"));
            } catch (final java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }

            assertEquals(runtime.discover(), 0, "A plugin contradicting its own location was accepted");
        }
    }

    @Test(groups = "fast")
    public void testPluginsExchangeEventsThroughTheRuntime() {
        try (PluginTestRuntime runtime = PluginTestRuntime.create()) {
            runtime.install(PluginFixture.plugin("listener-payment", "1.0.0")
                                         .entrypoint("com.lst.ListenerPlugin", eventListeningPluginSource()));
            runtime.startAll();

            runtime.eventBus().publish("payment-succeeded");

            assertEquals(runtime.service(PaymentGateway.class, "listener-payment").charge("ignored"),
                         "saw: payment-succeeded");
        }
    }

    /** Stopping releases the plugin's tracked resources. */
    @Test(groups = "fast")
    public void testStoppingReleasesResourcesThePluginRegistered() {
        try (PluginTestRuntime runtime = PluginTestRuntime.create()) {
            runtime.install(PluginFixture.plugin("resourceful-payment", "1.0.0")
                                         .entrypoint("com.res.ResourcefulPlugin", resourceTrackingPluginSource()));
            runtime.startAll();
            assertFalse(ResourceProbe.released);

            runtime.manager().stop("resourceful-payment");

            assertTrue(ResourceProbe.released, "ResourceRegistry did not release the plugin's resource");
        }
    }

    /** Shared with plugin fixtures so a test can observe a resource being released. */
    public static final class ResourceProbe {
        public static volatile boolean released;

        public static void markReleased() {
            released = true;
        }

        public static void reset() {
            released = false;
        }
    }

    // ----------------------------------------------------------------------------------------
    // Plugin sources. Written out rather than generated so each test's plugin reads as the thing
    // it is: a small, complete plugin.
    // ----------------------------------------------------------------------------------------

    private void installVersion(final PluginTestRuntime runtime, final String version) {
        runtime.install(PluginFixture.plugin("versioned-payment", version)
                                     .entrypoint("com.ver.VersionedPlugin", versionedPluginSource(version)));
    }

    private static String header(final String packageName) {
        return "package " + packageName + ";\n"
               + "import org.killbill.billing.lpr.api.Plugin;\n"
               + "import org.killbill.billing.lpr.api.PluginContext;\n"
               + "import org.killbill.billing.lpr.testkit.TestPluginRuntimeEndToEnd.PaymentGateway;\n";
    }

    private static String gatewayPluginSource(final String packageName, final String className, final String label) {
        return header(packageName)
               + "public class " + className + " implements Plugin {\n"
               + "    @Override public void start(PluginContext context) {\n"
               + "        context.services().register(PaymentGateway.class,\n"
               + "            amount -> \"" + label + " charged \" + amount);\n"
               + "    }\n"
               + "    @Override public void stop() { }\n"
               + "}\n";
    }

    private static String libraryUsingPluginSource(final String packageName, final String className,
                                                   final String label) {
        return header(packageName)
               + "import com.shared.Formatter;\n"
               + "public class " + className + " implements Plugin {\n"
               + "    @Override public void start(PluginContext context) {\n"
               + "        context.services().register(PaymentGateway.class, amount -> new Formatter().format(amount));\n"
               + "    }\n"
               + "    @Override public void stop() { }\n"
               + "}\n";
    }

    private static String formatterSource(final String pattern) {
        return "package com.shared;\n"
               + "public class Formatter {\n"
               + "    public String format(String amount) { return String.format(\"" + pattern + "\", amount); }\n"
               + "}\n";
    }

    private static String configuredPluginSource() {
        return header("com.cfg")
               + "public class ConfiguredPlugin implements Plugin {\n"
               + "    @Override public void start(PluginContext context) {\n"
               + "        String currency = context.config().get(\"gateway.currency\");\n"
               + "        int timeout = context.config().find(\"gateway.timeout\", Integer.class).orElseThrow();\n"
               + "        context.services().register(PaymentGateway.class,\n"
               + "            amount -> currency + \" \" + amount + \" timeout=\" + timeout);\n"
               + "    }\n"
               + "    @Override public void stop() { }\n"
               + "}\n";
    }

    private static String platformServicePluginSource() {
        return header("com.clk")
               + "import org.killbill.billing.lpr.testkit.TestPluginRuntimeEndToEnd.Clock;\n"
               + "public class ClockPlugin implements Plugin {\n"
               + "    @Override public void start(PluginContext context) {\n"
               + "        Clock clock = context.getPlatformService(Clock.class);\n"
               + "        context.services().register(PaymentGateway.class,\n"
               + "            amount -> \"charged \" + amount + \" at \" + clock.now());\n"
               + "    }\n"
               + "    @Override public void stop() { }\n"
               + "}\n";
    }

    private static String versionedPluginSource(final String version) {
        return header("com.ver")
               + "public class VersionedPlugin implements Plugin {\n"
               + "    @Override public void start(PluginContext context) {\n"
               + "        context.services().register(PaymentGateway.class,\n"
               + "            amount -> \"v" + version + " charged \" + amount);\n"
               + "    }\n"
               + "    @Override public void stop() { }\n"
               + "}\n";
    }

    /** Records, from inside its own start(), whether the outgoing version was still serving. */
    private static String incumbentProbingPluginSource() {
        return header("com.ver")
               + "public class VersionedPlugin implements Plugin {\n"
               + "    @Override public void start(PluginContext context) {\n"
               + "        boolean incumbentServing =\n"
               + "            context.services().getService(PaymentGateway.class, context.pluginId()).isPresent();\n"
               + "        context.services().register(PaymentGateway.class,\n"
               + "            amount -> \"v2 (incumbent was serving during startup: \" + incumbentServing + \")\");\n"
               + "    }\n"
               + "    @Override public void stop() { }\n"
               + "}\n";
    }

    private static String failingPluginSource() {
        return header("com.broken")
               + "public class BrokenPlugin implements Plugin {\n"
               + "    @Override public void start(PluginContext context) {\n"
               + "        throw new IllegalStateException(\"no credentials configured\");\n"
               + "    }\n"
               + "    @Override public void stop() { }\n"
               + "}\n";
    }

    private static String eventListeningPluginSource() {
        return header("com.lst")
               + "import java.util.concurrent.atomic.AtomicReference;\n"
               + "public class ListenerPlugin implements Plugin {\n"
               + "    @Override public void start(PluginContext context) {\n"
               + "        AtomicReference<String> seen = new AtomicReference<>(\"nothing\");\n"
               + "        context.resources().manage(\n"
               + "            context.eventBus().subscribe(String.class, event -> seen.set(event)));\n"
               + "        context.services().register(PaymentGateway.class, amount -> \"saw: \" + seen.get());\n"
               + "    }\n"
               + "    @Override public void stop() { }\n"
               + "}\n";
    }

    private static String resourceTrackingPluginSource() {
        return header("com.res")
               + "import org.killbill.billing.lpr.testkit.TestPluginRuntimeEndToEnd.ResourceProbe;\n"
               + "public class ResourcefulPlugin implements Plugin {\n"
               + "    @Override public void start(PluginContext context) {\n"
               + "        ResourceProbe.reset();\n"
               + "        context.resources().onClose(ResourceProbe::markReleased);\n"
               + "        context.services().register(PaymentGateway.class, amount -> \"ok\");\n"
               + "    }\n"
               + "    @Override public void stop() { }\n"
               + "}\n";
    }
}

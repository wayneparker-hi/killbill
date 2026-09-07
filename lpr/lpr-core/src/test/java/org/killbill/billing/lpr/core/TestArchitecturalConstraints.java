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

import org.testng.annotations.Test;

import static org.testng.Assert.fail;

/**
 * Guards the constraint the whole design rests on: <b>the runtime owns the plugin model, and
 * nothing else</b> (CLAUDE.md, A1).
 * <p>
 * A dependency that reaches {@code lpr-core} stops being an implementation detail and becomes
 * architecture, because the runtime's behaviour starts depending on it. That is how the previous
 * generation ended up unable to leave OSGi: Felix was not a component, it was the layer everything
 * else was expressed in.
 * <p>
 * The failure mode this catches is mundane -- someone needs a cache, reaches for Guava, and adds
 * one line to a pom. Nothing breaks; the boundary just quietly stops being true. Checking it here
 * rather than in {@code maven-enforcer} is deliberate: enforcer is routinely skipped with
 * {@code -Dcheck.skip-enforcer=true}, and a rule that is off when it matters is not a rule.
 */
public class TestArchitecturalConstraints {

    /**
     * Infrastructure that must stay behind an SPI, mapped to the port that is supposed to hide it.
     * The class names are entry points people actually import, not arbitrary internals.
     */
    private static final Map<String, String> BANNED_TO_PORT = Map.of(
            "com.alipay.sofa.ark.spi.service.classloader.ClassLoaderService", "PluginClassLoaderFactory",
            "com.alipay.sofa.ark.container.service.ArkServiceContainer", "PluginClassLoaderFactory",
            "com.google.common.eventbus.EventBus", "EventBus (lpr-api) / EventBusProvider (lpr-spi)",
            "com.google.common.cache.CacheBuilder", "no port: write it by hand or add one",
            "io.micrometer.core.instrument.MeterRegistry", "MetricsCollector",
            "org.yaml.snakeyaml.Yaml", "DescriptorParser",
            "com.fasterxml.jackson.databind.ObjectMapper", "DescriptorParser",
            "org.osgi.framework.BundleContext", "none: OSGi is what this runtime replaces",
            "org.apache.felix.framework.Felix", "none: Felix is what this runtime replaces",
            "com.google.inject.Injector", "none: the runtime must not require a DI container"
    );

    /**
     * Adapters may depend on their backend; the runtime may not. If one of these resolves here, a
     * transitive dependency has crossed the boundary.
     */
    @Test(groups = "fast")
    public void testRuntimeDoesNotDependOnConcreteInfrastructure() {
        final List<String> violations = BANNED_TO_PORT.keySet().stream()
                                                      .filter(TestArchitecturalConstraints::isOnClasspath)
                                                      .sorted()
                                                      .toList();
        if (!violations.isEmpty()) {
            final StringBuilder message = new StringBuilder(
                    "lpr-core must not see concrete infrastructure; these resolved on its classpath:\n");
            for (final String violation : violations) {
                message.append("  - ").append(violation)
                       .append("\n      belongs behind: ").append(BANNED_TO_PORT.get(violation)).append('\n');
            }
            message.append("Depend on the port from lpr-core and put the library in an adapter module instead.");
            fail(message.toString());
        }
    }

    /**
     * The plugin ABI. Every type reachable from {@code lpr-api} is one the runtime and every plugin
     * must agree on forever, so the module deliberately has no dependencies at all -- adding one
     * would quietly conscript a third-party library into the contract.
     */
    /**
     * The runtime owns the plugin model; implementations of infrastructure live in adapter modules.
     * <p>
     * This is a stricter statement than "no third-party dependency": the default EventBus and the
     * default ClassLoader factory are both self-written with no third-party code, and they still
     * belong outside lpr-core. Keeping them out is what makes the port real -- an implementation
     * sitting in the core is one that can be wired concretely by accident, which is exactly what
     * happened to the EventBus before this test existed.
     */
    @Test(groups = "fast")
    public void testRuntimeShipsNoInfrastructureImplementation() {
        final Map<String, String> implementationsBelongingInAdapters = Map.of(
                "org.killbill.billing.lpr.core.DefaultEventBus", "lpr-event-default",
                "org.killbill.billing.lpr.core.DefaultPluginClassLoader", "lpr-classloader-default",
                "org.killbill.billing.lpr.core.DefaultPluginClassLoaderFactory", "lpr-classloader-default",
                "org.killbill.billing.lpr.core.YamlDescriptorParser", "lpr-descriptor-yaml");

        final List<String> violations = implementationsBelongingInAdapters.keySet().stream()
                                                                          .filter(TestArchitecturalConstraints::isOnClasspath)
                                                                          .sorted()
                                                                          .toList();
        if (!violations.isEmpty()) {
            final StringBuilder message = new StringBuilder(
                    "lpr-core ships an infrastructure implementation. Move it to its adapter module:\n");
            for (final String violation : violations) {
                message.append("  - ").append(violation)
                       .append("\n      belongs in: ").append(implementationsBelongingInAdapters.get(violation))
                       .append('\n');
            }
            fail(message.toString());
        }
    }

    @Test(groups = "fast")
    public void testPluginApiCarriesNoThirdPartyTypes() {
        for (final Class<?> apiType : List.of(org.killbill.billing.lpr.api.Plugin.class,
                                              org.killbill.billing.lpr.api.PluginContext.class,
                                              org.killbill.billing.lpr.api.ServiceRegistry.class,
                                              org.killbill.billing.lpr.api.EventBus.class,
                                              org.killbill.billing.lpr.api.ResourceRegistry.class)) {
            for (final var method : apiType.getMethods()) {
                assertNotThirdParty(apiType, method.getReturnType());
                for (final Class<?> parameterType : method.getParameterTypes()) {
                    assertNotThirdParty(apiType, parameterType);
                }
            }
        }
    }

    private static void assertNotThirdParty(final Class<?> apiType, final Class<?> referenced) {
        final String name = referenced.getName();
        final boolean allowed = referenced.isPrimitive()
                                || name.startsWith("java.")
                                || name.startsWith("[")
                                || name.startsWith("org.killbill.billing.lpr.api.");
        if (!allowed) {
            fail(apiType.getSimpleName() + " exposes " + name + " in its signature. Types in the plugin"
                 + " API become part of the ABI every plugin compiles against and can never be"
                 + " changed independently; keep third-party types behind an SPI.");
        }
    }

    private static boolean isOnClasspath(final String className) {
        try {
            Class.forName(className, false, TestArchitecturalConstraints.class.getClassLoader());
            return true;
        } catch (final ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }
}

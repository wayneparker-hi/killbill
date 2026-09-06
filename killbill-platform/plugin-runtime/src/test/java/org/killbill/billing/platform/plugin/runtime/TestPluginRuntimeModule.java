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

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServlet;

import org.killbill.billing.notification.plugin.api.NotificationPluginApi;
import org.killbill.billing.platform.api.KillbillConfigSource;
import org.killbill.billing.platform.config.DefaultKillbillConfigSource;
import org.killbill.billing.platform.plugin.api.PluginServiceProperties;
import org.killbill.billing.platform.plugin.api.PluginServiceRegistry;
import org.killbill.billing.runtime.api.PluginInfo;
import org.killbill.billing.runtime.api.PluginsInfoApi;
import org.killbill.billing.util.nodes.KillbillNodesApi;
import org.killbill.billing.util.nodes.NodeCommand;
import org.killbill.billing.util.nodes.NodeInfo;
import org.killbill.bus.api.PersistentBus;
import org.killbill.clock.DefaultClock;
import org.killbill.notificationq.api.NotificationQueueService;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Stage;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Names;

import static org.testng.Assert.assertNotNull;

/**
 * The module has to hand Kill Bill every binding the OSGi module used to hand it.
 * <p>
 * This is the failure mode that motivated the test: {@code PluginsInfoApi} was implemented and bound
 * by {@code DefaultOSGIModule}, and deleting that module silently removed the binding. Nothing in
 * the plugin runtime's own tests noticed, because nothing in the plugin runtime uses it -- the
 * consumer is {@code DefaultKillbillNodesService}, three modules away. The break surfaced only when
 * every Beatrix integration test failed at injector creation, which is both far from the cause and
 * expensive to reach.
 * <p>
 * So the assertions are deliberately about the <em>module's</em> contract rather than about any
 * class in this package: create the injector the way the platform does, and ask for the things
 * Kill Bill injects. Adding a platform-facing binding means adding a line here.
 * <p>
 * The stub bindings below are the other half of the contract -- what the module <em>requires</em> of
 * its host. They are not scaffolding to be trimmed: if this list has to grow, the plugin runtime has
 * become harder to deploy, and that is worth noticing here rather than in a profile that fails to
 * boot.
 */
public class TestPluginRuntimeModule {

    private KillbillConfigSource configSource;

    @BeforeClass(groups = "fast")
    public void beforeClass() throws Exception {
        final Path installDir = Files.createTempDirectory("lpr-module-test");
        installDir.toFile().deleteOnExit();
        configSource = new DefaultKillbillConfigSource(
                Map.of("org.killbill.billing.plugin.install.dir", installDir.toString(),
                       "org.killbill.billing.plugin.start.enabled", "false"));
    }

    /**
     * Creating the injector is most of the test: Guice reports a missing binding at creation time,
     * so a binding dropped from {@link PluginRuntimeModule} fails here rather than in whichever
     * downstream module happens to inject it first.
     */
    @Test(groups = "fast")
    public void testPlatformFacingBindingsArePresent() {
        final Injector injector = createInjector();

        // Consumed by DefaultKillbillNodesService and the /plugins REST resources, neither of which
        // this module can see.
        assertNotNull(injector.getInstance(PluginsInfoApi.class));

        // The servlet PluginResource dispatches /plugins/... into.
        assertNotNull(injector.getInstance(
                Key.get(HttpServlet.class, Names.named(PluginServiceProperties.PLUGIN_SERVLET))));
    }

    /**
     * Notification plugins are an extension point like the other twelve, not a special case.
     * <p>
     * Under OSGi this was the one plugin API delivered through an observer callback rather than a
     * registry, which is why it is asserted separately: the registry existing is the whole of that
     * change.
     */
    @Test(groups = "fast")
    public void testNotificationPluginsGoThroughARegistry() {
        final Injector injector = createInjector();

        assertNotNull(injector.getInstance(
                Key.get(new TypeLiteral<PluginServiceRegistry<NotificationPluginApi>>() { })));
        assertNotNull(injector.getInstance(PluginEventDispatcher.class));
    }

    private Injector createInjector() {
        return Guice.createInjector(Stage.PRODUCTION,
                                    new PluginRuntimeModule(configSource),
                                    binder -> {
                                        // Supplied by NodesModule in a real deployment. Bound here because
                                        // the cycle it participates in is exactly what this test protects:
                                        // the nodes service needs PluginsInfoApi, which needs the nodes API.
                                        binder.bind(KillbillNodesApi.class).toInstance(new NoOpNodesApi());

                                        // Required by PluginEventDispatcher, which subscribes to the
                                        // external bus. Every real profile binds these; they are stubbed
                                        // rather than mocked because this test never fires an event.
                                        binder.bind(PersistentBus.class)
                                              .annotatedWith(Names.named("externalBus"))
                                              .toInstance(stub(PersistentBus.class));
                                        binder.bind(NotificationQueueService.class)
                                              .toInstance(stub(NotificationQueueService.class));
                                        binder.bind(org.killbill.clock.Clock.class).toInstance(new DefaultClock());
                                    });
    }

    /**
     * A do-nothing implementation of an interface.
     * <p>
     * A dynamic proxy rather than a mocking library: these collaborators are never called here, so
     * the test only needs them to exist, and a proxy says that more plainly than a mock with no
     * stubbed behaviour.
     */
    @SuppressWarnings("unchecked")
    private static <T> T stub(final Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(),
                                          new Class<?>[]{type},
                                          (proxy, method, args) -> defaultValueFor(method.getReturnType()));
    }

    private static Object defaultValueFor(final Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == void.class) {
            return null;
        }
        return 0;
    }

    private static final class NoOpNodesApi implements KillbillNodesApi {

        @Override
        public Iterable<NodeInfo> getNodesInfo() {
            return List.of();
        }

        @Override
        public NodeInfo getCurrentNodeInfo() {
            return null;
        }

        @Override
        public void triggerNodeCommand(final NodeCommand nodeCommand, final boolean localNodeOnly) {
        }

        @Override
        public void notifyPluginChanged(final PluginInfo plugin, final Iterable<PluginInfo> latestPlugins) {
        }
    }
}

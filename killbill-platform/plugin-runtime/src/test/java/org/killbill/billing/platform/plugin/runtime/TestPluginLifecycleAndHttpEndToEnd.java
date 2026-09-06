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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.killbill.billing.lpr.api.PluginState;
import org.killbill.billing.lpr.core.DefaultPluginManager;
import org.killbill.billing.lpr.testkit.PluginJarBuilder;
import org.killbill.billing.platform.api.KillbillConfigSource;
import org.killbill.billing.platform.config.DefaultKillbillConfigSource;
import org.killbill.billing.platform.plugin.api.PluginServiceProperties;
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
import com.google.inject.name.Names;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Drives a real plugin through the real lifecycle handlers and out to HTTP.
 * <p>
 * The migration test in the hello-world plugin proves the runtime can load a jar and route its
 * services into Kill Bill's registries, but it calls {@code PluginManager} directly. That leaves two
 * things unverified, and both are the kind that only break in production:
 * <ul>
 *   <li>{@link PluginRuntimeService} -- whether the {@code INIT_PLUGIN} / {@code START_PLUGIN} /
 *       {@code STOP_PLUGIN} handlers actually discover and start anything when the platform's
 *       lifecycle invokes them, rather than when a test invokes the manager.</li>
 *   <li>The HTTP path -- whether a servlet a plugin registers is reachable at
 *       {@code /plugins/<name>/...}, which involves the bridge, the router's longest-prefix match
 *       and the request wrapper that hides the prefix from the plugin.</li>
 * </ul>
 * So this test starts from the injector and ends at a response body.
 */
public class TestPluginLifecycleAndHttpEndToEnd {

    private static final String PLUGIN_ID = "e2e-servlet";
    private static final String VERSION = "1.0.0";

    private Injector injector;
    private Path root;

    @BeforeClass(groups = "fast")
    public void beforeClass() throws Exception {
        root = Files.createTempDirectory("lpr-e2e-");
        installPluginAsItWouldBeDeployed();

        final KillbillConfigSource configSource = new DefaultKillbillConfigSource(
                Map.of("org.killbill.billing.plugin.install.dir", root.resolve("plugins").toString()));

        injector = Guice.createInjector(Stage.PRODUCTION,
                                        new PluginRuntimeModule(configSource),
                                        binder -> {
                                            binder.bind(KillbillNodesApi.class).toInstance(new NoOpNodesApi());
                                            binder.bind(PersistentBus.class)
                                                  .annotatedWith(Names.named("externalBus"))
                                                  .toInstance(stub(PersistentBus.class));
                                            binder.bind(NotificationQueueService.class)
                                                  .toInstance(stub(NotificationQueueService.class));
                                            binder.bind(org.killbill.clock.Clock.class).toInstance(new DefaultClock());
                                        });
    }

    /**
     * The whole path, in the order the platform walks it.
     * <p>
     * One test rather than several because the steps are not independent: nothing can be served over
     * HTTP until the lifecycle has started the plugin, and asserting the intermediate states in the
     * same method is what makes a failure say <em>which</em> step broke.
     */
    @Test(groups = "fast")
    public void testTheLifecycleDiscoversStartsAndServesAPlugin() throws Exception {
        final PluginRuntimeService service = injector.getInstance(PluginRuntimeService.class);
        final DefaultPluginManager manager = injector.getInstance(DefaultPluginManager.class);
        final PluginsInfoApi pluginsInfo = injector.getInstance(PluginsInfoApi.class);

        // INIT_PLUGIN
        service.initialize();
        assertEquals(manager.list().size(), 1, "The lifecycle's INIT_PLUGIN did not discover the plugin");
        assertEquals(manager.state(PLUGIN_ID).orElseThrow(), PluginState.INSTALLED);

        // START_PLUGIN
        service.start();
        assertEquals(manager.state(PLUGIN_ID).orElseThrow(), PluginState.ACTIVE,
                     "The lifecycle's START_PLUGIN did not start the plugin");

        // What the /1.0/kb/pluginsInfo REST resource will report.
        final PluginInfo reported = pluginsInfo.getPluginsInfo().iterator().next();
        assertEquals(reported.getPluginName(), PLUGIN_ID);
        assertEquals(reported.getVersion(), VERSION);
        assertEquals(reported.getPluginState(), org.killbill.billing.runtime.api.PluginState.RUNNING);

        // The HTTP path: PluginResource injects this servlet and hands it /plugins/... requests.
        final HttpServlet frontServlet = injector.getInstance(
                Key.get(HttpServlet.class, Names.named(PluginServiceProperties.PLUGIN_SERVLET)));

        final StringWriter body = new StringWriter();
        frontServlet.service(request("/" + PLUGIN_ID + "/ping"), response(body));

        assertEquals(body.toString(), "pong from " + PLUGIN_ID,
                     "The request did not reach the plugin's servlet");

        // STOP_PLUGIN
        service.stop();
        assertEquals(manager.state(PLUGIN_ID).orElseThrow(), PluginState.STOPPED);

        final StringWriter afterStop = new StringWriter();
        frontServlet.service(request("/" + PLUGIN_ID + "/ping"), response(afterStop));
        assertTrue(afterStop.toString().isEmpty(),
                   "A stopped plugin must no longer serve requests, but returned: " + afterStop);
    }

    /**
     * Lays the plugin out exactly as a deployment would: a version directory holding a descriptor
     * and a jar. The jar is compiled here rather than referenced from the test classpath, so the
     * plugin's classes really do come through a plugin ClassLoader.
     */
    private void installPluginAsItWouldBeDeployed() throws Exception {
        final Path versionDir = root.resolve("plugins").resolve(PLUGIN_ID).resolve(VERSION);
        Files.createDirectories(versionDir);
        Files.writeString(versionDir.resolve("plugin.yaml"),
                          "id: " + PLUGIN_ID + '\n'
                          + "version: " + VERSION + '\n'
                          + "entrypoint:\n"
                          + "  class: " + PLUGIN_CLASS + '\n');
        new PluginJarBuilder().build(Map.of(PLUGIN_CLASS, PLUGIN_SOURCE), versionDir.resolve("plugin.jar"));
    }

    private static final String PLUGIN_CLASS = "org.killbill.billing.plugin.e2e.PingPlugin";

    /**
     * Registers one servlet, which is all this test needs to exercise the HTTP path.
     * <p>
     * Written as source rather than as a nested class so that it is compiled into the plugin jar and
     * nowhere else -- a class present on both sides would be loaded by the parent and prove nothing
     * about routing through a plugin ClassLoader.
     */
    private static final String PLUGIN_SOURCE =
            "package org.killbill.billing.plugin.e2e;\n"
            + "import java.io.IOException;\n"
            + "import java.util.Map;\n"
            + "import jakarta.servlet.Servlet;\n"
            + "import jakarta.servlet.http.HttpServlet;\n"
            + "import jakarta.servlet.http.HttpServletRequest;\n"
            + "import jakarta.servlet.http.HttpServletResponse;\n"
            + "import org.killbill.billing.lpr.api.Plugin;\n"
            + "import org.killbill.billing.lpr.api.PluginContext;\n"
            + "public class PingPlugin implements Plugin {\n"
            + "  public void start(final PluginContext context) {\n"
            + "    context.services().register(Servlet.class, new PingServlet(context.pluginId()),\n"
            + "        Map.of(\"killbill.pluginName\", context.pluginId()));\n"
            + "  }\n"
            + "  public void stop() { }\n"
            + "  public static class PingServlet extends HttpServlet {\n"
            + "    private final String pluginId;\n"
            + "    PingServlet(final String pluginId) { this.pluginId = pluginId; }\n"
            + "    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp)\n"
            + "        throws IOException { resp.getWriter().write(\"pong from \" + pluginId); }\n"
            + "  }\n"
            + "}\n";

    /**
     * Shaped the way {@code PluginResource} forwards: the JAX-RS layer has already consumed
     * {@code /plugins}, so the servlet sees the remainder as its path info. {@code PluginServlet}
     * reconstructs the path it matches on as {@code getServletPath() + getPathInfo()}, which is why
     * these two must not both carry the whole path.
     */
    private static HttpServletRequest request(final String pathAfterPluginsPrefix) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                TestPluginLifecycleAndHttpEndToEnd.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (InvocationHandler) (proxy, method, args) -> switch (method.getName()) {
                    case "getPathInfo" -> pathAfterPluginsPrefix;
                    case "getRequestURI" -> "/plugins" + pathAfterPluginsPrefix;
                    case "getServletPath", "getContextPath" -> "";
                    case "getMethod" -> "GET";
                    default -> defaultValueFor(method.getReturnType());
                });
    }

    private static HttpServletResponse response(final StringWriter body) {
        final PrintWriter writer = new PrintWriter(body);
        return (HttpServletResponse) Proxy.newProxyInstance(
                TestPluginLifecycleAndHttpEndToEnd.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (InvocationHandler) (proxy, method, args) -> {
                    if ("getWriter".equals(method.getName())) {
                        return writer;
                    }
                    if ("flushBuffer".equals(method.getName())) {
                        writer.flush();
                        return null;
                    }
                    return defaultValueFor(method.getReturnType());
                });
    }

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
            return java.util.List.of();
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

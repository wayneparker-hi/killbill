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
import java.util.UUID;

import org.killbill.billing.ObjectType;
import org.killbill.billing.lpr.api.PluginState;
import org.killbill.billing.lpr.classloader.DefaultPluginClassLoaderFactory;
import org.killbill.billing.lpr.core.DefaultEventBus;
import org.killbill.billing.lpr.core.DefaultPluginLifecycleManager;
import org.killbill.billing.lpr.core.DefaultPluginManager;
import org.killbill.billing.lpr.core.DefaultServiceRegistry;
import org.killbill.billing.lpr.core.PluginRepository;
import org.killbill.billing.lpr.descriptor.yaml.YamlDescriptorParser;
import org.killbill.billing.lpr.management.PluginInstaller;
import org.killbill.billing.lpr.spi.ClassLoaderPolicy;
import org.killbill.billing.lpr.testkit.PluginJarBuilder;
import org.killbill.billing.notification.plugin.api.ExtBusEvent;
import org.killbill.billing.notification.plugin.api.ExtBusEventType;
import org.killbill.billing.runtime.api.PluginInfo;
import org.killbill.billing.runtime.api.PluginsInfoApi;
import org.killbill.billing.runtime.api.PluginStateChange;
import org.killbill.billing.util.nodes.KillbillNodesApi;
import org.killbill.billing.util.nodes.NodeCommand;
import org.killbill.billing.util.nodes.NodeInfo;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * The cluster-wide management surface: install, start, stop, restart and uninstall, driven by the
 * same {@code BROADCAST_SERVICE} events {@code /1.0/kb/nodesInfo} produces.
 * <p>
 * This is the only path by which an operator can act on plugins, and every one of its failure modes
 * is silent. Under OSGi, {@code INSTALL_PLUGIN} and {@code UNINSTALL_PLUGIN} were declared alongside
 * the other commands but implemented by KPM, so a deployment without KPM accepted them and did
 * nothing at all -- which is exactly the shape of bug this test exists to prevent from recurring.
 */
public class TestPluginNodeCommandListener {

    private static final String PLUGIN_ID = "node-cmd";
    private static final String CLASS_NAME = "org.killbill.billing.plugin.nodecmd.NoopPlugin";

    private static final String SOURCE =
            "package org.killbill.billing.plugin.nodecmd;\n"
            + "import org.killbill.billing.lpr.api.Plugin;\n"
            + "import org.killbill.billing.lpr.api.PluginContext;\n"
            + "public class NoopPlugin implements Plugin {\n"
            + "  public void start(final PluginContext context) { }\n"
            + "  public void stop() { }\n"
            + "}\n";

    private Path root;
    private Path artifact;
    private DefaultPluginManager manager;
    private PluginInstaller installer;
    private PluginNodeCommandListener listener;

    @BeforeMethod(groups = "fast")
    public void beforeMethod() throws Exception {
        root = Files.createTempDirectory("lpr-nodecmd-");

        // A jar sitting somewhere else on disk, standing in for a release artifact -- carrying its
        // own plugin.yaml, the way a plugin built by this project does.
        artifact = Files.createTempDirectory("lpr-artifact-").resolve("plugin.jar");
        new PluginJarBuilder().build(Map.of(CLASS_NAME, SOURCE), artifact);
        addDescriptorTo(artifact, "id: " + PLUGIN_ID + "\nversion: 1.0.0\nentrypoint:\n  class: " + CLASS_NAME + "\n");

        final PluginRepository repository = new PluginRepository(root, new YamlDescriptorParser());
        final DefaultServiceRegistry services = new DefaultServiceRegistry();
        manager = new DefaultPluginManager(repository,
                                           new DefaultPluginLifecycleManager(new DefaultPluginClassLoaderFactory(),
                                                                             ClassLoaderPolicy.defaultPolicy(),
                                                                             getClass().getClassLoader(),
                                                                             services,
                                                                             new DefaultEventBus(),
                                                                             Map.of()),
                                           services);
        installer = new PluginInstaller(repository);
        listener = new PluginNodeCommandListener(manager, installer, new NoopPluginsInfoApi(), NoopNodesApi::new);
    }

    /**
     * Installing lays the version out and makes the runtime aware of it, but deliberately stops
     * short of running it: choosing a version and choosing to run it are separate decisions, which
     * is what makes a staged rollout possible.
     */
    @Test(groups = "fast")
    public void testInstallMakesTheVersionKnownWithoutStartingIt() {
        listener.handleKillbillEvent(command("INSTALL_PLUGIN", PLUGIN_ID, "1.0.0",
                                             Map.of("uri", artifact.toUri().toString())));

        assertTrue(Files.isDirectory(root.resolve(PLUGIN_ID).resolve("1.0.0")));
        assertEquals(manager.state(PLUGIN_ID).orElseThrow(), PluginState.INSTALLED);
        assertFalse(manager.isRunning(PLUGIN_ID), "INSTALL_PLUGIN must not start the plugin");
    }

    @Test(groups = "fast")
    public void testStartStopAndRestart() {
        install("1.0.0");

        listener.handleKillbillEvent(command("START_PLUGIN", PLUGIN_ID, null, Map.of()));
        assertEquals(manager.state(PLUGIN_ID).orElseThrow(), PluginState.ACTIVE);

        listener.handleKillbillEvent(command("STOP_PLUGIN", PLUGIN_ID, null, Map.of()));
        assertEquals(manager.state(PLUGIN_ID).orElseThrow(), PluginState.STOPPED);

        listener.handleKillbillEvent(command("RESTART_PLUGIN", PLUGIN_ID, null, Map.of()));
        assertEquals(manager.state(PLUGIN_ID).orElseThrow(), PluginState.ACTIVE);
    }

    /**
     * A version on the command means "make this the running one", which is the same operation as an
     * upgrade -- not a separate code path.
     */
    @Test(groups = "fast")
    public void testStartingWithAVersionSwitchesToThatVersion() {
        install("1.0.0");
        install("1.1.0");

        listener.handleKillbillEvent(command("START_PLUGIN", PLUGIN_ID, "1.0.0", Map.of()));

        assertEquals(manager.get(PLUGIN_ID).orElseThrow().version(), "1.0.0");
        assertEquals(manager.state(PLUGIN_ID).orElseThrow(), PluginState.ACTIVE);
    }

    /**
     * Uninstalling has to stop the plugin first. Deleting the jar underneath a live ClassLoader
     * fails only on the next class it has not yet loaded, far from the cause.
     */
    @Test(groups = "fast")
    public void testUninstallStopsThePluginBeforeDeletingIt() {
        install("1.0.0");
        listener.handleKillbillEvent(command("START_PLUGIN", PLUGIN_ID, null, Map.of()));
        assertTrue(manager.isRunning(PLUGIN_ID));

        listener.handleKillbillEvent(command("UNINSTALL_PLUGIN", PLUGIN_ID, "1.0.0", Map.of()));

        assertFalse(Files.exists(root.resolve(PLUGIN_ID).resolve("1.0.0")));
        assertTrue(manager.list().isEmpty(), "The runtime must no longer know about the plugin");
    }

    /**
     * A checksum that does not match means the artifact is not what was asked for. Installing it
     * anyway would put an unverified jar somewhere the runtime will happily load it.
     */
    @Test(groups = "fast")
    public void testInstallWithAWrongChecksumLeavesNothingBehind() {
        listener.handleKillbillEvent(command("INSTALL_PLUGIN", PLUGIN_ID, "1.0.0",
                                             Map.of("uri", artifact.toUri().toString(),
                                                    "sha1", "0".repeat(40))));

        assertFalse(Files.exists(root.resolve(PLUGIN_ID).resolve("1.0.0")));
        assertTrue(manager.list().isEmpty());
    }

    /**
     * Every node in the cluster sees every broadcast, including ones meant for other services. An
     * unrelated or malformed command must be ignored quietly rather than poisoning the subscription
     * for the commands that follow.
     */
    @Test(groups = "fast")
    public void testUnrelatedAndMalformedCommandsAreIgnored() {
        listener.handleKillbillEvent(event(ExtBusEventType.INVOICE_CREATION, "not json at all"));
        listener.handleKillbillEvent(command("SOME_OTHER_COMMAND", PLUGIN_ID, "1.0.0", Map.of()));
        listener.handleKillbillEvent(command("START_PLUGIN", "no-such-plugin", null, Map.of()));
        // INSTALL without the uri property saying where to fetch from
        listener.handleKillbillEvent(command("INSTALL_PLUGIN", PLUGIN_ID, "1.0.0", Map.of()));

        assertTrue(manager.list().isEmpty());

        // And the listener still works afterwards.
        install("1.0.0");
        assertEquals(manager.state(PLUGIN_ID).orElseThrow(), PluginState.INSTALLED);
    }

    /**
     * Puts a {@code plugin.yaml} inside an existing jar.
     * <p>
     * The installer copies it out on install, which is what makes {@code INSTALL_PLUGIN} work with
     * nothing but a URI. Written here rather than assumed, because a jar without one is a real case
     * the installer has to reject clearly.
     */
    private static void addDescriptorTo(final Path jar, final String descriptor) throws Exception {
        final Path rebuilt = jar.resolveSibling("with-descriptor.jar");
        try (java.util.jar.JarInputStream in =
                     new java.util.jar.JarInputStream(Files.newInputStream(jar));
             java.util.jar.JarOutputStream out =
                     new java.util.jar.JarOutputStream(Files.newOutputStream(rebuilt))) {
            java.util.jar.JarEntry entry;
            while ((entry = in.getNextJarEntry()) != null) {
                out.putNextEntry(new java.util.jar.JarEntry(entry.getName()));
                in.transferTo(out);
                out.closeEntry();
            }
            out.putNextEntry(new java.util.jar.JarEntry("plugin.yaml"));
            out.write(descriptor.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Files.move(rebuilt, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * The descriptor inside the jar always says 1.0.0, so installing another version needs an
     * explicit one. That is the {@code descriptor} property's purpose.
     */
    private void install(final String version) {
        listener.handleKillbillEvent(command("INSTALL_PLUGIN", PLUGIN_ID, version,
                                             Map.of("uri", artifact.toUri().toString(),
                                                    "descriptor",
                                                    "id: " + PLUGIN_ID + "\nversion: " + version
                                                    + "\nentrypoint:\n  class: " + CLASS_NAME + "\n")));
    }

    /** Builds the event shape {@code /1.0/kb/nodesInfo} broadcasts. */
    private static ExtBusEvent command(final String commandType,
                                       final String pluginName,
                                       final String pluginVersion,
                                       final Map<String, String> properties) {
        final StringBuilder props = new StringBuilder("[");
        properties.forEach((k, v) -> {
            if (props.length() > 1) {
                props.append(',');
            }
            props.append("{\"key\":\"").append(k).append("\",\"value\":").append(quote(v)).append('}');
        });
        props.append(']');

        final String eventJson = "{\"pluginKey\":null,\"pluginName\":\"" + pluginName + "\","
                                 + "\"pluginVersion\":" + (pluginVersion == null ? "null" : '"' + pluginVersion + '"')
                                 + ",\"properties\":" + props + '}';
        final String metaData = "{\"service\":\"nodes-service\",\"commandType\":\"" + commandType + "\","
                                + "\"eventJson\":" + quote(eventJson) + '}';
        return event(ExtBusEventType.BROADCAST_SERVICE, metaData);
    }

    /** Minimal JSON string escaping -- enough for the descriptors and URIs these commands carry. */
    private static String quote(final String value) {
        final StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static ExtBusEvent event(final ExtBusEventType type, final String metaData) {
        return (ExtBusEvent) Proxy.newProxyInstance(
                TestPluginNodeCommandListener.class.getClassLoader(),
                new Class<?>[]{ExtBusEvent.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getEventType" -> type;
                    case "getObjectType" -> ObjectType.SERVICE_BROADCAST;
                    case "getMetaData" -> metaData;
                    case "getUserToken" -> UUID.randomUUID();
                    case "toString" -> "ExtBusEvent(" + type + ')';
                    default -> null;
                });
    }

    private static final class NoopPluginsInfoApi implements PluginsInfoApi {

        @Override
        public Iterable<PluginInfo> getPluginsInfo() {
            return List.of();
        }

        @Override
        public void notifyOfStateChanged(final PluginStateChange newState,
                                         final String pluginId,
                                         final String pluginVersion) {
        }
    }

    private static final class NoopNodesApi implements KillbillNodesApi {

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

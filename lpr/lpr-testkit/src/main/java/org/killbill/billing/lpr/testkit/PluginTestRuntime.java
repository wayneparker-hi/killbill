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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.killbill.billing.lpr.api.PluginState;
import org.killbill.billing.lpr.classloader.DefaultPluginClassLoaderFactory;
import org.killbill.billing.lpr.core.DefaultEventBus;
import org.killbill.billing.lpr.core.DefaultPluginLifecycleManager;
import org.killbill.billing.lpr.core.DefaultPluginManager;
import org.killbill.billing.lpr.core.DefaultServiceRegistry;
import org.killbill.billing.lpr.core.PluginRepository;
import org.killbill.billing.lpr.descriptor.yaml.YamlDescriptorParser;
import org.killbill.billing.lpr.spi.ClassLoaderPolicy;
import org.killbill.billing.lpr.spi.PluginDescriptor;

/**
 * A complete plugin runtime over a temporary directory.
 * <p>
 * The point is that nothing is stubbed. A fixture is compiled, packaged into a jar, given a real
 * {@code plugin.yaml}, laid out on disk the way a deployment lays plugins out, then discovered and
 * started through the same code path production uses. A test that passes here has exercised the
 * ClassLoader policy, the descriptor parser, the repository's version selection, the lifecycle
 * ordering and the service registry together -- which is where the interesting failures live,
 * since each piece is easy to get right alone.
 * <p>
 * Close it when done, from a {@code finally} or try-with-resources: it stops the plugins and
 * removes the directory.
 */
public final class PluginTestRuntime implements AutoCloseable {

    private final Path root;
    private final Path pluginsDir;
    private final PluginJarBuilder jarBuilder = new PluginJarBuilder();
    private final YamlDescriptorParser descriptorParser = new YamlDescriptorParser();
    private final DefaultServiceRegistry serviceRegistry = new DefaultServiceRegistry();
    private final DefaultEventBus eventBus = new DefaultEventBus();
    private final PluginRepository repository;
    private final DefaultPluginManager manager;

    private PluginTestRuntime(final Path root, final Map<Class<?>, Object> platformServices) {
        this.root = root;
        this.pluginsDir = root.resolve("plugins");
        try {
            Files.createDirectories(pluginsDir);
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot create plugin directory " + pluginsDir, e);
        }

        this.repository = new PluginRepository(pluginsDir, descriptorParser);
        final DefaultPluginLifecycleManager lifecycle = new DefaultPluginLifecycleManager(
                new DefaultPluginClassLoaderFactory(),
                ClassLoaderPolicy.defaultPolicy(),
                PluginTestRuntime.class.getClassLoader(),
                serviceRegistry,
                eventBus,
                platformServices);
        this.manager = new DefaultPluginManager(repository, lifecycle, serviceRegistry);
    }

    /**
     * @return a runtime publishing no platform services
     */
    public static PluginTestRuntime create() {
        return create(Map.of());
    }

    /**
     * @param platformServices services plugins can reach through {@code PluginContext}
     * @return a runtime over a fresh temporary directory
     */
    public static PluginTestRuntime create(final Map<Class<?>, Object> platformServices) {
        try {
            return new PluginTestRuntime(Files.createTempDirectory("lpr-runtime-"),
                                         Objects.requireNonNullElse(platformServices, Map.of()));
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot create a temporary plugin runtime", e);
        }
    }

    /**
     * Compiles, packages and lays out a plugin, exactly as a deployment would.
     *
     * @param fixture what to build
     * @return the version directory it was installed into
     */
    public Path install(final PluginFixture fixture) {
        Objects.requireNonNull(fixture, "fixture");
        final Path versionDir = pluginsDir.resolve(fixture.pluginId()).resolve(fixture.version());
        try {
            Files.createDirectories(versionDir);
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot create " + versionDir, e);
        }

        jarBuilder.build(fixture.sources(), versionDir.resolve(PluginRepository.PLUGIN_JAR));
        writeDescriptor(fixture, versionDir.resolve(descriptorParser.descriptorFileName()));
        return versionDir;
    }

    /**
     * Installs a plugin module's own compiled output, descriptor and all.
     * <p>
     * This is how a plugin tests itself: point at its {@code target/classes} and the plugin is laid
     * out exactly as a deployment would have it, including the {@code plugin.yaml} it ships in
     * {@code src/main/resources}. The id and version come from that descriptor rather than from
     * arguments, so a test cannot silently disagree with what the plugin declares about itself.
     * <p>
     * Packaging into a jar rather than putting {@code target/classes} on the classpath is the whole
     * point: on the classpath the plugin's classes are loaded by the application ClassLoader and the
     * test proves nothing about isolation.
     *
     * @param classesDir the module's compiled output, containing the descriptor at its root
     * @return the version directory it was installed into
     * @throws IllegalArgumentException if there is no descriptor there
     */
    public Path installCompiled(final Path classesDir) {
        Objects.requireNonNull(classesDir, "classesDir");
        final Path descriptorFile = classesDir.resolve(descriptorParser.descriptorFileName());
        if (!Files.isRegularFile(descriptorFile)) {
            throw new IllegalArgumentException(
                    "No " + descriptorParser.descriptorFileName() + " in " + classesDir
                    + ". A plugin declares itself in src/main/resources; without it there is nothing "
                    + "to install.");
        }

        final PluginDescriptor descriptor;
        try (InputStream in = Files.newInputStream(descriptorFile)) {
            descriptor = descriptorParser.parse(in, descriptorFile.toString());
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot read " + descriptorFile, e);
        }

        final Path versionDir = pluginsDir.resolve(descriptor.pluginId()).resolve(descriptor.version());
        try {
            Files.createDirectories(versionDir);
            jarBuilder.packageDirectory(classesDir, versionDir.resolve(PluginRepository.PLUGIN_JAR));
            Files.copy(descriptorFile, versionDir.resolve(descriptorParser.descriptorFileName()));
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot install " + classesDir + " into " + versionDir, e);
        }
        return versionDir;
    }

    /**
     * Marks a version so the repository skips it, the way an operator disables a bad build without
     * deleting it.
     *
     * @param pluginId the plugin
     * @param version  the version to disable
     */
    public void disableVersion(final String pluginId, final String version) {
        write(pluginsDir.resolve(pluginId).resolve(version).resolve(PluginRepository.DISABLED_MARKER),
              "disabled by test\n");
    }

    /**
     * @param pluginId the plugin
     * @param version  the version to run
     */
    public void setActiveVersion(final String pluginId, final String version) {
        repository.setActiveVersion(pluginId, version);
    }

    /**
     * Reads the plugin directory. Call after installing and before starting.
     *
     * @return how many plugins were discovered
     */
    public int discover() {
        return manager.discover();
    }

    /**
     * Discovers and starts everything, the way the host does at boot.
     *
     * @return this runtime
     */
    public PluginTestRuntime startAll() {
        manager.discover();
        manager.startAll(java.util.List.of());
        return this;
    }

    public DefaultPluginManager manager() {
        return manager;
    }

    public DefaultServiceRegistry services() {
        return serviceRegistry;
    }

    public DefaultEventBus eventBus() {
        return eventBus;
    }

    public PluginRepository repository() {
        return repository;
    }

    public Path pluginsDirectory() {
        return pluginsDir;
    }

    /**
     * @param pluginId the plugin
     * @return its state, or empty if it was never discovered
     */
    public Optional<PluginState> state(final String pluginId) {
        return manager.state(pluginId);
    }

    /**
     * Looks up a service a plugin published.
     *
     * @param type     the service interface
     * @param pluginId the publishing plugin
     * @param <T>      service type
     * @return the service
     * @throws IllegalStateException if that plugin publishes no such service
     */
    public <T> T service(final Class<T> type, final String pluginId) {
        return serviceRegistry.getService(type, pluginId)
                              .orElseThrow(() -> new IllegalStateException(
                                      "Plugin " + pluginId + " publishes no " + type.getName()
                                      + "; publishers: " + serviceRegistry.getPluginIds(type)));
    }

    @Override
    public void close() {
        try {
            manager.stopAll();
        } finally {
            deleteRecursively(root);
        }
    }

    private void writeDescriptor(final PluginFixture fixture, final Path descriptorFile) {
        final StringBuilder yaml = new StringBuilder()
                .append("id: ").append(fixture.pluginId()).append('\n')
                .append("name: ").append(fixture.name()).append('\n')
                .append("version: ").append(fixture.version()).append('\n')
                .append("entrypoint:\n")
                .append("  class: ").append(fixture.entrypointClass()).append('\n');

        if (!fixture.config().isEmpty()) {
            yaml.append("config:\n");
            // Quoted so that values like 30000 or "true" survive as the strings the plugin declared,
            // rather than being reinterpreted by the YAML scalar rules.
            fixture.config().forEach((key, value) ->
                                             yaml.append("  ").append(key).append(": \"")
                                                 .append(value.replace("\"", "\\\"")).append("\"\n"));
        }
        write(descriptorFile, yaml.toString());
    }

    private void write(final Path file, final String content) {
        try {
            final Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writer.write(content);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    private static void deleteRecursively(final Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> entries = Files.walk(directory)) {
            entries.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (final IOException e) {
                    // A leftover temp directory is not worth failing a test over.
                    path.toFile().deleteOnExit();
                }
            });
        } catch (final IOException e) {
            directory.toFile().deleteOnExit();
        }
    }
}

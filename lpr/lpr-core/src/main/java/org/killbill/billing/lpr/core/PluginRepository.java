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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

import org.killbill.billing.lpr.spi.DescriptorParser;
import org.killbill.billing.lpr.spi.PluginArtifact;
import org.killbill.billing.lpr.spi.PluginDescriptor;
import org.killbill.billing.lpr.spi.SemanticVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the plugin directory and decides which version of each plugin to run.
 *
 * <pre>
 * plugins/
 * └── stripe-payment/
 *     ├── ACTIVE            # active=1.3.0
 *     ├── 1.2.0/
 *     │   ├── plugin.jar
 *     │   ├── plugin.yaml
 *     │   └── lib/          # optional; plugins are usually shaded
 *     └── 1.3.0/
 * </pre>
 *
 * Three things differ from the layout this replaces, each for a reason:
 * <ul>
 *   <li><b>{@code ACTIVE} is a file, not a symlink.</b> The old {@code SET_DEFAULT} symlink meant
 *       deployment state lived in a filesystem feature that behaves differently across platforms,
 *       archives and container image layers. A file with {@code active=1.3.0} survives being
 *       tarred, copied and inspected.</li>
 *   <li><b>Versions are compared numerically.</b> The old ordering used {@code String.compareTo},
 *       so {@code 10.0} sorted below {@code 9.0} and the wrong version would start once a plugin
 *       reached double digits.</li>
 *   <li><b>The jar is named, not guessed.</b> The old code took the first {@code .jar} found in the
 *       directory, which is directory-order dependent. Here {@code plugin.jar} wins, a single jar
 *       of any name is accepted, and anything ambiguous is rejected rather than resolved by luck.</li>
 * </ul>
 */
public class PluginRepository {

    private static final Logger log = LoggerFactory.getLogger(PluginRepository.class);

    /** Names the version to run. Absent means "the highest installed version". */
    public static final String ACTIVE_MARKER = "ACTIVE";
    public static final String ACTIVE_KEY = "active";
    public static final String PREVIOUS_KEY = "previous";

    /** Present in a version directory, that version is skipped. Survives a restart, unlike state in memory. */
    public static final String DISABLED_MARKER = "DISABLED";

    /** The conventional jar name; any single jar is accepted as a fallback. */
    public static final String PLUGIN_JAR = "plugin.jar";

    public static final String LIB_DIRECTORY = "lib";

    private final Path root;
    private final DescriptorParser descriptorParser;

    /**
     * @param root             directory holding one subdirectory per plugin
     * @param descriptorParser reads each version's descriptor
     */
    public PluginRepository(final Path root, final DescriptorParser descriptorParser) {
        this.root = Objects.requireNonNull(root, "root");
        this.descriptorParser = Objects.requireNonNull(descriptorParser, "descriptorParser");
    }

    public Path root() {
        return root;
    }

    /**
     * Scans the repository.
     * <p>
     * A plugin that cannot be read is logged and skipped rather than failing the scan: one
     * malformed descriptor must not stop every other plugin in the deployment from starting.
     * Plugins that are genuinely required are enforced separately, by name.
     *
     * @return one runtime per plugin, at the version that should run
     */
    public List<PluginRuntime> scan() {
        if (!Files.isDirectory(root)) {
            log.info("Plugin directory {} does not exist; no plugins will be loaded", root);
            return List.of();
        }

        final List<PluginRuntime> found = new ArrayList<>();
        try (Stream<Path> pluginDirs = Files.list(root)) {
            for (final Path pluginDir : pluginDirs.filter(Files::isDirectory).sorted().toList()) {
                try {
                    resolve(pluginDir).ifPresent(found::add);
                } catch (final RuntimeException e) {
                    log.error("Skipping plugin directory {}: {}", pluginDir.getFileName(), e.getMessage(), e);
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot list plugin directory " + root, e);
        }
        return List.copyOf(found);
    }

    /**
     * Resolves one plugin directory to the version that should run.
     *
     * @param pluginDir a directory under {@link #root()}
     * @return the runtime, or empty if the plugin has no runnable version
     */
    public Optional<PluginRuntime> resolve(final Path pluginDir) {
        final Path versionDir = selectVersionDirectory(pluginDir).orElse(null);
        if (versionDir == null) {
            log.warn("Plugin {} has no enabled version installed", pluginDir.getFileName());
            return Optional.empty();
        }
        return Optional.of(load(pluginDir, versionDir));
    }

    /**
     * Loads a specific version, bypassing {@code ACTIVE}. Used when starting a candidate version
     * alongside the running one during an upgrade.
     *
     * @param pluginId the plugin
     * @param version  the version directory name
     * @return the runtime for that version
     */
    public PluginRuntime loadVersion(final String pluginId, final String version) {
        final Path pluginDir = root.resolve(pluginId);
        final Path versionDir = pluginDir.resolve(version);
        if (!Files.isDirectory(versionDir)) {
            throw new IllegalArgumentException("Plugin " + pluginId + " has no version " + version
                                               + " installed at " + versionDir);
        }
        return load(pluginDir, versionDir);
    }

    /**
     * Records which version should run from now on.
     * <p>
     * The version being replaced is kept as {@code previous}, so a rollback does not have to guess
     * what was running before.
     *
     * @param pluginId the plugin
     * @param version  the version to activate
     */
    public void setActiveVersion(final String pluginId, final String version) {
        final Path pluginDir = root.resolve(Objects.requireNonNull(pluginId, "pluginId"));
        if (!Files.isDirectory(pluginDir.resolve(version))) {
            throw new IllegalArgumentException("Cannot activate version " + version + " of plugin "
                                               + pluginId + ": it is not installed");
        }

        final Properties marker = new Properties();
        readActiveMarker(pluginDir).ifPresent(previous -> marker.setProperty(PREVIOUS_KEY, previous));
        marker.setProperty(ACTIVE_KEY, version);

        final Path markerFile = pluginDir.resolve(ACTIVE_MARKER);
        try (var writer = Files.newBufferedWriter(markerFile, StandardCharsets.UTF_8)) {
            marker.store(writer, "Managed by LPR. 'active' names the version to run.");
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot write " + markerFile, e);
        }
        log.info("Plugin {} will run version {}", pluginId, version);
    }

    /**
     * @param pluginId the plugin
     * @return the version that ran before the current one, if any
     */
    public Optional<String> previousVersion(final String pluginId) {
        final Path markerFile = root.resolve(pluginId).resolve(ACTIVE_MARKER);
        return readProperties(markerFile).map(p -> p.getProperty(PREVIOUS_KEY));
    }

    /**
     * @param pluginId the plugin
     * @return every installed version, highest first
     */
    public List<String> installedVersions(final String pluginId) {
        return versionDirectories(root.resolve(pluginId)).stream()
                                                         .map(dir -> dir.getFileName().toString())
                                                         .toList();
    }

    private PluginRuntime load(final Path pluginDir, final Path versionDir) {
        final PluginDescriptor descriptor = readDescriptor(versionDir);
        final String directoryName = fileNameOf(pluginDir);

        if (!descriptor.pluginId().equals(directoryName)) {
            // Otherwise a plugin could be started under one name and register services under
            // another, and every lookup by configured name would quietly miss.
            throw new IllegalArgumentException("Plugin in directory '" + directoryName
                                               + "' declares id '" + descriptor.pluginId()
                                               + "'; the directory must be named after the plugin id");
        }
        final String versionName = fileNameOf(versionDir);
        if (!descriptor.version().equals(versionName)) {
            throw new IllegalArgumentException("Plugin " + descriptor.pluginId() + " in directory '"
                                               + versionName + "' declares version '" + descriptor.version() + "'");
        }

        return new PluginRuntime(descriptor,
                                 new PluginArtifact(descriptor.pluginId(),
                                                    descriptor.version(),
                                                    resolveJar(versionDir, descriptor.pluginId()),
                                                    resolveLibraries(versionDir)));
    }

    /**
     * {@code Path.getFileName()} returns null for a root path. That cannot happen for a plugin
     * directory, but saying so explicitly beats a NullPointerException that would blame the wrong
     * line if the repository were ever pointed somewhere unexpected.
     */
    private static String fileNameOf(final Path path) {
        final Path fileName = path.getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("Not a plugin directory: " + path);
        }
        return fileName.toString();
    }

    private PluginDescriptor readDescriptor(final Path versionDir) {
        final Path descriptorFile = versionDir.resolve(descriptorParser.descriptorFileName());
        if (!Files.isRegularFile(descriptorFile)) {
            throw new IllegalArgumentException("Missing " + descriptorParser.descriptorFileName() + " in " + versionDir);
        }
        try (InputStream input = Files.newInputStream(descriptorFile)) {
            return descriptorParser.parse(input, descriptorFile.toString());
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot read " + descriptorFile, e);
        }
    }

    private Path resolveJar(final Path versionDir, final String pluginId) {
        final Path conventional = versionDir.resolve(PLUGIN_JAR);
        if (Files.isRegularFile(conventional)) {
            return conventional;
        }
        final List<Path> jars = listMatching(versionDir, path -> path.getFileName().toString().endsWith(".jar"));
        if (jars.size() == 1) {
            return jars.get(0);
        }
        if (jars.isEmpty()) {
            throw new IllegalArgumentException("Plugin " + pluginId + " has no jar in " + versionDir);
        }
        throw new IllegalArgumentException("Plugin " + pluginId + " has " + jars.size() + " jars in " + versionDir
                                           + "; name the plugin's own jar '" + PLUGIN_JAR
                                           + "' or move its dependencies into '" + LIB_DIRECTORY + "/'");
    }

    private List<Path> resolveLibraries(final Path versionDir) {
        final Path libDir = versionDir.resolve(LIB_DIRECTORY);
        if (!Files.isDirectory(libDir)) {
            return List.of();
        }
        return listMatching(libDir, path -> path.getFileName().toString().endsWith(".jar"));
    }

    /**
     * {@code ACTIVE} wins when it names an installed, enabled version. Otherwise the highest
     * version runs, so that dropping a directory in and restarting does the obvious thing.
     */
    private Optional<Path> selectVersionDirectory(final Path pluginDir) {
        final List<Path> candidates = versionDirectories(pluginDir);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        final Optional<String> requested = readActiveMarker(pluginDir);
        if (requested.isPresent()) {
            final Optional<Path> match = candidates.stream()
                                                   .filter(dir -> dir.getFileName().toString().equals(requested.get()))
                                                   .findFirst();
            if (match.isPresent()) {
                return match;
            }
            log.warn("Plugin {} has {} pointing at version {}, which is not installed or is disabled; "
                     + "falling back to the highest installed version",
                     pluginDir.getFileName(), ACTIVE_MARKER, requested.get());
        }
        return Optional.of(candidates.get(0));
    }

    /**
     * Enabled version directories, highest version first. Directories whose names are not versions
     * are ignored rather than rejected, so {@code ACTIVE}, backups and editor droppings can sit
     * alongside without breaking the scan.
     */
    private List<Path> versionDirectories(final Path pluginDir) {
        if (!Files.isDirectory(pluginDir)) {
            return List.of();
        }
        return listMatching(pluginDir, Files::isDirectory).stream()
                .filter(dir -> SemanticVersion.isValid(dir.getFileName().toString()))
                .filter(dir -> {
                    final boolean disabled = Files.exists(dir.resolve(DISABLED_MARKER));
                    if (disabled) {
                        log.info("Skipping disabled version {} of plugin {}",
                                 dir.getFileName(), pluginDir.getFileName());
                    }
                    return !disabled;
                })
                .sorted(Comparator.comparing((Path dir) -> SemanticVersion.parse(dir.getFileName().toString()))
                                  .reversed())
                .toList();
    }

    private Optional<String> readActiveMarker(final Path pluginDir) {
        return readProperties(pluginDir.resolve(ACTIVE_MARKER)).map(p -> p.getProperty(ACTIVE_KEY));
    }

    private Optional<Properties> readProperties(final Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            final Properties properties = new Properties();
            properties.load(reader);
            return Optional.of(properties);
        } catch (final IOException e) {
            log.warn("Cannot read {}; treating it as absent", file, e);
            return Optional.empty();
        }
    }

    private List<Path> listMatching(final Path directory, final java.util.function.Predicate<Path> filter) {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(filter).sorted().toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot list " + directory, e);
        }
    }
}

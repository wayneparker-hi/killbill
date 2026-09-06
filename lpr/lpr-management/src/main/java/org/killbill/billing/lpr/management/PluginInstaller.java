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

package org.killbill.billing.lpr.management;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import org.killbill.billing.lpr.core.PluginRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Puts a plugin version on disk in the layout the runtime reads, and takes it off again.
 * <p>
 * This is the half of KPM worth keeping. The other 3,500 lines resolved coordinates against Kill
 * Bill's hosted plugin directory -- a catalogue lookup, not an installer -- and are better expressed
 * as whatever produces an {@link ArtifactSource}.
 *
 * <h2>Installing is not starting</h2>
 * {@link #install} lays out the files and stops. Making a version the one that runs is
 * {@code PluginRepository.setActiveVersion}, and running it is the lifecycle's job. Keeping the
 * three separate is what makes a staged upgrade possible: install 1.3.0 while 1.2.0 is serving,
 * activate it, and roll back if it misbehaves.
 *
 * <h2>Writes are staged</h2>
 * Files land in a temporary directory beside the target and are moved into place only once the
 * download and checksum have both succeeded. A failed install therefore leaves nothing behind for
 * the next scan to trip over -- which matters because the runtime discovers plugins by reading this
 * directory, and a half-written version directory is indistinguishable from a real one.
 */
public class PluginInstaller {

    private static final Logger log = LoggerFactory.getLogger(PluginInstaller.class);

    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    /** The descriptor file name, both inside a plugin jar and beside it once installed. */
    private static final String DESCRIPTOR = "plugin.yaml";

    private final PluginRepository repository;
    private final HttpClient httpClient;

    public PluginInstaller(final PluginRepository repository) {
        this(repository,
             HttpClient.newBuilder()
                       .connectTimeout(Duration.ofSeconds(30))
                       .followRedirects(HttpClient.Redirect.NORMAL)
                       .build());
    }

    public PluginInstaller(final PluginRepository repository, final HttpClient httpClient) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    /**
     * Installs one version of a plugin.
     * <p>
     * Refuses to overwrite a version that is already installed. Re-installing the same coordinates
     * over a running plugin would swap the jar underneath a live ClassLoader, and the symptom
     * (classes that load fine until the first one that is loaded lazily) appears far from the cause.
     * Uninstall first, or install under a new version.
     *
     * @param pluginId   the plugin's stable id
     * @param version    the version being installed
     * @param source     where to fetch the jar from
     * @param descriptor the {@code plugin.yaml} to write alongside the jar, or null to use the one
     *                   the jar carries
     * @return the version directory that was created
     * @throws IllegalStateException if that version is already installed, or if no descriptor is
     *                               given and the jar does not carry one
     */
    public Path install(final String pluginId,
                        final String version,
                        final ArtifactSource source,
                        final String descriptor) {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(source, "source");

        final Path pluginDir = repository.root().resolve(pluginId);
        final Path target = pluginDir.resolve(version);
        if (Files.exists(target)) {
            throw new IllegalStateException(
                    "Plugin " + pluginId + " version " + version + " is already installed at " + target
                    + ". Uninstall it first rather than overwriting a jar that may be in use.");
        }

        Path staging = null;
        try {
            Files.createDirectories(pluginDir);
            staging = Files.createTempDirectory(pluginDir, version + ".incoming-");

            final byte[] jar = fetch(source);
            verify(source, jar);

            final String resolvedDescriptor = descriptor != null ? descriptor : descriptorInside(jar);
            if (resolvedDescriptor == null) {
                throw new IllegalStateException(
                        "Cannot install " + pluginId + " " + version + ": the jar carries no "
                        + DESCRIPTOR + " and none was supplied. A plugin built by this project puts "
                        + "one in src/main/resources.");
            }

            Files.write(staging.resolve(PluginRepository.PLUGIN_JAR), jar);
            Files.writeString(staging.resolve(DESCRIPTOR), resolvedDescriptor, StandardCharsets.UTF_8);

            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            staging = null;

            log.info("Installed plugin {} version {} from {} into {}", pluginId, version, source.uri(), target);
            return target;

        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to install plugin " + pluginId + " version " + version, e);
        } finally {
            deleteQuietly(staging);
        }
    }

    /**
     * Removes one version from disk.
     * <p>
     * Does not stop the plugin: a running version holds open file handles and a ClassLoader, and
     * deleting its jar while it serves traffic is the thing this class must not silently do. Stop it
     * through the lifecycle first. On a running system the safe order is stop, then uninstall.
     *
     * @param pluginId the plugin
     * @param version  the version to remove
     * @return true if something was removed
     */
    public boolean uninstall(final String pluginId, final String version) {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(version, "version");

        final Path pluginDir = repository.root().resolve(pluginId);
        final Path target = pluginDir.resolve(version);
        if (!Files.isDirectory(target)) {
            return false;
        }
        deleteRecursively(target);
        log.info("Uninstalled plugin {} version {}", pluginId, version);

        // A plugin directory holding only bookkeeping is noise for the next scan.
        if (remainingVersions(pluginDir).isEmpty()) {
            deleteRecursively(pluginDir);
            log.info("Removed plugin {} entirely: no versions remain", pluginId);
        }
        return true;
    }

    private List<Path> remainingVersions(final Path pluginDir) {
        if (!Files.isDirectory(pluginDir)) {
            return List.of();
        }
        try (var entries = Files.list(pluginDir)) {
            return entries.filter(Files::isDirectory).toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to list " + pluginDir, e);
        }
    }

    /**
     * Reads the descriptor a plugin ships inside its own jar.
     * <p>
     * The repository reads {@code plugin.yaml} from beside the jar rather than from within it -- so
     * that an operator can correct a descriptor without repackaging -- but the plugin is still the
     * authority on its own entry point. Copying the jar's copy out on install means an artifact
     * built by this project installs correctly with no extra input, which is the common case.
     *
     * @param jar the fetched jar
     * @return the descriptor, or null if the jar does not carry one
     */
    private static String descriptorInside(final byte[] jar) throws IOException {
        try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(jar))) {
            JarEntry entry;
            while ((entry = in.getNextJarEntry()) != null) {
                if (DESCRIPTOR.equals(entry.getName())) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    private byte[] fetch(final ArtifactSource source) throws IOException {
        final URI uri = source.uri();
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return Files.readAllBytes(Path.of(uri));
        }
        try {
            final HttpRequest request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET().build();
            final HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("Fetching " + uri + " returned HTTP " + response.statusCode());
            }
            try (InputStream body = response.body()) {
                return body.readAllBytes();
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + uri, e);
        }
    }

    private void verify(final ArtifactSource source, final byte[] jar) throws IOException {
        if (source.sha1().isEmpty()) {
            return;
        }
        final String expected = source.sha1().get().trim().toLowerCase(java.util.Locale.ROOT);
        final String actual;
        try {
            actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(jar));
        } catch (final NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 is unavailable in this JVM", e);
        }
        if (!expected.equals(actual)) {
            throw new IOException("Checksum mismatch for " + source.uri()
                                  + ": expected " + expected + " but got " + actual);
        }
    }

    private static void deleteQuietly(final Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            deleteRecursively(path);
        } catch (final RuntimeException e) {
            log.warn("Failed to clean up {}", path, e);
        }
    }

    private static void deleteRecursively(final Path path) {
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (final IOException e) {
                    throw new UncheckedIOException("Failed to delete " + p, e);
                }
            });
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to delete " + path, e);
        }
    }
}

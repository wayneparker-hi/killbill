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
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import org.killbill.billing.lpr.spi.PluginRepository;
import org.killbill.billing.lpr.spi.PluginInstallAuditSink;
import org.killbill.billing.lpr.spi.PluginInstallAudit.Stage;
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
    private final ArtifactVerificationPolicy policy;
    private final PluginInstallAuditSink auditSink;

    public PluginInstaller(final PluginRepository repository) {
        this(repository, ArtifactVerificationPolicy.optional(), event -> log.info("Plugin install audit {}", event));
    }

    public PluginInstaller(final PluginRepository repository, final ArtifactVerificationPolicy policy,
                           final PluginInstallAuditSink auditSink) {
        this(repository, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL).build(), policy, auditSink);
    }

    public PluginInstaller(final PluginRepository repository, final HttpClient httpClient) {
        this(repository, httpClient, ArtifactVerificationPolicy.optional(), event -> log.info("Plugin install audit {}", event));
    }

    public PluginInstaller(final PluginRepository repository, final HttpClient httpClient,
                           final ArtifactVerificationPolicy policy, final PluginInstallAuditSink auditSink) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
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
        if (!pluginId.matches("[a-z0-9][a-z0-9.-]*")) { throw new IllegalArgumentException("Invalid plugin id"); }
        org.killbill.billing.lpr.spi.SemanticVersion.parse(version);

        final InstallAuditOperation audit = new InstallAuditOperation(auditSink, pluginId, version, source, policy.requireSignature());
        return installVerified(pluginId, version, source, descriptor, audit);
    }

    private Path installVerified(final String pluginId, final String version, final ArtifactSource source,
                                 final String descriptor, final InstallAuditOperation audit) {
        String phase = "START_AUDIT";
        boolean published = false;
        try {
            audit.record(Stage.STARTED, null);
            phase = "FETCH";
            final FetchedArtifact fetched = fetch(source);
            final byte[] jar = fetched.bytes();
            audit.downloaded(jar, fetched.uri());
            phase = "CHECKSUM";
            verify(source, jar);
            phase = "SIGNATURE_OR_DESCRIPTOR";
            final VerifiedArtifact verified = resolveDescriptor(jar, descriptor);
            audit.verified(verified.descriptor(), verified.signers());
            phase = "VERIFIED_AUDIT";
            audit.record(Stage.VERIFIED, null);
            phase = "PUBLISH";
            final Path installed = repository.publish(pluginId, version, jar, verified.descriptor());
            published = true;
            phase = "RESULT_AUDIT";
            audit.record(Stage.PUBLISHED, null);
            log.info("Installed plugin {} version {} into {}", pluginId, version, installed);
            return installed;
        } catch (final IOException | RuntimeException failure) {
            try { audit.record(Stage.FAILED, phase); }
            catch (final IOException | RuntimeException auditFailure) { failure.addSuppressed(auditFailure); }
            if (published) {
                throw new IllegalStateException("Plugin artifact is published, but its result audit failed", failure);
            }
            if (failure instanceof IOException io) { throw new UncheckedIOException("Plugin installation failed at " + phase, io); }
            throw (RuntimeException) failure;
        }
    }

    private record VerifiedArtifact(String descriptor, List<String> signers) { }

    private VerifiedArtifact resolveDescriptor(final byte[] jar, final String external) throws IOException {
        if (policy.requireSignature()) {
            final var signed = new JarSignatureVerifier(policy).verify(jar);
            if (external != null && !external.equals(signed.descriptor())) {
                throw new IOException("External descriptor differs from signed plugin.yaml");
            }
            return new VerifiedArtifact(signed.descriptor(), signed.signers());
        }
        final String descriptor = external != null ? external : descriptorInside(jar);
        if (descriptor == null) { throw new IOException("Missing plugin.yaml"); }
        return new VerifiedArtifact(descriptor, List.of());
    }

    /** Removes storage only; the caller must stop any loaded local version first. */
    public boolean uninstall(final String pluginId, final String version) {
        final boolean removed = repository.remove(pluginId, version);
        if (removed) { log.info("Uninstalled plugin {} version {}", pluginId, version); }
        return removed;
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
    private String descriptorInside(final byte[] jar) throws IOException {
        try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(jar))) {
            JarEntry entry;
            while ((entry = in.getNextJarEntry()) != null) {
                if (DESCRIPTOR.equals(entry.getName())) {
                    return new String(ArtifactStreams.read(in, 1024 * 1024), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    private record FetchedArtifact(byte[] bytes, URI uri) { }

    private FetchedArtifact fetch(final ArtifactSource source) throws IOException {
        final URI uri = source.uri();
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            try (InputStream input = Files.newInputStream(Path.of(uri))) {
                return new FetchedArtifact(ArtifactStreams.read(input, policy.maxArtifactBytes()), uri);
            }
        }
        try {
            final HttpRequest request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET().build();
            final HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() / 100 != 2) { throw new IOException("Artifact HTTP status " + response.statusCode()); }
                return new FetchedArtifact(ArtifactStreams.read(body, policy.maxArtifactBytes()), response.uri());
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching artifact", e);
        }
    }

    private void verify(final ArtifactSource source, final byte[] jar) throws IOException {
        if (source.sha256().isEmpty()) {
            if (policy.requireSha256()) { throw new IOException("SHA-256 is required by installation policy"); }
            return;
        }
        if (!source.sha256().orElseThrow().equals(ArtifactDigests.sha256(jar))) {
            throw new IOException("SHA-256 checksum mismatch");
        }
    }
}

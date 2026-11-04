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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.killbill.billing.lpr.descriptor.yaml.YamlDescriptorParser;
import org.killbill.billing.lpr.repository.filesystem.FileSystemPluginInstallAudit;
import org.killbill.billing.lpr.repository.filesystem.FileSystemPluginRepository;
import org.killbill.billing.lpr.spi.PluginInstallAudit;
import org.killbill.billing.lpr.spi.PluginInstallAudit.Stage;
import org.killbill.billing.lpr.spi.PluginInstallAuditSink;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class TestPluginInstallAudit {
    private static final String DESCRIPTOR = "id: demo\nversion: 1.0.0\nentrypoint: example.Demo\n";

    @Test(groups = "fast")
    public void testChecksumFormatAndSha256Requirement() throws Exception {
        try (Fixture f = new Fixture()) {
            for (final String malformed : List.of("", "a".repeat(40), "z".repeat(64))) {
                assertThrows(IllegalArgumentException.class, () -> ArtifactSource.of(f.artifact).withSha256(malformed));
            }
            final var policy = new ArtifactVerificationPolicy(true, false, List.of());
            final var installer = new PluginInstaller(f.repository, policy, f.events::add);
            assertThrows(UncheckedIOException.class, () -> installer.install("demo", "1.0.0", ArtifactSource.of(f.artifact), DESCRIPTOR));
            assertFalse(Files.exists(f.root.resolve("repository/demo/1.0.0")));
            final String digest = ArtifactDigests.sha256(Files.readAllBytes(f.artifact));
            installer.install("demo", "1.0.0", ArtifactSource.of(f.artifact).withSha256("  " + digest.toUpperCase(java.util.Locale.ROOT) + " "), DESCRIPTOR);
            assertEquals(f.events.get(f.events.size() - 1).actualSha256(), digest);
        }
    }

    @Test(groups = "fast")
    public void testSuccessfulAuditPersistsAfterUninstall() throws Exception {
        try (Fixture f = new Fixture()) {
            final Path auditDir = f.root.resolve("audit");
            final var installer = new PluginInstaller(f.repository, ArtifactVerificationPolicy.optional(), new FileSystemPluginInstallAudit(auditDir));
            installer.install("demo", "1.0.0", ArtifactSource.of(f.artifact), DESCRIPTOR);
            installer.uninstall("demo", "1.0.0");
            final List<Properties> records = records(auditDir);
            assertEquals(records.size(), 3);
            assertEquals(records.stream().map(p -> p.getProperty("operationId")).distinct().count(), 1L);
            assertEquals(records.stream().map(p -> p.getProperty("stage")).sorted().toList(), List.of("PUBLISHED", "STARTED", "VERIFIED"));
            final var verified = records.stream().filter(p -> p.getProperty("stage").equals("VERIFIED")).findFirst().orElseThrow();
            assertEquals(verified.getProperty("actualSha256"), ArtifactDigests.sha256(Files.readAllBytes(f.artifact)));
            assertEquals(verified.getProperty("descriptorSha256"), ArtifactDigests.sha256(DESCRIPTOR.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals(URI.create(verified.getProperty("source")), f.artifact.toUri());
        }
    }

    @Test(groups = "fast")
    public void testFailedChecksumAuditIncludesActualDigestAndNoPublishedEvent() throws Exception {
        try (Fixture f = new Fixture()) {
            final var installer = new PluginInstaller(f.repository, ArtifactVerificationPolicy.optional(), f.events::add);
            assertThrows(UncheckedIOException.class, () -> installer.install("demo", "1.0.0",
                    ArtifactSource.of(f.artifact).withSha256("0".repeat(64)), DESCRIPTOR));
            assertEquals(f.events.stream().map(PluginInstallAudit::stage).toList(), List.of(Stage.STARTED, Stage.FAILED));
            assertEquals(f.events.get(1).actualSha256(), ArtifactDigests.sha256(Files.readAllBytes(f.artifact)));
            assertEquals(f.events.get(1).failureCode(), "CHECKSUM");
        }
    }

    @Test(groups = "fast")
    public void testAuditFailureBeforePublishPreventsInstallation() throws Exception {
        for (final Stage failureAt : List.of(Stage.STARTED, Stage.VERIFIED)) {
            try (Fixture f = new Fixture()) {
                final PluginInstallAuditSink sink = event -> {
                    f.events.add(event);
                    if (event.stage() == failureAt) { throw new IOException("unavailable"); }
                };
                final var installer = new PluginInstaller(f.repository, ArtifactVerificationPolicy.optional(), sink);
                assertThrows(UncheckedIOException.class, () -> installer.install("demo", "1.0.0", ArtifactSource.of(f.artifact), DESCRIPTOR));
                assertFalse(Files.exists(f.root.resolve("repository/demo/1.0.0")));
                assertFalse(f.events.stream().anyMatch(e -> e.stage() == Stage.PUBLISHED));
            }
        }
    }

    @Test(groups = "fast")
    public void testAuditFailureAfterPublishReportsArtifactWasPublished() throws Exception {
        try (Fixture f = new Fixture()) {
            final var installer = new PluginInstaller(f.repository, ArtifactVerificationPolicy.optional(), event -> {
                f.events.add(event); if (event.stage() == Stage.PUBLISHED) { throw new IOException("unavailable"); }
            });
            final var error = expectThrows(IllegalStateException.class,
                    () -> installer.install("demo", "1.0.0", ArtifactSource.of(f.artifact), DESCRIPTOR));
            assertTrue(error.getMessage().contains("is published"));
            assertTrue(Files.exists(f.root.resolve("repository/demo/1.0.0/plugin.jar")));
            assertEquals(f.events.get(f.events.size() - 1).failureCode(), "RESULT_AUDIT");
        }
    }

    @Test(groups = "fast")
    public void testHttpRedirectAuditTracksResolvedSourceAndRedactsQueries() throws Exception {
        try (Fixture f = new Fixture()) {
            final var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/redirect", exchange -> {
                exchange.getResponseHeaders().add("Location", "/actual?credential=secret");
                exchange.sendResponseHeaders(302, -1); exchange.close();
            });
            server.createContext("/actual", exchange -> {
                final byte[] bytes = "payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start();
            try {
                final String host = "http://127.0.0.1:" + server.getAddress().getPort();
                final var installer = new PluginInstaller(f.repository, ArtifactVerificationPolicy.optional(), f.events::add);
                installer.install("demo", "1.0.0", ArtifactSource.of(URI.create(host + "/redirect?token=secret")), DESCRIPTOR);
                final var event = f.events.get(f.events.size() - 1);
                assertEquals(event.source(), host + "/redirect");
                assertEquals(event.resolvedSource(), host + "/actual");
                assertFalse(event.toString().contains("secret"));
            } finally { server.stop(0); }
        }
    }

    @Test(groups = "fast")
    public void testSensitiveSourceIsRedactedEvenOnFetchFailure() throws Exception {
        try (Fixture f = new Fixture()) {
            final var source = ArtifactSource.of(URI.create("unsupported://user:password@example.invalid/plugin.jar?token=secret#credential"));
            final var installer = new PluginInstaller(f.repository, ArtifactVerificationPolicy.optional(), f.events::add);
            assertThrows(RuntimeException.class, () -> installer.install("demo", "1.0.0", source, DESCRIPTOR));
            assertEquals(f.events.size(), 2);
            for (final var event : f.events) {
                assertEquals(event.source(), "unsupported://example.invalid/plugin.jar");
                assertFalse(event.toString().contains("password"));
                assertFalse(event.toString().contains("secret"));
                assertFalse(event.toString().contains("credential"));
            }
        }
    }

    @Test(groups = "fast")
    public void testFileAndHttpArtifactLimitsPreventPublication() throws Exception {
        try (Fixture f = new Fixture()) {
            final var policy = new ArtifactVerificationPolicy(false, false, List.of(), 6, 1024);
            final var installer = new PluginInstaller(f.repository, policy, f.events::add);
            assertThrows(UncheckedIOException.class, () -> installer.install("demo", "1.0.0", ArtifactSource.of(f.artifact), DESCRIPTOR));
            final var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/large", exchange -> {
                exchange.sendResponseHeaders(200, 0); // Chunked: no Content-Length to rely on.
                try (var output = exchange.getResponseBody()) { output.write(new byte[32]); }
            });
            server.start();
            try {
                final URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/large");
                assertThrows(UncheckedIOException.class, () -> installer.install("demo", "1.0.0", ArtifactSource.of(uri), DESCRIPTOR));
            } finally { server.stop(0); }
            assertFalse(Files.exists(f.root.resolve("repository/demo/1.0.0")));
            assertEquals(f.events.stream().filter(e -> e.stage() == Stage.FAILED).count(), 2L);
            assertTrue(f.events.stream().filter(e -> e.stage() == Stage.FAILED).allMatch(e -> "FETCH".equals(e.failureCode())));
            // Exact boundary is allowed.
            Files.write(f.artifact, new byte[6]);
            installer.install("demo", "1.0.0", ArtifactSource.of(f.artifact), DESCRIPTOR);
        }
    }

    private static List<Properties> records(final Path directory) throws Exception {
        final List<Properties> result = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            for (final Path path : paths.toList()) {
                assertTrue(path.getFileName().toString().endsWith(".properties"));
                final Properties record = new Properties();
                try (var reader = Files.newBufferedReader(path)) { record.load(reader); }
                result.add(record);
            }
        }
        return result;
    }

    private static final class Fixture implements AutoCloseable {
        final Path root = Files.createTempDirectory("lpr-install-audit-");
        final Path artifact = Files.writeString(root.resolve("artifact.jar"), "payload");
        final FileSystemPluginRepository repository = new FileSystemPluginRepository(root.resolve("repository"), new YamlDescriptorParser());
        final List<PluginInstallAudit> events = new ArrayList<>();
        Fixture() throws Exception { }
        @Override public void close() throws Exception {
            try (var paths = Files.walk(root)) {
                for (final Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
            }
        }
    }
}

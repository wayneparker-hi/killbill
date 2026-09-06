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

import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.killbill.billing.lpr.core.PluginRepository;
import org.killbill.billing.lpr.descriptor.yaml.YamlDescriptorParser;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Installing is a filesystem operation that the runtime reads back by scanning, so the properties
 * worth asserting are about what is left on disk -- including after a failure.
 */
public class TestPluginInstaller {

    private static final String PLUGIN_ID = "hello-world";
    private static final String VERSION = "1.0.0";
    private static final String DESCRIPTOR = "id: hello-world\nversion: 1.0.0\n";

    private Path root;
    private PluginRepository repository;
    private PluginInstaller installer;

    @BeforeMethod(groups = "fast")
    public void beforeMethod() throws Exception {
        root = Files.createTempDirectory("lpr-installer-");
        repository = new PluginRepository(root, new YamlDescriptorParser());
        installer = new PluginInstaller(repository);
    }

    @Test(groups = "fast")
    public void testInstallLaysOutTheVersionWhereTheRepositoryLooksForIt() throws Exception {
        final Path jar = someJar("payload");

        final Path installed = installer.install(PLUGIN_ID, VERSION, ArtifactSource.of(jar), DESCRIPTOR);

        assertEquals(installed, root.resolve(PLUGIN_ID).resolve(VERSION));
        assertEquals(Files.readString(installed.resolve(PluginRepository.PLUGIN_JAR)), "payload");
        assertEquals(Files.readString(installed.resolve("plugin.yaml")), DESCRIPTOR);

        // The point of the layout: the repository can now see it without being told.
        assertEquals(repository.installedVersions(PLUGIN_ID), List.of(VERSION));
    }

    /**
     * Overwriting a version that may be loaded would swap a jar underneath a live ClassLoader, whose
     * symptom appears much later and far away.
     */
    @Test(groups = "fast")
    public void testInstallRefusesToOverwriteAnInstalledVersion() throws Exception {
        final Path jar = someJar("payload");
        installer.install(PLUGIN_ID, VERSION, ArtifactSource.of(jar), DESCRIPTOR);

        final IllegalStateException e = expectThrows(IllegalStateException.class,
                                                     () -> installer.install(PLUGIN_ID, VERSION,
                                                                             ArtifactSource.of(jar), DESCRIPTOR));
        assertTrue(e.getMessage().contains("already installed"), e.getMessage());
    }

    /**
     * The property that makes a failed install safe: the runtime discovers plugins by listing this
     * directory, so a half-written version directory would be indistinguishable from a real one.
     */
    @Test(groups = "fast")
    public void testAFailedInstallLeavesNothingBehind() throws Exception {
        final Path jar = someJar("payload");
        final ArtifactSource wrongChecksum = ArtifactSource.of(jar).withSha1("0".repeat(40));

        expectThrows(UncheckedIOException.class,
                     () -> installer.install(PLUGIN_ID, VERSION, wrongChecksum, DESCRIPTOR));

        assertFalse(Files.exists(root.resolve(PLUGIN_ID).resolve(VERSION)),
                    "The version directory must not exist after a failed install");
        assertTrue(repository.installedVersions(PLUGIN_ID).isEmpty(),
                   "A failed install must not leave a version the repository can find");
        assertTrue(stagingDirectories().isEmpty(), "Staging directories must be cleaned up: " + stagingDirectories());
    }

    @Test(groups = "fast")
    public void testInstallAcceptsAMatchingChecksum() throws Exception {
        // SHA-1 of "payload"
        final Path jar = someJar("payload");
        final String sha1 = sha1Of("payload");

        installer.install(PLUGIN_ID, VERSION, ArtifactSource.of(jar).withSha1(sha1), DESCRIPTOR);

        assertEquals(repository.installedVersions(PLUGIN_ID), List.of(VERSION));
    }

    @Test(groups = "fast")
    public void testUninstallRemovesTheVersionAndThenThePluginWhenItWasTheLast() throws Exception {
        installer.install(PLUGIN_ID, VERSION, ArtifactSource.of(someJar("v1")), DESCRIPTOR);
        installer.install(PLUGIN_ID, "1.1.0", ArtifactSource.of(someJar("v2")),
                          "id: hello-world\nversion: 1.1.0\n");

        assertTrue(installer.uninstall(PLUGIN_ID, "1.1.0"));
        assertEquals(repository.installedVersions(PLUGIN_ID), List.of(VERSION),
                     "Removing one version must leave the others alone");
        assertTrue(Files.isDirectory(root.resolve(PLUGIN_ID)));

        assertTrue(installer.uninstall(PLUGIN_ID, VERSION));
        assertFalse(Files.exists(root.resolve(PLUGIN_ID)),
                    "An empty plugin directory is noise for the next scan and should go too");
    }

    @Test(groups = "fast")
    public void testUninstallingSomethingThatIsNotThereIsNotAnError() {
        assertFalse(installer.uninstall(PLUGIN_ID, VERSION));
    }

    /**
     * Coordinates are a convenience over a URI, not a second mechanism -- so the assertion is simply
     * that they produce the URI a Maven repository serves.
     */
    @Test(groups = "fast")
    public void testMavenCoordinatesResolveToTheReleaseLayout() {
        final ArtifactSource source = ArtifactSource.ofMavenCoordinates(
                URI.create("https://repo1.maven.org/maven2"),
                "org.kill-bill.billing.plugin.java", "analytics-plugin", "7.0.0");

        assertEquals(source.uri().toString(),
                     "https://repo1.maven.org/maven2/org/kill-bill/billing/plugin/java/"
                     + "analytics-plugin/7.0.0/analytics-plugin-7.0.0.jar");
    }

    /**
     * Snapshot resolution needs maven-metadata.xml and a timestamped filename. Guessing at it would
     * produce a 404 whose cause is not obvious, so it is refused with an explanation instead.
     */
    @Test(groups = "fast")
    public void testMavenCoordinatesRefuseSnapshotsRatherThanGuessing() {
        final IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
                                                        () -> ArtifactSource.ofMavenCoordinates(
                                                                URI.create("https://example.invalid/repo"),
                                                                "g", "a", "1.0.0-SNAPSHOT"));
        assertTrue(e.getMessage().contains("snapshot"), e.getMessage());
    }

    private List<Path> stagingDirectories() {
        final Path pluginDir = root.resolve(PLUGIN_ID);
        if (!Files.isDirectory(pluginDir)) {
            return List.of();
        }
        try (var entries = Files.list(pluginDir)) {
            return entries.filter(p -> p.getFileName().toString().contains(".incoming-")).toList();
        } catch (final java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path someJar(final String content) throws Exception {
        final Path jar = Files.createTempDirectory("lpr-artifact-").resolve("plugin.jar");
        Files.writeString(jar, content, StandardCharsets.UTF_8);
        return jar;
    }

    private static String sha1Of(final String content) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-1").digest(content.getBytes(StandardCharsets.UTF_8)));
    }
}

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

package org.killbill.billing.lpr.repository.filesystem;

import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.killbill.billing.lpr.descriptor.yaml.YamlDescriptorParser;
import org.killbill.billing.lpr.spi.PluginRepository;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class TestFileSystemPluginRepository {
    private static final byte[] JAR = {1, 2, 3};

    @Test(groups = "fast")
    public void testLegacyArtifactCannotBindAnIncorrectContentIdentity() throws Exception {
        try (Storage s = new Storage()) {
            publish(s.local(), "1.0.0");
            final var repo = s.shared();
            final var before = repo.selection("demo");
            assertThrows(IllegalStateException.class, () -> repo.publish("demo", "1.0.0", new byte[]{9}, descriptor("1.0.0")));
            assertEquals(repo.selection("demo"), before, "Rejected publication must not disable the existing selection");
            publish(repo, "1.0.0");
            repo.remove("demo", "1.0.0");
            assertThrows(IllegalStateException.class, () -> repo.publish("demo", "1.0.0", new byte[]{9}, descriptor("1.0.0")));
        }
    }

    @Test(groups = "fast")
    public void testCorruptSelectionPreventsSharedPublication() throws Exception {
        try (Storage s = new Storage()) {
            final var repo = s.shared();
            publish(repo, "1.0.0");
            Files.writeString(s.root.resolve("demo/ACTIVE"), "corrupt");
            assertThrows(RuntimeException.class, () -> publish(repo, "2.0.0"));
            assertFalse(Files.exists(s.root.resolve("demo/2.0.0")));
        }
    }

    @Test(groups = "fast")
    public void testLocalSelectionAndLegacyMarker() throws Exception {
        try (Storage s = new Storage()) {
            final var repo = s.local();
            publish(repo, "1.0.0"); publish(repo, "2.0.0");
            assertEquals(repo.scan().get(0).descriptor().version(), "2.0.0");
            Files.writeString(s.root.resolve("demo/ACTIVE"), "active=1.0.0\nprevious=2.0.0\n");
            assertEquals(repo.selection("demo").revision(), 0L);
            assertTrue(repo.selection("demo").enabled());
            assertEquals(repo.scan().get(0).descriptor().version(), "1.0.0");
            final var next = repo.updateSelection("demo", 0, "2.0.0", "1.0.0", true);
            assertEquals(next.revision(), 1L);
            assertThrows(ConcurrentModificationException.class,
                         () -> repo.updateSelection("demo", 0, "1.0.0", null, true));
            assertEquals(repo.selection("demo"), next);
        }
    }

    @Test(groups = "fast")
    public void testCorruptStateFailsClosed() throws Exception {
        try (Storage s = new Storage()) {
            publish(s.local(), "1.0.0");
            for (final String marker : List.of("", "revision=1", "revision=-1", "revision=no", "active=bad", "revision=1\nenabled=maybe")) {
                Files.writeString(s.root.resolve("demo/ACTIVE"), marker);
                assertThrows(RuntimeException.class, () -> s.local().selection("demo"));
                assertThrows(RuntimeException.class, () -> s.local().updateSelection("demo", 0, "1.0.0", null, true));
            }
        }
    }

    @Test(groups = "fast")
    public void testSharedPublicationIsIdempotentAndInitiallyDisabled() throws Exception {
        try (Storage s = new Storage()) {
            final var repo = s.shared();
            publish(repo, "1.0.0");
            final var selected = repo.selection("demo");
            assertFalse(selected.enabled());
            assertEquals(selected.activeVersion(), "1.0.0");
            publish(repo, "1.0.0");
            assertEquals(repo.selection("demo"), selected);
            assertThrows(IllegalStateException.class, () -> repo.publish("demo", "1.0.0", new byte[]{9}, descriptor("1.0.0")));
            assertThrows(IllegalStateException.class, () -> repo.publish("demo", "1.0.0", JAR, descriptor("1.0.0") + "#changed"));
            assertEquals(Files.readAllBytes(s.root.resolve("demo/1.0.0/plugin.jar")), JAR);
        }
    }

    @Test(groups = "fast")
    public void testRevisionSurvivesLastUninstallAndReinstall() throws Exception {
        try (Storage s = new Storage()) {
            final var repo = s.shared(); publish(repo, "1.0.0");
            final long revision = repo.selection("demo").revision();
            repo.updateSelection("demo", revision, "1.0.0", null, true);
            assertThrows(IllegalStateException.class, () -> repo.remove("demo", "1.0.0"));
            repo.updateSelection("demo", revision + 1, "1.0.0", null, false);
            repo.remove("demo", "1.0.0"); publish(repo, "1.0.0");
            assertEquals(repo.selection("demo").revision(), revision + 2);
            assertFalse(repo.selection("demo").enabled());
            assertThrows(ConcurrentModificationException.class,
                         () -> repo.updateSelection("demo", revision, "1.0.0", null, true));
        }
    }

    @Test(groups = "fast")
    public void testCacheContainsPrivateLibrariesAndSurvivesSourceDeletion() throws Exception {
        try (Storage s = new Storage()) {
            final var repo = s.shared(); publish(repo, "1.0.0");
            final Path lib = s.root.resolve("demo/1.0.0/lib/private.jar");
            Files.createDirectories(lib.getParent()); Files.write(lib, new byte[]{7});
            final var first = repo.loadVersion("demo", "1.0.0");
            assertEquals(first.artifact().libraries().size(), 1);
            assertEquals(Files.readAllBytes(first.artifact().libraries().get(0)), new byte[]{7});
            final var second = repo.loadVersion("demo", "1.0.0");
            assertEquals(first.artifact(), second.artifact());
            repo.remove("demo", "1.0.0");
            assertEquals(Files.readAllBytes(first.artifact().jar()), JAR);
            assertTrue(Files.exists(first.artifact().libraries().get(0)));
            assertThrows(IllegalStateException.class, () -> repo.publish("demo", "1.0.0", new byte[]{8}, descriptor("1.0.0")));
            publish(repo, "1.0.0");
            assertNotEquals(repo.loadVersion("demo", "1.0.0").artifact().jar(), first.artifact().jar(),
                            "The cache digest also includes the sidecar library that was removed with the source");
        }
    }

    @Test(groups = "fast")
    public void testPathsAndSymlinkDirectoriesAreRejected() throws Exception {
        try (Storage s = new Storage()) {
            final var repo = s.shared();
            assertThrows(IllegalArgumentException.class, () -> repo.publish("../escape", "1.0.0", JAR, ""));
            assertThrows(IllegalArgumentException.class, () -> repo.publish("demo", "../escape", JAR, ""));
            assertThrows(IllegalArgumentException.class,
                         () -> new SharedFileSystemPluginRepository(s.root, s.root.resolve("cache"), new YamlDescriptorParser()));
            publish(repo, "1.0.0");
            final Path outside = s.base.resolve("outside"); Files.createDirectories(outside);
            Files.write(outside.resolve("private.jar"), JAR);
            Files.createSymbolicLink(s.root.resolve("demo/1.0.0/lib"), outside);
            assertThrows(RuntimeException.class, () -> repo.loadVersion("demo", "1.0.0"));
        }
    }

    @Test(groups = "fast")
    public void testLockTimeoutDoesNotWriteState() throws Exception {
        try (Storage s = new Storage()) {
            final var repo = new FileSystemPluginRepository(s.root, new YamlDescriptorParser(), 50);
            publish(repo, "1.0.0");
            try (var channel = FileChannel.open(s.root.resolve(".locks/demo.lock"), StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                assertThrows(UncheckedIOException.class, () -> repo.updateSelection("demo", 0, "1.0.0", null, true));
                assertEquals(repo.selection("demo").revision(), 0L);
            }
        }
    }

    @Test(groups = "fast")
    public void testTwoProcessesCompeteOnOneRevision() throws Exception {
        try (Storage s = new Storage()) {
            publish(s.local(), "1.0.0"); publish(s.local(), "2.0.0");
            final Process a = process(s, "a", "1.0.0");
            final Process b = process(s, "b", "2.0.0");
            try {
                awaitFile(s.base.resolve("a.ready")); awaitFile(s.base.resolve("b.ready"));
                Files.createFile(s.base.resolve("go"));
                assertTrue(a.waitFor(15, TimeUnit.SECONDS)); assertTrue(b.waitFor(15, TimeUnit.SECONDS));
                assertEquals(List.of(a.exitValue(), b.exitValue()).stream().sorted().toList(), List.of(0, 3));
                assertEquals(s.local().selection("demo").revision(), 1L);
                assertFalse(Files.exists(s.root.resolve("demo/ACTIVE.tmp")));
            } finally { a.destroyForcibly(); b.destroyForcibly(); }
        }
    }

    private Process process(final Storage s, final String name, final String version) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
                System.getProperty("java.class.path"), RepositoryCasProcess.class.getName(), s.root.toString(),
                s.base.toString(), name, version).redirectErrorStream(true).redirectOutput(s.base.resolve(name + ".log").toFile()).start();
    }

    static void awaitFile(final Path path) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(path)) {
            if (System.nanoTime() > deadline) { throw new IllegalStateException("Timed out waiting for " + path); }
            Thread.sleep(10);
        }
    }

    static void publish(final PluginRepository repository, final String version) {
        repository.publish("demo", version, JAR, descriptor(version));
    }

    static String descriptor(final String version) {
        return "id: demo\nname: Demo\nversion: " + version + "\nentrypoint: example.Demo\n";
    }

    private static final class Storage implements AutoCloseable {
        final Path base = Files.createTempDirectory("lpr-repository-test-");
        final Path root = Files.createDirectories(base.resolve("repository"));
        Storage() throws Exception { }
        FileSystemPluginRepository local() { return new FileSystemPluginRepository(root, new YamlDescriptorParser()); }
        SharedFileSystemPluginRepository shared() {
            return new SharedFileSystemPluginRepository(root, base.resolve("cache"), new YamlDescriptorParser());
        }
        @Override public void close() throws Exception { RepositoryFiles.delete(base); }
    }
}

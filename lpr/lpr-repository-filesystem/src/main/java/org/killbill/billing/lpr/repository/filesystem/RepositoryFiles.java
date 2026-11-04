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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.killbill.billing.lpr.spi.SemanticVersion;

final class RepositoryFiles {
    private final Path root;
    private final long lockTimeoutMillis;

    RepositoryFiles(final Path root, final long lockTimeoutMillis) {
        if (lockTimeoutMillis <= 0) { throw new IllegalArgumentException("Lock timeout must be positive"); }
        this.root = root;
        this.lockTimeoutMillis = lockTimeoutMillis;
    }

    static boolean validId(final String id) { return id != null && id.matches("[a-z0-9][a-z0-9.-]*"); }

    Path pluginPath(final String id) {
        if (!validId(id)) { throw new IllegalArgumentException("Invalid plugin id: " + id); }
        final Path path = root.resolve(id);
        if (Files.exists(path)) { requireContained(path); }
        return path;
    }

    Path versionPath(final String id, final String version) {
        SemanticVersion.parse(version);
        final Path path = pluginPath(id).resolve(version);
        if (Files.exists(path)) { requireContained(path); }
        return path;
    }

    void requireContained(final Path path) {
        try {
            if (Files.isSymbolicLink(path) || !path.toRealPath().startsWith(root.toRealPath())) {
                throw new IllegalArgumentException("Repository path escapes storage: " + path);
            }
        } catch (final IOException e) { throw new UncheckedIOException(e); }
    }

    List<String> pluginIds() {
        if (!Files.isDirectory(root)) { return List.of(); }
        try (var entries = Files.list(root)) {
            return entries.filter(Files::isDirectory).map(path -> path.getFileName().toString())
                          .filter(RepositoryFiles::validId).sorted().toList();
        } catch (final IOException e) { throw new UncheckedIOException(e); }
    }

    <T> T locked(final String id, final IoAction<T> action) {
        pluginPath(id);
        try {
            final Path locks = root.resolve(".locks");
            Files.createDirectories(locks);
            requireContained(locks);
            final Path lockPath = locks.resolve(id + ".lock");
            if (Files.exists(lockPath)) { requireContained(lockPath); }
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = acquire(channel)) {
                return action.run();
            }
        } catch (final IOException e) { throw new UncheckedIOException("Repository operation failed for " + id, e); }
    }

    private FileLock acquire(final FileChannel channel) throws IOException {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lockTimeoutMillis);
        do {
            try {
                final FileLock lock = channel.tryLock();
                if (lock != null) { return lock; }
            } catch (final OverlappingFileLockException busy) { }
            try { Thread.sleep(10); }
            catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for repository lock", e);
            }
        } while (System.nanoTime() < deadline);
        throw new IOException("Repository lock timed out after " + lockTimeoutMillis + "ms");
    }

    Path publish(final String id, final String version, final byte[] jar, final String descriptor) {
        return locked(id, () -> publishUnlocked(id, version, jar, descriptor, false));
    }

    Path publishUnlocked(final String id, final String version, final byte[] jar,
                          final String descriptor, final boolean idempotent) throws IOException {
        final Path target = versionPath(id, version);
        if (Files.exists(target)) {
            if (idempotent && Arrays.equals(jar, Files.readAllBytes(target.resolve("plugin.jar")))
                && descriptor.equals(Files.readString(target.resolve("plugin.yaml")))) { return target; }
            throw new IllegalStateException("Plugin " + id + " version " + version + " is already installed");
        }
        Files.createDirectories(target.getParent());
        final Path staging = Files.createTempDirectory(target.getParent(), version + ".incoming-");
        try {
            Files.write(staging.resolve("plugin.jar"), jar);
            Files.writeString(staging.resolve("plugin.yaml"), descriptor);
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } finally { delete(staging); }
    }

    boolean remove(final String id, final String version, final boolean preserveSelection) {
        return locked(id, () -> {
            final Path target = versionPath(id, version);
            if (!Files.isDirectory(target)) { return false; }
            if (preserveSelection) {
                final var selected = new SelectionStore(root, this).read(id);
                if (selected.enabled() && version.equals(selected.activeVersion())) {
                    throw new IllegalStateException("Stop the shared desired version before uninstalling: " + id);
                }
            }
            delete(target);
            if (!preserveSelection) {
                try (var entries = Files.list(target.getParent())) {
                    if (entries.noneMatch(Files::isDirectory)) { delete(target.getParent()); }
                }
            }
            return true;
        });
    }

    static void delete(final Path path) throws IOException {
        if (!Files.exists(path)) { return; }
        try (var paths = Files.walk(path)) {
            for (final Path entry : paths.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(entry); }
        }
    }

    @FunctionalInterface
    interface IoAction<T> { T run() throws IOException; }
}

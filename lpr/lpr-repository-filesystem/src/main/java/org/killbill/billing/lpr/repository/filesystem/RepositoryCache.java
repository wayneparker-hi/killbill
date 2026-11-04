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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.killbill.billing.lpr.spi.PluginArtifact;
import org.killbill.billing.lpr.spi.PluginDeployment;

/** Immutable content directories: source removal never invalidates an already loaded cache. */
final class RepositoryCache {
    private RepositoryCache() { }

    static PluginDeployment materialize(final PluginDeployment source, final Path cache) throws IOException {
        final Path sourceRoot = source.artifact().root();
        final List<Path> contents;
        try (var paths = Files.walk(sourceRoot)) {
            final List<Path> entries = paths.sorted().toList();
            for (final Path entry : entries) {
                if (Files.isSymbolicLink(entry)) { throw new IOException("Symbolic links are not cacheable: " + entry); }
            }
            contents = entries.stream().filter(Files::isRegularFile).toList();
        }
        final String digest = digest(sourceRoot, contents);
        final Path parent = cache.resolve(source.descriptor().pluginId()).resolve(source.descriptor().version());
        Files.createDirectories(parent);
        final Path target = parent.resolve(digest);
        if (!Files.isDirectory(target)) { copy(sourceRoot, contents, parent, target); }
        return new PluginDeployment(source.descriptor(), new PluginArtifact(source.artifact().pluginId(),
                source.artifact().version(), target.resolve(sourceRoot.relativize(source.artifact().jar())),
                source.artifact().libraries().stream().map(path -> target.resolve(sourceRoot.relativize(path))).toList()));
    }

    private static String digest(final Path root, final List<Path> contents) throws IOException {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (final Path path : contents) {
                if (Files.isSymbolicLink(path) || !path.toRealPath().startsWith(root.toRealPath())) {
                    throw new IOException("Cache source escapes plugin directory: " + path);
                }
                final byte[] name = root.relativize(path).toString().getBytes(StandardCharsets.UTF_8);
                final byte[] bytes = Files.readAllBytes(path);
                digest.update(java.nio.ByteBuffer.allocate(12).putInt(name.length).putLong(bytes.length).array());
                digest.update(name);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (final NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void copy(final Path root, final List<Path> contents, final Path parent,
                              final Path target) throws IOException {
        final Path staging = Files.createTempDirectory(parent, ".incoming-");
        try {
            for (final Path path : contents) {
                final Path dest = staging.resolve(root.relativize(path));
                Files.createDirectories(dest.getParent());
                Files.copy(path, dest);
            }
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } finally { RepositoryFiles.delete(staging); }
    }
}

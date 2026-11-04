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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ConcurrentModificationException;
import java.util.Properties;

import org.killbill.billing.lpr.spi.RepositorySelection;

/** Callers hold the plugin lock for updates; readers only see complete atomic replacements. */
final class SelectionStore {
    private final Path root;
    private final RepositoryFiles files;

    SelectionStore(final Path root, final RepositoryFiles files) { this.root = root; this.files = files; }

    RepositorySelection read(final String id) {
        final Path marker = files.pluginPath(id).resolve("ACTIVE");
        if (!Files.exists(marker)) { return RepositorySelection.initial(); }
        files.requireContained(marker);
        try (var reader = Files.newBufferedReader(marker)) {
            final Properties p = new Properties();
            p.load(reader);
            final String enabled = p.getProperty("enabled", "true");
            if (!enabled.equals("true") && !enabled.equals("false")) {
                throw new IllegalArgumentException("Invalid enabled flag in " + marker);
            }
            if (p.getProperty("active") == null) {
                throw new IllegalArgumentException("Missing desired state in " + marker);
            }
            return new RepositorySelection(Long.parseLong(p.getProperty("revision", "0")),
                                           p.getProperty("active"), p.getProperty("previous"), Boolean.parseBoolean(enabled));
        } catch (final IOException e) { throw new UncheckedIOException("Cannot read selection " + marker, e); }
    }

    RepositorySelection update(final String id, final long expected, final String version,
                                final String previous, final boolean enabled) throws IOException {
        final RepositorySelection current = read(id);
        if (current.revision() != expected) {
            throw new ConcurrentModificationException("Stale selection for " + id + ": expected " + expected
                                                      + ", found " + current.revision());
        }
        final RepositorySelection next = new RepositorySelection(Math.incrementExact(expected), version, previous, enabled);
        final Properties properties = new Properties();
        properties.setProperty("revision", Long.toString(next.revision()));
        properties.setProperty("enabled", Boolean.toString(enabled));
        if (version != null) { properties.setProperty("active", version); }
        if (previous != null) { properties.setProperty("previous", previous); }
        final Path dir = files.pluginPath(id);
        Files.createDirectories(dir);
        final Path staging = Files.createTempFile(dir, "ACTIVE.", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(staging)) {
                properties.store(writer, "LPR desired state; update with revision CAS");
            }
            Files.move(staging, dir.resolve("ACTIVE"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(staging); }
        return next;
    }
}

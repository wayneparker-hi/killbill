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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.killbill.billing.lpr.spi.DescriptorParser;
import org.killbill.billing.lpr.spi.PluginDeployment;
import org.killbill.billing.lpr.spi.PluginRepository;
import org.killbill.billing.lpr.spi.RepositorySelection;

/** Shared filesystem (including NFS with advisory locks/atomic rename), with a node-local cache. */
public final class SharedFileSystemPluginRepository implements PluginRepository {
    private final FileSystemPluginRepository shared;
    private final Path cache;

    public SharedFileSystemPluginRepository(final Path root, final Path cache, final DescriptorParser parser) {
        this(root, cache, parser, 5000);
    }

    public SharedFileSystemPluginRepository(final Path root, final Path cache,
                                            final DescriptorParser parser, final long lockTimeoutMillis) {
        try {
            Files.createDirectories(root);
            Files.createDirectories(cache);
            final Path storage = root.toRealPath();
            this.cache = cache.toRealPath();
            if (storage.startsWith(this.cache) || this.cache.startsWith(storage)) {
                throw new IllegalArgumentException("Shared storage and node cache must not overlap");
            }
            shared = new FileSystemPluginRepository(storage, parser, lockTimeoutMillis);
        } catch (final java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    @Override public Path root() { return shared.root(); }
    @Override public boolean shared() { return true; }
    @Override public List<String> pluginIds() { return shared.pluginIds(); }
    @Override public List<String> installedVersions(final String id) { return shared.installedVersions(id); }
    @Override public RepositorySelection selection(final String id) { return shared.selection(id); }
    @Override public PluginDeployment loadVersion(final String id, final String version) {
        return shared.cachedVersion(id, version, cache);
    }
    @Override public List<PluginDeployment> scan() {
        final List<PluginDeployment> result = new ArrayList<>();
        for (final PluginDeployment deployment : shared.scan()) {
            try { result.add(loadVersion(deployment.descriptor().pluginId(), deployment.descriptor().version())); }
            catch (final RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Cannot cache plugin {}", deployment.descriptor().pluginId(), e);
            }
        }
        return List.copyOf(result);
    }
    @Override public RepositorySelection updateSelection(final String id, final long expected,
                                                          final String version, final String previous, final boolean enabled) {
        return shared.updateSelection(id, expected, version, previous, enabled);
    }
    @Override public Path publish(final String id, final String version, final byte[] jar, final String descriptor) {
        return shared.publishShared(id, version, jar, descriptor);
    }
    @Override public boolean remove(final String id, final String version) { return shared.removeShared(id, version); }
}

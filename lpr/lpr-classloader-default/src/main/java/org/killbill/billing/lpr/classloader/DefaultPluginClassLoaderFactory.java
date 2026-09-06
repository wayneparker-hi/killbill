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

package org.killbill.billing.lpr.classloader;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.killbill.billing.lpr.spi.ClassLoaderPolicy;
import org.killbill.billing.lpr.spi.PluginArtifact;
import org.killbill.billing.lpr.spi.PluginClassLoaderFactory;
import org.killbill.billing.lpr.spi.PluginClassLoaderHandle;

/**
 * The shipping ClassLoader backend: a {@link DefaultPluginClassLoader} per plugin version.
 * <p>
 * It is deliberately small because the problem is small. Kill Bill plugins are shaded jars, and
 * plugin-to-plugin dependencies are not supported, so there is no dependency graph to resolve and
 * no cross-plugin export table to maintain -- the two things that make general module systems
 * large. What remains is delegation order, which lives in {@link ClassLoaderPolicy}.
 * <p>
 * Thread-safe: instances hold no mutable state.
 */
public class DefaultPluginClassLoaderFactory implements PluginClassLoaderFactory {

    @Override
    public PluginClassLoaderHandle create(final PluginArtifact artifact,
                                          final ClassLoaderPolicy policy,
                                          final ClassLoader parent) {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(parent, "parent");

        final URL[] classpath = buildClasspath(artifact);
        final DefaultPluginClassLoader classLoader =
                new DefaultPluginClassLoader(artifact.pluginId(), classpath, parent, policy);
        return new DefaultPluginClassLoaderHandle(artifact.pluginId(), classLoader);
    }

    @Override
    public String backendName() {
        return "default";
    }

    /**
     * Plugin jar first, then {@code lib/} entries in declared order, mirroring how a shaded jar
     * would have resolved them.
     */
    private URL[] buildClasspath(final PluginArtifact artifact) {
        final List<Path> entries = new ArrayList<>();
        entries.add(artifact.jar());
        entries.addAll(artifact.libraries());

        final List<URL> urls = new ArrayList<>(entries.size());
        for (final Path entry : entries) {
            if (!Files.exists(entry)) {
                throw new IllegalStateException("Classpath entry does not exist for plugin "
                                                + artifact.pluginId() + ": " + entry);
            }
            try {
                urls.add(entry.toUri().toURL());
            } catch (final MalformedURLException e) {
                throw new IllegalStateException("Cannot build classpath URL for plugin "
                                                + artifact.pluginId() + ": " + entry, e);
            }
        }
        return urls.toArray(new URL[0]);
    }
}

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

package org.killbill.billing.lpr.spi;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * The on-disk form of one plugin version: what to put on its classpath, and who it is.
 * <p>
 * Kill Bill plugins are shaded jars -- the OSGi build inlined every compile and runtime dependency
 * into the bundle -- so {@code libraries} is usually empty. It exists for plugins packaged as a
 * thin jar beside a {@code lib/} directory.
 *
 * @param pluginId  stable identity, matching {@code id} in the descriptor
 * @param version   semantic version of this artifact
 * @param jar       the plugin jar containing the entrypoint class
 * @param libraries additional classpath entries, in order; may be empty
 */
public record PluginArtifact(String pluginId, String version, Path jar, List<Path> libraries) {

    public PluginArtifact {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(jar, "jar");
        if (jar.getParent() == null) {
            // A plugin lives in a version directory under the repository. A jar with no parent is
            // one sitting at a filesystem root, which no repository layout can produce -- rejecting
            // it here is what lets root() be non-null for everyone downstream.
            throw new IllegalArgumentException("Plugin jar must live in a directory: " + jar);
        }
        libraries = List.copyOf(Objects.requireNonNullElse(libraries, List.of()));
    }

    /**
     * The version directory holding this artifact, e.g. {@code .../plugins/hello-world/1.2.0}.
     * <p>
     * Plugins that ship data files beside their jar resolve them against this.
     *
     * @return the directory containing {@link #jar()}; never null
     */
    public Path root() {
        return jar.getParent();
    }

    /**
     * Convenience for the common shaded-jar case.
     *
     * @param pluginId stable identity
     * @param version  semantic version
     * @param jar      the plugin jar
     * @return an artifact with no extra classpath entries
     */
    public static PluginArtifact of(final String pluginId, final String version, final Path jar) {
        return new PluginArtifact(pluginId, version, jar, List.of());
    }
}

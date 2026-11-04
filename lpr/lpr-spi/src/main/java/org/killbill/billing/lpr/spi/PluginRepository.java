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
import java.util.Optional;

/** Storage port. Implementations materialize artifacts locally before returning deployments. */
public interface PluginRepository {
    String ACTIVE_MARKER = "ACTIVE";
    String ACTIVE_KEY = "active";
    String PREVIOUS_KEY = "previous";
    String DISABLED_MARKER = "DISABLED";
    String PLUGIN_JAR = "plugin.jar";
    String LIB_DIRECTORY = "lib";

    Path root();
    List<String> pluginIds();
    List<PluginDeployment> scan();
    PluginDeployment loadVersion(String pluginId, String version);
    List<String> installedVersions(String pluginId);
    RepositorySelection selection(String pluginId);

    /** Fails with ConcurrentModificationException on a stale revision. */
    RepositorySelection updateSelection(String pluginId, long expectedRevision, String version,
                                         String previousVersion, boolean enabled);

    /** Atomic publication; existing versions are never overwritten. */
    Path publish(String pluginId, String version, byte[] jar, String descriptor);
    boolean remove(String pluginId, String version);

    default boolean shared() { return false; }

    default Optional<String> previousVersion(final String pluginId) {
        return Optional.ofNullable(selection(pluginId).previousVersion());
    }

    default void setActiveVersion(final String pluginId, final String version) {
        setActiveVersion(pluginId, version, null);
    }

    default void setActiveVersion(final String pluginId, final String version, final String previousVersion) {
        final RepositorySelection current = selection(pluginId);
        updateSelection(pluginId, current.revision(), version,
                        previousVersion != null ? previousVersion : current.activeVersion(), true);
    }
}

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
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.killbill.billing.lpr.core;

import java.util.Optional;

/**
 * Observer for version activation and rollback.
 * <p>
 * The runtime itself stays agnostic of health checks, audit buses and other host concerns; this
 * hook lets the adapter layer react to a version switch without leaking those concerns into
 * {@code DefaultPluginManager}.
 */
public interface PluginActivationListener {

    /** Whether the host needs the previous running version during its observation window. */
    default boolean retainPreviousVersion() {
        return false;
    }

    /**
     * Called after a plugin has been switched to a new version.
     *
     * @param pluginId        the plugin
     * @param newVersion      the version that is now serving traffic
     * @param previousVersion the version that was running before, or empty if none was running
     */
    default void onActivated(final String pluginId,
                             final String newVersion,
                             final Optional<String> previousVersion) {
    }

    /**
     * Called after a plugin has been rolled back.
     *
     * @param pluginId      the plugin
     * @param currentVersion the version that is now serving traffic
     * @param failedVersion  the version that was rolled back from
     */
    default void onRolledBack(final String pluginId,
                              final String currentVersion,
                              final String failedVersion) {
    }
    default void onRolledBack(final String pluginId, final String currentVersion,
                              final String failedVersion, final String reason) {
        onRolledBack(pluginId, currentVersion, failedVersion);
    }

}

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

package org.killbill.billing.lpr.core;

import java.util.List;
import java.util.Objects;

import org.killbill.billing.lpr.api.PluginState;

/**
 * A snapshot of one plugin for the management API and for operators.
 * <p>
 * This is what an operator sees when asking "what is installed and what is running", and it is the
 * shape Kill Bill's {@code /1.0/kb/pluginsInfo} endpoint and {@code node_infos} table are fed from.
 * <p>
 * It reports {@code installedVersions} alongside the running one so that "why is 1.2 still serving
 * traffic when I deployed 1.3" is answerable from a single call rather than from the filesystem.
 *
 * @param pluginId          stable identity
 * @param name              human-readable name
 * @param version           the version currently loaded
 * @param state             where that version is in its lifecycle
 * @param services          service interfaces this plugin currently publishes
 * @param installedVersions every version on disk, highest first
 * @param failureReason     why it last failed, if it is FAILED
 */
public record PluginInfo(String pluginId,
                         String name,
                         String version,
                         PluginState state,
                         List<String> services,
                         List<String> installedVersions,
                         String failureReason) {

    public PluginInfo {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(state, "state");
        services = List.copyOf(Objects.requireNonNullElse(services, List.of()));
        installedVersions = List.copyOf(Objects.requireNonNullElse(installedVersions, List.of()));
    }

    /**
     * @return whether this plugin is currently serving traffic
     */
    public boolean isRunning() {
        return state.isRunning();
    }
}

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

import org.killbill.billing.lpr.api.Plugin;

/**
 * A read-only view of one running plugin version, exposed so host-side collaborators can inspect
 * it without gaining access to the full {@link PluginRuntime} state machine.
 */
public interface RunningPlugin {

    /**
     * @return the plugin id
     */
    String pluginId();

    /**
     * @return the plugin version
     */
    String version();

    /**
     * @return the plugin entry point instance; never null for a running plugin
     */
    Plugin instance();

    /**
     * @return the ClassLoader the plugin's classes were loaded from; never null for a running plugin
     */
    ClassLoader classLoader();
}

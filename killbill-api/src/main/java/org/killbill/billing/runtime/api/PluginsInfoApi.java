/*
 * Copyright 2010-2013 Ning, Inc.
 * Copyright 2015 Groupon, Inc
 * Copyright 2015 The Billing Project, LLC
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

package org.killbill.billing.runtime.api;

import org.killbill.billing.KillbillApi;

public interface PluginsInfoApi extends KillbillApi {

    /**
     * Every plugin version present on disk, whether or not it is loaded.
     * <p>
     * A plugin with several installed versions yields one entry per version, exactly one of which
     * reports {@link PluginInfo#isSelectedForStart()}.
     *
     * @return the plugins as seen by the plugin runtime
     */
    public Iterable<PluginInfo> getPluginsInfo();

    /**
     * Tells the plugin runtime that a plugin's files changed underneath it.
     * <p>
     * Called after something outside the runtime -- an install, or an operator disabling a version --
     * has already changed the filesystem. The runtime rescans and, for {@code DISABLED}, stops the
     * plugin if it is running. It does not start anything: making a version visible and choosing to
     * run it are separate decisions.
     *
     * @param newState      what changed
     * @param pluginId      the plugin whose directory changed
     * @param pluginVersion the version involved
     */
    public void notifyOfStateChanged(PluginStateChange newState, String pluginId, String pluginVersion);
}

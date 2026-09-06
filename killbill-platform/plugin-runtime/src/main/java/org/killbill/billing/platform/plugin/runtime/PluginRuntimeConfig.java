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

package org.killbill.billing.platform.plugin.runtime;

import org.killbill.billing.platform.api.KillbillPlatformConfig;
import org.skife.config.Config;
import org.skife.config.Default;
import org.skife.config.DefaultNull;
import org.skife.config.Description;

/**
 * How the plugin runtime is configured.
 * <p>
 * Four properties, where its OSGi predecessor had a dozen. Most of what {@code OSGIConfig} carried
 * described the OSGi framework itself -- the bundle cache directory, its storage name, and two
 * lists totalling some two hundred package names that had to be exported from the system bundle
 * for plugins to see them. None of that has an equivalent here: there is no framework cache, and
 * package visibility is a fixed parent-first prefix list in the runtime rather than a deployment
 * setting.
 */
public interface PluginRuntimeConfig extends KillbillPlatformConfig {

    @Config("org.killbill.billing.plugin.install.dir")
    @Default("/var/tmp/bundles")
    @Description("Root directory holding installed plugins, one sub-directory per plugin id")
    String getPluginInstallDir();

    @Config("org.killbill.billing.plugin.mandatory.plugins")
    @DefaultNull
    @Description("Comma-separated plugin ids whose failure to start should abort startup")
    String getMandatoryPlugins();

    @Config("org.killbill.billing.plugin.parentFirstPackages")
    @DefaultNull
    @Description("Extra package prefixes plugins must resolve from the platform rather than from "
                 + "their own jar. The runtime already covers the JDK and the Kill Bill plugin API; "
                 + "add to this only to share a library's types across the boundary, which also "
                 + "means plugins can no longer choose their own version of it")
    String getExtraParentFirstPackages();

    @Config("org.killbill.billing.plugin.start.enabled")
    @Default("true")
    @Description("Whether to start plugins during the lifecycle. Set false to inspect what is "
                 + "installed without running any of it")
    boolean isPluginStartEnabled();
}

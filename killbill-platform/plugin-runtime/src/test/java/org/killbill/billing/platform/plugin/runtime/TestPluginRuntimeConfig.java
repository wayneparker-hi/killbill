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

package org.killbill.billing.platform.plugin.runtime;

/**
 * Default values for every {@link PluginRuntimeConfig} property, so test stubs only override what
 * they care about.
 */
public class TestPluginRuntimeConfig implements PluginRuntimeConfig {

    @Override
    public String getPluginInstallDir() {
        return "/tmp/plugins";
    }

    @Override
    public String getMandatoryPlugins() {
        return null;
    }

    @Override
    public String getExtraParentFirstPackages() {
        return null;
    }

    @Override
    public boolean isPluginStartEnabled() {
        return true;
    }

    @Override
    public long getPluginInvocationDefaultTimeout() {
        return 30000L;
    }

    @Override
    public String getPluginInvocationTimeoutOverrides() {
        return null;
    }

    @Override
    public int getPluginInvocationMaxThreads() { return 64; }

    @Override
    public int getPluginInvocationMaxConcurrentPerPlugin() { return 16; }

    @Override
    public int getPluginCircuitBreakerFailureThreshold() {
        return 5;
    }

    @Override
    public long getPluginCircuitBreakerOpenDuration() {
        return 60000L;
    }

    @Override
    public boolean isPluginHealthCheckEnabled() {
        return true;
    }

    @Override
    public long getPluginHealthCheckInterval() {
        return 30000L;
    }

    @Override
    public long getPluginHealthCheckTimeout() {
        return 5000L;
    }

    @Override
    public int getPluginHealthCheckMaxThreads() { return 8; }

    @Override
    public boolean isPluginUpgradeAutoRollbackEnabled() {
        return true;
    }

    @Override
    public long getPluginUpgradeHealthCheckWindow() {
        return 60000L;
    }

    @Override
    public long getPluginUpgradeHealthCheckInterval() {
        return 5000L;
    }
}

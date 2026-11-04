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

    @Config("org.killbill.billing.plugin.repository.mode")
    @Default("local")
    @Description("Repository mode: local or shared (shared filesystem with cross-node locks and atomic rename)")
    String getPluginRepositoryMode();

    @Config("org.killbill.billing.plugin.repository.shared.dir")
    @DefaultNull
    @Description("Authoritative shared repository; install.dir is the node-local cache in shared mode")
    String getPluginSharedRepositoryDir();

    @Config("org.killbill.billing.plugin.repository.lockTimeout")
    @Default("5000")
    long getPluginRepositoryLockTimeout();

    @Config("org.killbill.billing.plugin.repository.reconcileInterval")
    @Default("5000")
    long getPluginRepositoryReconcileInterval();

    @Config("org.killbill.billing.plugin.repository.reportStaleAfter")
    @Default("30000")
    long getPluginReportStaleAfter();

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

    @Config("org.killbill.billing.plugin.invocation.defaultTimeout")
    @Default("30000")
    @Description("Default timeout in milliseconds for every call into a plugin service "
                 + "(payment, invoice, notification, etc.)")
    long getPluginInvocationDefaultTimeout();

    @Config("org.killbill.billing.plugin.invocation.timeoutOverrides")
    @DefaultNull
    @Description("Comma-separated plugin-id=timeout-ms pairs that override the default timeout. "
                 + "Example: stripe-plugin=60000,avatax-plugin=45000")
    String getPluginInvocationTimeoutOverrides();

    @Config("org.killbill.billing.plugin.invocation.maxThreads")
    @Default("64")
    @Description("Maximum concurrent invocation workers across all plugins")
    int getPluginInvocationMaxThreads();

    @Config("org.killbill.billing.plugin.invocation.maxConcurrentPerPlugin")
    @Default("16")
    @Description("Maximum plugin calls still executing, including timed-out calls")
    int getPluginInvocationMaxConcurrentPerPlugin();

    @Config("org.killbill.billing.plugin.invocation.circuitBreaker.failureThreshold")
    @Default("5")
    @Description("Consecutive failures before the circuit breaker opens for a plugin")
    int getPluginCircuitBreakerFailureThreshold();

    @Config("org.killbill.billing.plugin.invocation.circuitBreaker.openDuration")
    @Default("60000")
    @Description("How long the circuit stays open before allowing a half-open probe, in milliseconds")
    long getPluginCircuitBreakerOpenDuration();

    @Config("org.killbill.billing.plugin.healthcheck.enabled")
    @Default("true")
    @Description("Whether to run periodic plugin health checks")
    boolean isPluginHealthCheckEnabled();

    @Config("org.killbill.billing.plugin.healthcheck.interval")
    @Default("30000")
    @Description("How often to run plugin health checks, in milliseconds")
    long getPluginHealthCheckInterval();

    @Config("org.killbill.billing.plugin.healthcheck.timeout")
    @Default("5000")
    @Description("Timeout for a single plugin health check, in milliseconds")
    long getPluginHealthCheckTimeout();

    @Config("org.killbill.billing.plugin.healthcheck.maxThreads")
    @Default("8")
    @Description("Maximum concurrent health probe workers, including timed-out probes")
    int getPluginHealthCheckMaxThreads();

    @Config("org.killbill.billing.plugin.upgrade.autoRollback")
    @Default("true")
    @Description("Whether to roll back a newly activated version if its health check fails "
                 + "within the watch window")
    boolean isPluginUpgradeAutoRollbackEnabled();

    @Config("org.killbill.billing.plugin.upgrade.healthCheck.window")
    @Default("60000")
    @Description("How long after activation to watch the new version's health, in milliseconds")
    long getPluginUpgradeHealthCheckWindow();

    @Config("org.killbill.billing.plugin.upgrade.healthCheck.interval")
    @Default("5000")
    @Description("How often to check the new version's health during the watch window, "
                 + "in milliseconds")
    long getPluginUpgradeHealthCheckInterval();
    @Config("org.killbill.billing.plugin.lifecycle.stopTimeout")
    @Default("30000")
    @Description("Caller deadline for plugin stop and resource cleanup, in milliseconds")
    long getPluginStopTimeout();

    @Config("org.killbill.billing.plugin.lifecycle.stopMaxThreads")
    @Default("8")
    @Description("Maximum daemon workers executing plugin stop and resource cleanup")
    int getPluginStopMaxThreads();
}

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

package org.killbill.billing.lpr.api;

/**
 * An optional health probe a plugin can expose to the runtime.
 * <p>
 * Plugins return an implementation from {@link Plugin#healthCheck()}. The runtime calls it on a
 * fixed schedule and uses the result to decide whether the plugin's services remain visible to
 * selectors and to the host's healthcheck endpoint.
 * <p>
 * Implementations must be safe to call from any thread. They should return promptly; the runtime
 * enforces the timeout configured by {@code org.killbill.billing.plugin.healthcheck.timeout}.
 */
public interface PluginHealthCheck {

    /**
     * Performs a lightweight self-check.
     *
     * @return the result; never null
     * @throws Exception if the check cannot complete cleanly; the runtime treats this as unhealthy
     */
    HealthCheckResult check() throws Exception;

    /**
     * The outcome of a health check.
     *
     * @param healthy true when the plugin believes it can serve traffic
     * @param message human-readable details, especially useful when unhealthy
     */
    record HealthCheckResult(boolean healthy, String message) {

        public HealthCheckResult {
            if (message == null) {
                message = "";
            }
        }

        public static HealthCheckResult ok() {
            return new HealthCheckResult(true, "");
        }

        public static HealthCheckResult ok(final String message) {
            return new HealthCheckResult(true, message);
        }

        public static HealthCheckResult unhealthy(final String message) {
            return new HealthCheckResult(false, message);
        }
    }
}

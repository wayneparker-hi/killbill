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

/**
 * Thrown when a call into a plugin service is interrupted by the platform's own protections:
 * a timeout, an open circuit breaker, or an unexpected executor failure.
 * <p>
 * This is a runtime exception so it can pass through plugin API boundaries without changing
 * every {@code *PluginApi} method signature, while still being distinguishable from exceptions
 * the plugin itself threw.
 */
public class PluginInvocationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PluginInvocationException(final String message) {
        super(message);
    }

    public PluginInvocationException(final String message, final Throwable cause) {
        super(message, cause);
    }
}

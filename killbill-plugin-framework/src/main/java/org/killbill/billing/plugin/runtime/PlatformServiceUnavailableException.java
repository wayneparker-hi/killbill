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

package org.killbill.billing.plugin.runtime;

/**
 * A plugin asked for a platform service this deployment does not publish.
 * <p>
 * Replaces {@code OSGIServiceNotAvailable}, and means something narrower than its predecessor did.
 * Under OSGi a service could appear and vanish while the plugin ran, so this was a transient
 * condition worth retrying. The plugin runtime hands the platform services over once, at start, so
 * it now means a permanent one: the deployment does not have that API at all -- the payment-only
 * profile has no {@code OverdueApi} -- and retrying will not help.
 * <p>
 * A plugin that can work without an optional API should ask for it in {@code startPlugin()} and
 * catch this, rather than discovering the absence on the first request it serves.
 */
public class PlatformServiceUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PlatformServiceUnavailableException(final Throwable cause) {
        super(cause);
    }

    public PlatformServiceUnavailableException(final String message) {
        super(message);
    }

    public PlatformServiceUnavailableException(final String message, final Throwable cause) {
        super(message, cause);
    }
}

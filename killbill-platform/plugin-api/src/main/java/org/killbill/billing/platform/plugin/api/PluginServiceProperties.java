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

package org.killbill.billing.platform.plugin.api;

/**
 * Well-known properties a plugin may attach when publishing a service.
 */
public final class PluginServiceProperties {

    /**
     * The name Kill Bill will look this service up by. Optional: it defaults to the plugin id,
     * which is what almost every plugin wants.
     * <p>
     * It exists because the lookup key and the plugin's identity are not always the same thing. A
     * payment method row stores the plugin name it was created with, and that name has to keep
     * resolving after the plugin is renamed or after one plugin takes over another's traffic.
     * <p>
     * The OSGi layer called this {@code killbill.pluginName} and made it mandatory, which is why
     * every plugin carried a line of boilerplate setting it to its own name.
     */
    public static final String REGISTRATION_NAME = "killbill.registrationName";

    /**
     * Integer ranking used when several plugins publish the same service type and the caller takes
     * them in order. Higher first; absent means zero.
     */
    public static final String PRIORITY = "priority";

    /**
     * Guice binding name for the servlet that fronts every {@code /plugins/...} request.
     * <p>
     * Shared because the two ends live in different modules: the runtime binds the servlet, and
     * Kill Bill's JAX-RS layer injects it. A literal string on both sides would be a rename waiting
     * to fail at startup rather than at compile time.
     */
    public static final String PLUGIN_SERVLET = "pluginServlet";

    private PluginServiceProperties() {
    }
}

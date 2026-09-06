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

package org.killbill.billing.lpr.spi;

/**
 * A plugin's ClassLoader plus the means to release it.
 * <p>
 * Adapters return this rather than a raw {@code ClassLoader} so that the runtime never has to
 * guess how to tear one down: SOFAArk, a plain {@code URLClassLoader} and any future backend all
 * dispose of themselves behind {@link #close()}.
 * <p>
 * Closing shuts the door on new class loading; it does not reclaim the loaded classes. That only
 * happens once nothing refers to the ClassLoader any more, which is why plugin resources have to
 * go through {@code ResourceRegistry}.
 */
public interface PluginClassLoaderHandle extends AutoCloseable {

    /**
     * @return the plugin this ClassLoader belongs to
     */
    String pluginId();

    /**
     * @return the ClassLoader; distinct from every other plugin's
     */
    ClassLoader classLoader();

    /**
     * Releases the ClassLoader's file handles. Idempotent, never throws.
     */
    @Override
    void close();
}

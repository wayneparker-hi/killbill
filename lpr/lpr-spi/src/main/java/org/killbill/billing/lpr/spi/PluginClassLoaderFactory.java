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
 * Port for ClassLoader isolation -- the one piece of the runtime with a genuinely hard
 * implementation, and therefore the one most worth being able to swap.
 * <p>
 * The runtime depends on this interface and never on a backend. Two adapters ship: a direct
 * {@code URLClassLoader} implementation (the default, since Kill Bill plugins are shaded jars with
 * no plugin-to-plugin dependencies) and a SOFAArk-backed one. Both are held to the same two tests:
 * isolation (two plugins get independent copies of a conflicting library while sharing the API
 * types) and unloadability (a stopped plugin's ClassLoader becomes collectable).
 * <p>
 * Implementations must be thread-safe.
 */
public interface PluginClassLoaderFactory {

    /**
     * Builds an isolated ClassLoader for one plugin version.
     * <p>
     * Calling this twice for the same artifact yields two independent ClassLoaders. That is
     * deliberate: side-by-side loading of an old and a new version is how the runtime upgrades a
     * plugin without a gap in availability.
     *
     * @param artifact what to put on the classpath
     * @param policy   which packages resolve from the parent
     * @param parent   the parent ClassLoader, normally the one that loaded the runtime
     * @return a handle owning the new ClassLoader
     * @throws IllegalStateException if the classpath cannot be built
     */
    PluginClassLoaderHandle create(PluginArtifact artifact, ClassLoaderPolicy policy, ClassLoader parent);

    /**
     * @return short name of the backing implementation, for logs and diagnostics
     */
    String backendName();
}

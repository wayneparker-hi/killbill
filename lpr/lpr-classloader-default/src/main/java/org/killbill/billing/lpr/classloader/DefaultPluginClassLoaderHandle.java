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

package org.killbill.billing.lpr.classloader;

import java.io.IOException;
import java.util.Objects;

import org.killbill.billing.lpr.spi.PluginClassLoaderHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns one {@link DefaultPluginClassLoader} and closes it exactly once.
 * <p>
 * Note what closing does and does not do. It releases the jar file handles, so the artifact can be
 * replaced on disk. It does not unload the classes: the JVM reclaims those only when the
 * ClassLoader itself becomes unreachable, which depends on the plugin having released its threads,
 * subscriptions and callbacks. Closing is necessary for unloading, never sufficient.
 */
final class DefaultPluginClassLoaderHandle implements PluginClassLoaderHandle {

    private static final Logger log = LoggerFactory.getLogger(DefaultPluginClassLoaderHandle.class);

    private final String pluginId;
    private final DefaultPluginClassLoader classLoader;
    private volatile boolean closed;

    DefaultPluginClassLoaderHandle(final String pluginId, final DefaultPluginClassLoader classLoader) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    @Override
    public String pluginId() {
        return pluginId;
    }

    @Override
    public ClassLoader classLoader() {
        return classLoader;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            classLoader.close();
        } catch (final IOException e) {
            // Nothing useful to do: the plugin is going away regardless, and propagating would
            // abort the rest of the shutdown sequence.
            log.warn("Failed to close ClassLoader for plugin {}", pluginId, e);
        }
    }

    @Override
    public String toString() {
        return "DefaultPluginClassLoaderHandle{pluginId=" + pluginId + ", closed=" + closed + '}';
    }
}

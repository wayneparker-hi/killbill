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
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;

import org.killbill.billing.lpr.spi.ClassLoaderPolicy;

/**
 * One plugin's ClassLoader.
 * <p>
 * Standard Java delegation is parent-first for everything, which would force every plugin onto the
 * core's library versions. Standard child-first would fix that but break the API types: the
 * plugin's {@code PaymentPluginApi} would be a different {@code Class} object from the core's, and
 * every call across the boundary would fail with {@code ClassCastException}.
 * <p>
 * So delegation is split per package, driven by {@link ClassLoaderPolicy}: the JDK and the runtime
 * contract come from the parent, everything else comes from the plugin first. That is the whole
 * mechanism -- there is no package wiring, no version ranges, and nothing for a plugin to declare.
 * <p>
 * Registered as parallel-capable: a plugin's classes are loaded from whichever request thread
 * touches them first, and locking per class name rather than per loader avoids deadlocking two
 * threads that load two classes in opposite order.
 */
public class DefaultPluginClassLoader extends URLClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final String pluginId;
    private final ClassLoaderPolicy policy;

    /**
     * @param pluginId  owning plugin, used in diagnostics
     * @param urls      the plugin's classpath: its jar, then any {@code lib/} entries
     * @param parent    the runtime's ClassLoader
     * @param policy    which packages resolve from {@code parent}
     */
    public DefaultPluginClassLoader(final String pluginId,
                                    final URL[] urls,
                                    final ClassLoader parent,
                                    final ClassLoaderPolicy policy) {
        super("lpr-plugin-" + Objects.requireNonNull(pluginId, "pluginId"), urls, parent);
        this.pluginId = pluginId;
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * @return the plugin this ClassLoader serves
     */
    public String getPluginId() {
        return pluginId;
    }

    @Override
    protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                loaded = policy.isParentFirst(name) ? loadParentFirst(name) : loadChildFirst(name);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    /**
     * Shared types. The parent's answer is authoritative; the plugin's own copy, if it shipped one,
     * is ignored rather than allowed to shadow it.
     */
    private Class<?> loadParentFirst(final String name) throws ClassNotFoundException {
        try {
            return getParent().loadClass(name);
        } catch (final ClassNotFoundException notInParent) {
            // A parent-first prefix the runtime does not actually export. Falling through lets a
            // plugin carry, say, its own org.killbill.* helper without the policy having to
            // enumerate exceptions.
            return findClass(name);
        }
    }

    /**
     * The plugin's own dependencies. Its copy wins, which is what lets two plugins hold
     * incompatible versions of the same library at the same time.
     */
    private Class<?> loadChildFirst(final String name) throws ClassNotFoundException {
        try {
            return findClass(name);
        } catch (final ClassNotFoundException notInPlugin) {
            return getParent().loadClass(name);
        }
    }

    @Override
    public URL getResource(final String name) {
        if (policy.isParentFirstResource(name)) {
            final URL fromParent = getParent().getResource(name);
            return fromParent != null ? fromParent : findResource(name);
        }
        final URL fromPlugin = findResource(name);
        return fromPlugin != null ? fromPlugin : getParent().getResource(name);
    }

    /**
     * Both sides are always returned; only the order changes. Callers that enumerate resources --
     * {@code ServiceLoader} above all -- need to see every provider, not just the nearest one.
     */
    @Override
    public Enumeration<URL> getResources(final String name) throws IOException {
        final Enumeration<URL> fromParent = getParent().getResources(name);
        final Enumeration<URL> fromPlugin = findResources(name);

        final List<URL> ordered = new ArrayList<>();
        if (policy.isParentFirstResource(name)) {
            addAll(ordered, fromParent);
            addAll(ordered, fromPlugin);
        } else {
            addAll(ordered, fromPlugin);
            addAll(ordered, fromParent);
        }
        return Collections.enumeration(ordered);
    }

    private static void addAll(final List<URL> target, final Enumeration<URL> source) {
        while (source.hasMoreElements()) {
            final URL url = source.nextElement();
            if (!target.contains(url)) {
                target.add(url);
            }
        }
    }

    @Override
    public String toString() {
        return "DefaultPluginClassLoader{pluginId=" + pluginId + '}';
    }
}

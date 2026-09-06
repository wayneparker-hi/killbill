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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Wraps a plugin's service so that every call into it runs with the plugin's own ClassLoader as the
 * thread context ClassLoader.
 * <p>
 * Without this, a plugin method invoked from a Kill Bill request thread would see the core's
 * ClassLoader as the TCCL. That breaks any library that resolves classes reflectively rather than
 * through its own imports -- {@code ServiceLoader}, JDBC's {@code DriverManager}, JAXB and XML
 * factory lookups, Jackson's subtype resolution, most template engines. The plugin's own classes
 * are invisible to the core's ClassLoader, so those lookups fail, and they fail with
 * {@code ClassNotFoundException} pointing at a class the plugin obviously does ship.
 * <p>
 * The predecessor did the same thing in {@code ContextClassLoaderHelper}. It is carried over rather
 * than dropped: the need comes from how Java resolves classes reflectively, not from OSGi.
 * <p>
 * The caller's TCCL is always restored, including on exception, so a plugin cannot leave a Kill
 * Bill request thread pointing at a ClassLoader that is about to be closed.
 */
final class PluginServiceContextClassLoaderProxy implements InvocationHandler {

    private final Object service;
    private final ClassLoader pluginClassLoader;

    private PluginServiceContextClassLoaderProxy(final Object service, final ClassLoader pluginClassLoader) {
        this.service = service;
        this.pluginClassLoader = pluginClassLoader;
    }

    /**
     * Wraps a service, or returns it unchanged when wrapping would buy nothing.
     *
     * @param service     the plugin's implementation
     * @param serviceType the interface it was registered under
     * @param <T>         service type
     * @return a proxy switching the TCCL around every call
     */
    @SuppressWarnings("unchecked")
    static <T> T wrap(final T service, final Class<T> serviceType) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(serviceType, "serviceType");

        final ClassLoader pluginClassLoader = service.getClass().getClassLoader();
        if (pluginClassLoader == null || pluginClassLoader == PluginServiceContextClassLoaderProxy.class.getClassLoader()) {
            // A service the core itself provides -- the external payment plugin, the no-op invoice
            // provider. Same ClassLoader on both sides, so there is nothing to switch, and a proxy
            // would only add a frame to every stack trace.
            return service;
        }

        final Set<Class<?>> interfaces = new LinkedHashSet<>();
        interfaces.add(serviceType);
        collectInterfaces(service.getClass(), interfaces);

        return (T) Proxy.newProxyInstance(pluginClassLoader,
                                          interfaces.toArray(new Class<?>[0]),
                                          new PluginServiceContextClassLoaderProxy(service, pluginClassLoader));
    }

    @Override
    public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
        final Thread current = Thread.currentThread();
        final ClassLoader original = current.getContextClassLoader();
        try {
            current.setContextClassLoader(pluginClassLoader);
            return method.invoke(service, args);
        } catch (final InvocationTargetException e) {
            // Unwrap, so callers see the exception the plugin actually threw rather than a
            // reflection wrapper around it. Kill Bill's call sites catch specific plugin
            // exceptions; a wrapped one would fall through to the generic handler.
            throw e.getCause() != null ? e.getCause() : e;
        } finally {
            current.setContextClassLoader(original);
        }
    }

    /**
     * All interfaces the implementation carries, so the proxy can stand in wherever the original
     * could. Registered under one type, a plugin service is often cast to another it also
     * implements -- {@code Healthcheck} alongside {@code PaymentPluginApi}, say.
     */
    private static void collectInterfaces(final Class<?> type, final Set<Class<?>> found) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            final List<Class<?>> direct = new ArrayList<>(List.of(current.getInterfaces()));
            for (final Class<?> candidate : direct) {
                if (found.add(candidate)) {
                    collectInterfaces(candidate, found);
                }
            }
        }
    }
}

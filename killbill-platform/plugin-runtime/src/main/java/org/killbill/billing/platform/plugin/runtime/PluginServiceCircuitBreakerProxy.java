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
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import org.killbill.billing.platform.plugin.api.PluginServiceDescriptor;

/**
 * Wraps a plugin service so that every call into it is guarded by a timeout and a circuit breaker.
 * <p>
 * The proxy is installed around the context-ClassLoader proxy (which is installed around the real
 * service). Every call therefore travels:
 * <pre>
 *   core request thread
 *     -> circuit breaker check
 *     -> submit to plugin invocation executor with timeout
 *     -> context-ClassLoader proxy (sets TCCL)
 *     -> plugin implementation
 * </pre>
 * <p>
 * This ordering keeps the timeout interrupt on the plugin call itself and lets the plugin's own
 * ClassLoader remain the TCCL for the duration of the call, matching the behaviour of the previous
 * OSGi layer.
 */
final class PluginServiceCircuitBreakerProxy implements InvocationHandler {

    private final Object service;
    private final ClassLoader classLoader;
    private final PluginServiceDescriptor descriptor;
    private final PluginInvocationGuard guard;

    private PluginServiceCircuitBreakerProxy(final Object service,
                                             final ClassLoader classLoader,
                                             final PluginServiceDescriptor descriptor,
                                             final PluginInvocationGuard guard) {
        this.service = Objects.requireNonNull(service, "service");
        this.classLoader = classLoader;
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    /**
     * Wraps a service with timeout and circuit-breaker protection.
     *
     * @param service     the service to protect (usually already wrapped by the TCCL proxy)
     * @param serviceType the interface it is registered under
     * @param descriptor  identifies the plugin providing the service
     * @param guard       the shared invocation guard
     * @param <T>         service type
     * @return a protected proxy, or the original service if guard is null
     */
    @SuppressWarnings("unchecked")
    static <T> T wrap(final T service,
                      final Class<T> serviceType,
                      final PluginServiceDescriptor descriptor,
                      final PluginInvocationGuard guard) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(serviceType, "serviceType");
        Objects.requireNonNull(descriptor, "descriptor");
        if (guard == null) {
            return service;
        }

        final Set<Class<?>> interfaces = new LinkedHashSet<>();
        interfaces.add(serviceType);
        collectInterfaces(service.getClass(), interfaces);

        return (T) Proxy.newProxyInstance(service.getClass().getClassLoader(),
                                          interfaces.toArray(new Class<?>[0]),
                                          new PluginServiceCircuitBreakerProxy(service,
                                                                               service.getClass().getClassLoader(),
                                                                               descriptor,
                                                                               guard));
    }

    @Override
    public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
        return guard.call(descriptor.getPluginId(), () -> {
            try {
                return method.invoke(service, args);
            } catch (final InvocationTargetException e) {
                final Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                if (cause instanceof Error) {
                    throw (Error) cause;
                }
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                }
                throw new PluginInvocationException("Unexpected plugin error", cause);
            }
        });
    }

    private static void collectInterfaces(final Class<?> type, final Set<Class<?>> found) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (final Class<?> candidate : current.getInterfaces()) {
                if (found.add(candidate)) {
                    collectInterfaces(candidate, found);
                }
            }
        }
    }
}

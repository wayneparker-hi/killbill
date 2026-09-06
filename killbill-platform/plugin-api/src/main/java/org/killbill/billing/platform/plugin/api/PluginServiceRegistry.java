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

import java.util.Set;

/**
 * Every implementation of one plugin service type, keyed by the name configuration refers to.
 * <p>
 * This is the shape Kill Bill's business modules need. A payment call knows a plugin <i>name</i>
 * -- from the payment method, from configuration, from the request -- and has to reach that
 * plugin's {@code PaymentPluginApi}. One registry per service type, injected where it is needed,
 * keeps that lookup a single map read on a hot path.
 * <p>
 * The runtime keeps these registries in step with plugin lifecycle: entries appear while a plugin
 * is starting and are withdrawn before it stops, so a lookup never returns an implementation whose
 * ClassLoader is about to close.
 * <p>
 * Implementations must be safe for concurrent use: registration happens on lifecycle threads while
 * lookups happen on request threads.
 *
 * @param <T> the plugin service type, such as {@code PaymentPluginApi}
 */
public interface PluginServiceRegistry<T> {

    /**
     * Publishes an implementation. Called by the runtime, not by business code.
     *
     * @param descriptor who is publishing, and under what name
     * @param service    the implementation
     */
    void registerService(PluginServiceDescriptor descriptor, T service);

    /**
     * Withdraws an implementation.
     *
     * @param registrationName the name it was registered under
     */
    void unregisterService(String registrationName);

    /**
     * @param registrationName the configured plugin name
     * @return the implementation, or null if no plugin currently provides one under that name
     */
    T getServiceForName(String registrationName);

    /**
     * @return the names currently registered
     */
    Set<String> getAllServices();

    /**
     * @return the service type this registry holds
     */
    Class<T> getServiceType();
}

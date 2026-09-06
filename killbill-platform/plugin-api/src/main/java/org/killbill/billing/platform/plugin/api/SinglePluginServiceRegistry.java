/*
 * Copyright 2010-2013 Ning, Inc.
 * Copyright 2015 Groupon, Inc
 * Copyright 2015 The Billing Project, LLC
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
 * A plugin service of which there can be only one.
 * <p>
 * Distinct from {@link PluginServiceRegistry} because the two answer different questions. A payment
 * API is looked up <em>by name</em>, since a deployment runs several and the caller knows which one
 * it wants. A metric registry is not: there is one, whichever plugin supplied it, and callers ask
 * for "the" one.
 *
 * @param <T> the service type
 */
public interface SinglePluginServiceRegistry<T> {

    /**
     * Publishes the implementation. A second registration replaces the first.
     *
     * @param descriptor who is publishing
     * @param service    the implementation
     */
    void registerService(PluginServiceDescriptor descriptor, T service);

    /**
     * Withdraws the implementation.
     *
     * @param registrationName the name it was registered under
     */
    void unregisterService(String registrationName);

    /**
     * @return the implementation, or null if no plugin currently provides one
     */
    T getService();

    /**
     * Runs an action whenever the implementation changes, and once immediately if one is already
     * registered.
     * <p>
     * Needed because some registrations are one-shot. A gauge is handed to the metric registry once
     * and the caller keeps no reference to re-register it, so if the providing plugin has not
     * started yet the gauge would simply never appear. A callback lets the registration be replayed
     * when the plugin arrives.
     * <p>
     * Only the single-valued registry offers this: a named lookup can be retried on the next call,
     * so it has no equivalent problem.
     *
     * @param listener the action; must be idempotent, since it may run more than once
     */
    void addRegistrationListener(Runnable listener);

    /**
     * @return the service type this registry holds
     */
    Class<T> getServiceType();
}

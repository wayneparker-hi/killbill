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

package org.killbill.billing.lpr.core;

import org.killbill.billing.lpr.api.ServiceReference;

/**
 * Notified as plugins publish and withdraw services.
 * <p>
 * This is how a host wires plugin services into structures of its own. Kill Bill, for instance,
 * keeps one registry per service type and injects it wherever that type is called from, so that a
 * payment call is a single map lookup rather than a scan; a listener keeps those per-type registries
 * in step without the runtime knowing they exist.
 * <p>
 * Callbacks run on the thread performing the registration, which during startup is the lifecycle
 * thread and during a hot update is whoever triggered it. Implementations must be quick and must
 * not throw -- the runtime logs and continues, because a host-side wiring failure must not leave a
 * plugin half-registered.
 */
public interface ServiceRegistryListener {

    /**
     * A plugin published a service. Fires while the plugin is {@code STARTING}, before it becomes
     * {@code ACTIVE}.
     *
     * @param reference the new registration
     */
    void onRegistered(ServiceReference<?> reference);

    /**
     * A plugin's service was withdrawn, because the plugin is stopping or retracted it.
     *
     * @param reference the registration being removed
     */
    void onUnregistered(ServiceReference<?> reference);
}

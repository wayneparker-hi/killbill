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

import org.killbill.billing.lpr.api.ServiceRegistration;

/**
 * Handle returned to whoever published a service.
 * <p>
 * Plugins rarely close these by hand -- the lifecycle withdraws everything a plugin published when
 * it stops. This exists for the case of retracting one service while staying active, for instance
 * a plugin that fails its own health check and wants to stop receiving traffic without shutting
 * down.
 */
final class DefaultServiceRegistration implements ServiceRegistration {

    private final DefaultServiceRegistry registry;
    private final DefaultServiceReference<?> reference;
    private volatile boolean active = true;

    DefaultServiceRegistration(final DefaultServiceRegistry registry,
                               final DefaultServiceReference<?> reference) {
        this.registry = registry;
        this.reference = reference;
    }

    @Override
    public String pluginId() {
        return reference.pluginId();
    }

    @Override
    public Class<?> type() {
        return reference.type();
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public synchronized void close() {
        if (!active) {
            return;
        }
        active = false;
        registry.unregister(reference);
    }

    @Override
    public String toString() {
        return "ServiceRegistration{" + reference + ", active=" + active + '}';
    }
}

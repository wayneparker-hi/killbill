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

package org.killbill.billing.lpr.api;

/**
 * Optional persistent delivery to a stable plugin/subscription address. No ordering guarantee.
 * Successful publish acknowledges storage, not processing. Consumers must be idempotent.
 * Implementations are thread-safe, reject overload without blocking, and expire on plugin stop.
 */
public interface DurableEventBus extends AutoCloseable {
    /** Throws IllegalStateException on unavailable storage or overload; never silently drops. */
    void publish(String targetPluginId, String subscriptionId, DurableEvent event);

    /** One active subscription per id in this plugin version; automatically released on stop. */
    Subscription subscribe(String subscriptionId, DurableEventHandler handler);

    /** Prevents new work, releases callbacks; in-flight calls may finish. Never throws. */
    @Override
    void close();
}

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
 * Handle for a published service. Closing withdraws it.
 * <p>
 * Plugins rarely need to close these by hand: the runtime withdraws every registration made by a
 * plugin when that plugin stops. Close explicitly only to retract a service while staying active.
 * <p>
 * Closing twice is a no-op.
 */
public interface ServiceRegistration extends AutoCloseable {

    /**
     * @return the publishing plugin's id
     */
    String pluginId();

    /**
     * @return the service interface
     */
    Class<?> type();

    /**
     * @return whether this registration is still live
     */
    boolean isActive();

    /**
     * Withdraws the service. Never throws.
     */
    @Override
    void close();
}

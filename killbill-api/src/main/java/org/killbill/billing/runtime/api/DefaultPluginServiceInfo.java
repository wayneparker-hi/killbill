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

package org.killbill.billing.runtime.api;

import java.util.Objects;

/**
 * One service a plugin registered.
 *
 * @param serviceTypeName  the service interface, such as {@code ...PaymentPluginApi}
 * @param registrationName the name it was registered under
 */
public record DefaultPluginServiceInfo(String serviceTypeName, String registrationName)
        implements PluginServiceInfo {

    public DefaultPluginServiceInfo {
        Objects.requireNonNull(serviceTypeName, "serviceTypeName");
        Objects.requireNonNull(registrationName, "registrationName");
    }

    @Override
    public String getServiceTypeName() {
        return serviceTypeName;
    }

    @Override
    public String getRegistrationName() {
        return registrationName;
    }
}

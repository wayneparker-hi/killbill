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

import java.util.HashMap;
import java.util.Map;
import org.killbill.billing.lpr.api.ServiceApiVersion;

/** Host-owned required versions, parsed once at assembly time. Keys are binary interface names. */
public record ServiceApiRequirements(Map<String, ServiceApiVersion> versions) {
    public ServiceApiRequirements { versions = Map.copyOf(versions); }

    public static ServiceApiRequirements parse(final String configured) {
        final Map<String, ServiceApiVersion> versions = new HashMap<>();
        if (configured == null || configured.isBlank()) { return new ServiceApiRequirements(versions); }
        for (final String entry : configured.split(",", -1)) {
            final String[] pair = entry.trim().split("=", -1);
            if (pair.length != 2 || !pair[0].trim().matches("[a-zA-Z_$][a-zA-Z0-9_$]*(\\.[a-zA-Z_$][a-zA-Z0-9_$]*)+")) {
                throw new IllegalArgumentException("Expected service API requirements as interface=MAJOR.MINOR.PATCH");
            }
            if (versions.putIfAbsent(pair[0].trim(), ServiceApiVersion.parse(pair[1].trim())) != null) {
                throw new IllegalArgumentException("Duplicate service API requirement: " + pair[0].trim());
            }
        }
        return new ServiceApiRequirements(versions);
    }
}

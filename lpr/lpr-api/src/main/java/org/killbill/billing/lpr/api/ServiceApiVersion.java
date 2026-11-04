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

import java.util.Map;
import java.util.Objects;

/** Released service contract version, independent of the plugin's deployment version. */
public record ServiceApiVersion(int major, int minor, int patch) implements Comparable<ServiceApiVersion> {
    public static final String PROPERTY = "service.api.version";
    public static final ServiceApiVersion LEGACY = new ServiceApiVersion(1, 0, 0);

    public ServiceApiVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Service API version components must not be negative");
        }
    }

    public static ServiceApiVersion parse(final String value) {
        Objects.requireNonNull(value, "value");
        if (!value.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")) {
            throw new IllegalArgumentException("Service API version must be released MAJOR.MINOR.PATCH: " + value);
        }
        final String[] parts = value.split("\\.");
        return new ServiceApiVersion(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    public static ServiceApiVersion fromProperties(final Map<String, Object> properties) {
        if (properties == null || !properties.containsKey(PROPERTY)) { return LEGACY; }
        if (!(properties.get(PROPERTY) instanceof String value)) {
            throw new IllegalArgumentException(PROPERTY + " must be a version string");
        }
        return parse(value);
    }

    /** Provider-side compatibility: stable same-major releases support older consumer contracts. */
    public boolean supports(final ServiceApiVersion required) {
        Objects.requireNonNull(required, "required");
        return major == required.major && (major == 0 ? equals(required) : compareTo(required) >= 0);
    }

    @Override
    public int compareTo(final ServiceApiVersion other) {
        final int byMajor = Integer.compare(major, other.major);
        final int byMinor = Integer.compare(minor, other.minor);
        return byMajor != 0 ? byMajor : byMinor != 0 ? byMinor : Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() { return major + "." + minor + "." + patch; }
}

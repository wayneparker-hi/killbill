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

import java.util.List;
import org.killbill.billing.lpr.api.ServiceApiVersion;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class TestServiceApiVersion {
    @Test(groups = "fast")
    public void testCompatibilityDirectionAndNumericComparison() {
        final var required = ServiceApiVersion.parse("1.2.3");
        for (final String version : List.of("1.2.3", "1.2.4", "1.10.0")) {
            assertTrue(ServiceApiVersion.parse(version).supports(required));
        }
        for (final String version : List.of("1.2.2", "1.1.9", "0.9.9", "2.0.0")) {
            assertFalse(ServiceApiVersion.parse(version).supports(required));
        }
        assertEquals(required.toString(), "1.2.3");
        assertEquals(new ServiceApiVersion(1, 2, 3), required);
    }

    @Test(groups = "fast")
    public void testUnstableContractsRequireExactVersion() {
        final var required = ServiceApiVersion.parse("0.2.1");
        assertTrue(required.supports(required));
        for (final String version : List.of("0.2.0", "0.2.2", "0.3.0", "1.2.1")) {
            assertFalse(ServiceApiVersion.parse(version).supports(required));
        }
    }

    @Test(groups = "fast")
    public void testOnlyCanonicalReleasedVersionsAreAccepted() {
        for (final String value : List.of("", "1", "1.2", "01.2.3", "1.02.3", "1.2.03", "-1.2.3", "1.2.3-SNAPSHOT",
                                         "1.2.3+build", "1.2.3.4", "1.2.2147483648", " 1.2.3 ")) {
            assertThrows(IllegalArgumentException.class, () -> ServiceApiVersion.parse(value));
        }
        assertThrows(IllegalArgumentException.class, () -> new ServiceApiVersion(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ServiceApiVersion(1, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ServiceApiVersion(1, 0, -1));
    }
}

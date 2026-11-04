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

import java.util.List;
import org.killbill.billing.lpr.api.ServiceApiVersion;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class TestServiceApiRequirements {
    @Test(groups = "fast")
    public void testParsingAndImmutablePolicy() {
        final var requirements = ServiceApiRequirements.parse(" example.Api = 1.2.3,example.Outer$Inner=2.0.0 ");
        assertEquals(requirements.versions().get("example.Api"), new ServiceApiVersion(1, 2, 3));
        assertEquals(requirements.versions().size(), 2);
        assertThrows(UnsupportedOperationException.class, () -> requirements.versions().clear());
        assertTrue(ServiceApiRequirements.parse(null).versions().isEmpty());
        assertTrue(ServiceApiRequirements.parse("  ").versions().isEmpty());
    }

    @Test(groups = "fast")
    public void testMalformedAndDuplicateRequirementsAreRejected() {
        for (final String value : List.of("example.Api", "example.Api=1.0", "example.Api=1.0.0-SNAPSHOT", "example.Api=1.0.0,",
                                         "=1.0.0", "bad name.Api=1.0.0", "example.Api=1.0.0,example.Api=2.0.0", "example.Api==1.0.0")) {
            assertThrows(IllegalArgumentException.class, () -> ServiceApiRequirements.parse(value));
        }
    }
}

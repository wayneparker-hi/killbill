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

package org.killbill.billing.lpr.spi;

import java.util.List;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

public class TestSemanticVersion {

    /**
     * The defect this class exists to fix. The OSGi layer ordered versions with
     * {@code String.compareTo}, so a plugin that reached 10.x would silently start an older
     * version and nothing would look wrong.
     */
    @Test(groups = "fast")
    public void testDoubleDigitVersionsOrderNumericallyNotLexicographically() {
        assertTrue("10.0.0".compareTo("9.0.0") < 0,
                   "Precondition: string ordering is wrong here, which is the whole point");

        assertTrue(SemanticVersion.parse("10.0.0").compareTo(SemanticVersion.parse("9.0.0")) > 0);
        assertTrue(SemanticVersion.parse("1.10.0").compareTo(SemanticVersion.parse("1.9.0")) > 0);
        assertTrue(SemanticVersion.parse("1.0.10").compareTo(SemanticVersion.parse("1.0.9")) > 0);
    }

    @Test(groups = "fast")
    public void testVersionsSortHighestLast() {
        final List<SemanticVersion> sorted = java.util.stream.Stream
                .of("1.9.0", "10.0.0", "1.10.0", "2.0.0", "1.0.0")
                .map(SemanticVersion::parse)
                .sorted()
                .toList();

        assertEquals(sorted.stream().map(SemanticVersion::toString).toList(),
                     List.of("1.0.0", "1.9.0", "1.10.0", "2.0.0", "10.0.0"));
    }

    /** A release must outrank the snapshot it came from, or a deployment would prefer the snapshot. */
    @Test(groups = "fast")
    public void testAReleaseOutranksItsPreRelease() {
        assertTrue(SemanticVersion.parse("1.0.0").compareTo(SemanticVersion.parse("1.0.0-SNAPSHOT")) > 0);
        assertTrue(SemanticVersion.parse("1.0.0-alpha").compareTo(SemanticVersion.parse("1.0.0-beta")) < 0);
        assertTrue(SemanticVersion.parse("1.0.0-alpha.1").compareTo(SemanticVersion.parse("1.0.0-alpha")) > 0);
        assertTrue(SemanticVersion.parse("1.0.0-alpha.2").compareTo(SemanticVersion.parse("1.0.0-alpha.10")) < 0);
    }

    /** Kill Bill artifacts are frequently versioned with two components. */
    @Test(groups = "fast")
    public void testPatchDefaultsToZero() {
        assertEquals(SemanticVersion.parse("1.2"), SemanticVersion.parse("1.2.0"));
    }

    /** Required by the spec: build metadata does not affect precedence. */
    @Test(groups = "fast")
    public void testBuildMetadataIsIgnoredWhenComparing() {
        assertEquals(SemanticVersion.parse("1.0.0+build.1").compareTo(SemanticVersion.parse("1.0.0+build.2")), 0);
    }

    @Test(groups = "fast")
    public void testUnparseableVersionsAreRejectedRatherThanGuessed() {
        assertFalse(SemanticVersion.isValid("SET_DEFAULT"));
        assertFalse(SemanticVersion.isValid("latest"));
        assertFalse(SemanticVersion.isValid("v1.0.0"));
        assertFalse(SemanticVersion.isValid(""));
        assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("latest"));
    }

    @Test(groups = "fast")
    public void testToStringRoundTrips() {
        for (final String raw : List.of("1.2.3", "1.2.3-SNAPSHOT", "0.0.1", "10.20.30")) {
            assertEquals(SemanticVersion.parse(raw).toString(), raw);
        }
    }
}

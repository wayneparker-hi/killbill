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

/** Persistent desired state. A null active version selects the highest enabled installed version. */
public record RepositorySelection(long revision, String activeVersion, String previousVersion, boolean enabled) {
    public RepositorySelection {
        if (revision < 0) { throw new IllegalArgumentException("Negative selection revision"); }
        if (activeVersion != null) { SemanticVersion.parse(activeVersion); }
        if (previousVersion != null) { SemanticVersion.parse(previousVersion); }
    }

    public static RepositorySelection initial() { return new RepositorySelection(0, null, null, true); }
}

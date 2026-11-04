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

import java.util.Objects;
import java.util.UUID;

/** Stable data only. Consumers must deduplicate by id; payload is application-defined text. */
public record DurableEvent(UUID id, String topic, String payload) {
    public DurableEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(payload, "payload");
        if (topic.isBlank() || topic.length() > 128) {
            throw new IllegalArgumentException("Event topic must contain 1..128 characters");
        }
    }
}

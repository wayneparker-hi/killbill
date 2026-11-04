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

import java.util.Objects;
import java.util.UUID;

import org.killbill.billing.lpr.api.DurableEvent;
import org.killbill.bus.api.BusEvent;
import com.fasterxml.jackson.annotation.JsonIgnore;

/** Fixed platform-loaded wire format. Never contains plugin implementation types. */
public record PersistentPluginEvent(int schemaVersion, String sourcePluginId, String targetPluginId,
                                    String subscriptionId, UUID id, String topic, String payload) implements BusEvent {
    public PersistentPluginEvent {
        validateAddress(sourcePluginId);
        validateAddress(targetPluginId);
        validateAddress(subscriptionId);
        Objects.requireNonNull(id, "id");
        new DurableEvent(id, topic, payload);
    }

    static void validateAddress(final String value) {
        if (value == null || !value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Event address must contain 1..128 letters, digits, '.', '_' or '-'");
        }
    }

    DurableEvent message() { return new DurableEvent(id, topic, payload); }

    @Override
    @JsonIgnore
    public UUID getUserToken() { return id; }
    @Override
    @JsonIgnore
    public Long getSearchKey1() { return null; }
    @Override
    @JsonIgnore
    public Long getSearchKey2() { return null; }
}

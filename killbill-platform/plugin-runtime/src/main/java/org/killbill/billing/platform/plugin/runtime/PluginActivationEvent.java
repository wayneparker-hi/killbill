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
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.killbill.billing.platform.plugin.runtime;

import java.time.Instant;
import java.util.Objects;

/**
 * Audit event published on the LPR event bus when a plugin version is upgraded or rolled back.
 */
public final class PluginActivationEvent {

    public enum Type {
        UPGRADE,
        ROLLBACK
    }

    private final Type type;
    private final String pluginId;
    private final String fromVersion;
    private final String toVersion;
    private final Instant timestamp;
    private final String reason;

    private PluginActivationEvent(final Type type,
                                  final String pluginId,
                                  final String fromVersion,
                                  final String toVersion,
                                  final Instant timestamp,
                                  final String reason) {
        this.type = Objects.requireNonNull(type, "type");
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.fromVersion = fromVersion;
        this.toVersion = Objects.requireNonNull(toVersion, "toVersion");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        this.reason = reason;
    }

    public static PluginActivationEvent upgrade(final String pluginId,
                                                final String fromVersion,
                                                final String toVersion) {
        return new PluginActivationEvent(Type.UPGRADE, pluginId, fromVersion, toVersion, Instant.now(), null);
    }

    public static PluginActivationEvent rollback(final String pluginId,
                                                 final String fromVersion,
                                                 final String toVersion,
                                                 final String reason) {
        return new PluginActivationEvent(Type.ROLLBACK, pluginId, fromVersion, toVersion, Instant.now(), reason);
    }

    public Type type() {
        return type;
    }

    public String pluginId() {
        return pluginId;
    }

    public String fromVersion() {
        return fromVersion;
    }

    public String toVersion() {
        return toVersion;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public String reason() {
        return reason;
    }

    @Override
    public String toString() {
        return "PluginActivationEvent{" + type + ", plugin=" + pluginId
               + ", from=" + fromVersion + ", to=" + toVersion
               + ", reason=" + reason + '}';
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PluginActivationEvent)) {
            return false;
        }
        final PluginActivationEvent that = (PluginActivationEvent) o;
        return type == that.type
               && pluginId.equals(that.pluginId)
               && Objects.equals(fromVersion, that.fromVersion)
               && toVersion.equals(that.toVersion)
               && Objects.equals(reason, that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, pluginId, fromVersion, toVersion, reason);
    }
}

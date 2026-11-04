/*
 * Copyright 2014-2015 Groupon, Inc
 * Copyright 2014-2015 The Billing Project, LLC
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

package org.killbill.billing.util.nodes.json;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.killbill.billing.runtime.api.PluginInfo;
import org.killbill.billing.runtime.api.PluginState;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class PluginInfoModelJson {


    private final String pluginKey;

    private final String pluginName;

    private final String version;

    private final PluginState state;

    private final Set<PluginServiceInfoModelJson> services;

    private final Boolean isSelectedForStart;

    private final Long runtimeRevision;
    private final String runtimeState;

    public PluginInfoModelJson(final String pluginKey, final String pluginName, final String version,
                               final PluginState state, final Boolean selected, final Set<PluginServiceInfoModelJson> services) {
        this(pluginKey, pluginName, version, state, selected, services, null, null);
    }

    public Long getRuntimeRevision() { return runtimeRevision; }
    public String getRuntimeState() { return runtimeState; }

    @JsonCreator
    public PluginInfoModelJson(
                               @JsonProperty("pluginKey") final String pluginKey,
                               @JsonProperty("pluginName") final String pluginName,
                               @JsonProperty("version") final String version,
                               @JsonProperty("state") final PluginState state,
                               @JsonProperty("isSelectedForStart") final Boolean isSelectedForStart,
                               @JsonProperty("services") final Set<PluginServiceInfoModelJson> services,
                               @JsonProperty("runtimeRevision") final Long runtimeRevision,
                               @JsonProperty("runtimeState") final String runtimeState) {
        this.runtimeRevision = runtimeRevision;
        this.runtimeState = runtimeState;
        this.pluginKey = pluginKey;
        this.pluginName = pluginName;
        this.version = version;
        this.state = state;
        this.isSelectedForStart = isSelectedForStart;
        this.services = services;
    }

    public PluginInfoModelJson(final PluginInfo input) {
        this(             input.getPluginKey(),
             input.getPluginName(),
             input.getVersion(),
             input.getPluginState(),
             input.isSelectedForStart(),
             input.getServices()
                  .stream()
                  .map(serviceInfo -> new PluginServiceInfoModelJson(serviceInfo.getServiceTypeName(), serviceInfo.getRegistrationName()))
                  .collect(Collectors.toUnmodifiableSet()),
             input.getRuntimeRevision(), input.getRuntimeState()
            );
    }

    public String getPluginName() {
        return pluginName;
    }

    public String getVersion() {
        return version;
    }

    public PluginState getState() {
        return state;
    }

    public String getPluginKey() {
        return pluginKey;
    }

    @JsonProperty("isSelectedForStart")
    public boolean isSelectedForStart() {
        return Boolean.TRUE.equals(isSelectedForStart);
    }

    public Set<PluginServiceInfoModelJson> getServices() {
        return services;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PluginInfoModelJson)) {
            return false;
        }

        final PluginInfoModelJson that = (PluginInfoModelJson) o;
        if (state != null ? !state.equals(that.state) : that.state != null) {
            return false;
        }
        if (!Objects.equals(isSelectedForStart, that.isSelectedForStart)) {
            return false;
        }
        if (pluginKey != null ? !pluginKey.equals(that.pluginKey) : that.pluginKey != null) {
            return false;
        }
        if (pluginName != null ? !pluginName.equals(that.pluginName) : that.pluginName != null) {
            return false;
        }
        if (version != null ? !version.equals(that.version) : that.version != null) {
            return false;
        }
        return Objects.equals(services, that.services) && Objects.equals(runtimeRevision, that.runtimeRevision)
               && Objects.equals(runtimeState, that.runtimeState);

    }

    @Override
    public int hashCode() {
        return Objects.hash(pluginKey, pluginName, version, state, services, isSelectedForStart, runtimeRevision, runtimeState);
    }
}

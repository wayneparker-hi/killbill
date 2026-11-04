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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.killbill.billing.lpr.spi.PluginRepository;
import org.killbill.billing.lpr.spi.RepositorySelection;
import org.killbill.billing.runtime.api.ClusterPluginStatus;
import org.killbill.billing.runtime.api.NodePluginStatus;
import org.killbill.billing.runtime.api.PluginInfo;
import org.killbill.billing.util.nodes.KillbillNodesApi;
import org.killbill.billing.util.nodes.NodeInfo;
import org.killbill.clock.Clock;

/** A reported observation, not a distributed transaction or a liveness guarantee. */
public final class PluginClusterStatusService {
    private final PluginRepository repository;
    private final Provider<KillbillNodesApi> nodesApi;
    private final Clock clock;
    private final long staleAfter;

    @Inject
    public PluginClusterStatusService(final PluginRepository repository, final Provider<KillbillNodesApi> nodesApi,
                                      final Clock clock, final PluginRuntimeConfig config) {
        this.repository = repository;
        this.nodesApi = nodesApi;
        this.clock = clock;
        staleAfter = config.getPluginReportStaleAfter();
        if (staleAfter <= 0) { throw new IllegalArgumentException("Report stale interval must be positive"); }
    }

    public List<ClusterPluginStatus> getStatus() {
        final long now = clock.getUTCNow().getMillis();
        final List<NodeInfo> nodes = new ArrayList<>();
        nodesApi.get().getNodesInfo().forEach(nodes::add);
        nodes.sort(java.util.Comparator.comparing(NodeInfo::getNodeName));
        final Set<String> ids = new TreeSet<>(repository.pluginIds());
        for (final NodeInfo node : nodes) {
            node.getPluginInfo().forEach(plugin -> ids.add(idOf(plugin)));
        }
        final List<ClusterPluginStatus> result = new ArrayList<>();
        for (final String id : ids) {
            final RepositorySelection desired = repository.selection(id);
            final List<String> installed = repository.installedVersions(id);
            final String version = desired.activeVersion() != null ? desired.activeVersion()
                    : (installed.isEmpty() ? null : installed.get(0));
            final List<NodePluginStatus> reports = nodes.stream().map(node -> report(node, id, desired, version, now)).toList();
            result.add(new ClusterPluginStatus(id, version, desired.revision(), desired.enabled(), now, reports,
                                               !reports.isEmpty() && reports.stream().allMatch(NodePluginStatus::converged)));
        }
        return List.copyOf(result);
    }

    private NodePluginStatus report(final NodeInfo node, final String id, final RepositorySelection desired,
                                    final String desiredVersion, final long now) {
        PluginInfo current = null;
        for (final PluginInfo plugin : node.getPluginInfo()) {
            if (id.equals(idOf(plugin)) && plugin.isSelectedForStart()) { current = plugin; break; }
        }
        final Long timestamp = node.getLastUpdatedDate() == null ? null : node.getLastUpdatedDate().getMillis();
        final boolean stale = timestamp == null || now < timestamp || now - timestamp > staleAfter;
        final String state = current == null ? "MISSING" : current.getRuntimeState();
        final Long revision = current == null ? null : current.getRuntimeRevision();
        final String version = current == null ? null : current.getVersion();
        final boolean stateMatches = desired.enabled()
                ? "ACTIVE".equals(state) && Objects.equals(version, desiredVersion)
                : Set.of("STOPPED", "INSTALLED", "RESOLVED").contains(state == null ? "UNKNOWN" : state);
        final boolean converged = !stale && revision != null && revision == desired.revision() && stateMatches;
        return new NodePluginStatus(node.getNodeName(), version, state, revision, timestamp, stale, converged);
    }

    private static String idOf(final PluginInfo info) {
        return info.getPluginKey() != null ? info.getPluginKey() : info.getPluginName();
    }
}

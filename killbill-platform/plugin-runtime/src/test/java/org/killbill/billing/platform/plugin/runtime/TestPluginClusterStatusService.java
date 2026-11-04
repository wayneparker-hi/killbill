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

import java.util.List;
import java.util.Set;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.killbill.billing.lpr.spi.PluginRepository;
import org.killbill.billing.lpr.spi.RepositorySelection;
import org.killbill.billing.runtime.api.DefaultPluginInfo;
import org.killbill.billing.runtime.api.PluginInfo;
import org.killbill.billing.runtime.api.PluginState;
import org.killbill.billing.util.nodes.KillbillNodesApi;
import org.killbill.billing.util.nodes.NodeInfo;
import org.killbill.clock.Clock;
import org.testng.annotations.Test;
import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

public class TestPluginClusterStatusService {
    private static final DateTime NOW = new DateTime(2026, 11, 4, 12, 0, DateTimeZone.UTC);

    @Test(groups = "fast")
    public void testFreshMatchingRevisionAndDetailedStateAreRequired() {
        final var fixture = new Fixture(true);
        fixture.nodes(node("fresh", 0, info("2.0.0", 7L, "ACTIVE")),
                      node("old-revision", 0, info("2.0.0", 6L, "ACTIVE")),
                      node("stale", 31000, info("2.0.0", 7L, "ACTIVE")),
                      node("stopping", 0, info("2.0.0", 7L, "STOPPING")),
                      node("legacy", 0, info("2.0.0", null, null)), node("missing", 0));
        final var status = fixture.service.getStatus().get(0);
        assertFalse(status.converged());
        assertEquals(status.revision(), 7L);
        assertEquals(status.nodes().size(), 6);
        assertTrue(status.nodes().stream().filter(n -> n.nodeName().equals("fresh")).findFirst().orElseThrow().converged());
        assertEquals(status.nodes().stream().filter(n -> n.converged()).count(), 1L);
        assertTrue(status.nodes().stream().filter(n -> n.nodeName().equals("stale")).findFirst().orElseThrow().stale());
        assertEquals(status.nodes().stream().filter(n -> n.nodeName().equals("missing")).findFirst().orElseThrow().state(), "MISSING");
    }

    @Test(groups = "fast")
    public void testDisabledNodesConvergeOnlyAfterStopCompletes() {
        final var fixture = new Fixture(false);
        fixture.nodes(node("a", 0, info("1.0.0", 7L, "STOPPING")));
        assertFalse(fixture.service.getStatus().get(0).converged());
        fixture.nodes(node("a", 0, info("1.0.0", 7L, "STOPPED")), node("b", 0, info("2.0.0", 7L, "INSTALLED")));
        assertTrue(fixture.service.getStatus().get(0).converged());
        fixture.nodes();
        assertFalse(fixture.service.getStatus().get(0).converged());
    }

    @Test(groups = "fast")
    public void testMissingTimestampAndFutureReportsAreNotFresh() {
        final var fixture = new Fixture(true);
        final NodeInfo missingTime = node("unknown-time", 0, info("2.0.0", 7L, "ACTIVE"));
        when(missingTime.getLastUpdatedDate()).thenReturn(null);
        fixture.nodes(missingTime, node("future", -1000, info("2.0.0", 7L, "ACTIVE")));
        assertTrue(fixture.service.getStatus().get(0).nodes().stream().allMatch(n -> n.stale() && !n.converged()));
    }

    @Test(groups = "fast")
    public void testCoordinatorRetriesFailedTickAndRespectsDisabledStartup() {
        final var manager = mock(org.killbill.billing.lpr.core.DefaultPluginManager.class);
        final var repository = mock(PluginRepository.class);
        final var nodes = mock(KillbillNodesApi.class);
        final var info = mock(org.killbill.billing.runtime.api.PluginsInfoApi.class);
        when(repository.shared()).thenReturn(true);
        when(info.getPluginsInfo()).thenReturn(List.of());
        final var coordinator = new PluginClusterCoordinator(manager, repository, new TestPluginRuntimeConfig(), () -> nodes, () -> info);
        try {
            doThrow(new IllegalStateException("temporary")).doNothing().when(manager).reconcile();
            coordinator.runOnce(); coordinator.runOnce();
            verify(manager, times(2)).reconcile(); verify(nodes).notifyPluginChanged(null, List.of());
        } finally { coordinator.stop(); }
        final var disabled = new PluginClusterCoordinator(manager, repository,
                new TestPluginRuntimeConfig() { @Override public boolean isPluginStartEnabled() { return false; } }, () -> nodes, () -> info);
        try { disabled.runOnce(); verify(manager, times(2)).reconcile(); }
        finally { disabled.stop(); }
    }

    private static PluginInfo info(final String version, final Long revision, final String state) {
        return new DefaultPluginInfo("demo", "Demo", version, PluginState.RUNNING, Set.of(), true, revision, state);
    }

    private static NodeInfo node(final String name, final int age, final PluginInfo... plugins) {
        final NodeInfo node = mock(NodeInfo.class);
        when(node.getNodeName()).thenReturn(name); when(node.getLastUpdatedDate()).thenReturn(NOW.minusMillis(age));
        when(node.getPluginInfo()).thenReturn(List.of(plugins)); return node;
    }

    private static final class Fixture {
        final KillbillNodesApi nodes = mock(KillbillNodesApi.class);
        final PluginClusterStatusService service;
        Fixture(final boolean enabled) {
            final var repository = mock(PluginRepository.class);
            when(repository.pluginIds()).thenReturn(List.of("demo"));
            when(repository.installedVersions("demo")).thenReturn(List.of("2.0.0", "1.0.0"));
            when(repository.selection("demo")).thenReturn(new RepositorySelection(7, "2.0.0", "1.0.0", enabled));
            final Clock clock = mock(Clock.class); when(clock.getUTCNow()).thenReturn(NOW);
            service = new PluginClusterStatusService(repository, () -> nodes, clock, new TestPluginRuntimeConfig());
        }
        void nodes(final NodeInfo... reports) { when(nodes.getNodesInfo()).thenReturn(List.of(reports)); }
    }
}

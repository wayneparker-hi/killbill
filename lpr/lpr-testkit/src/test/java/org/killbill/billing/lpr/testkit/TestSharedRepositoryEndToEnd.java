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

package org.killbill.billing.lpr.testkit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ConcurrentModificationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.killbill.billing.lpr.api.PluginState;
import org.killbill.billing.lpr.core.PluginActivationListener;
import org.killbill.billing.lpr.management.ArtifactSource;
import org.killbill.billing.lpr.management.PluginInstaller;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class TestSharedRepositoryEndToEnd {
    public interface Gateway { String value(); }
    public interface Gate { void enter() throws Exception; }

    @Test(groups = "fast")
    public void testBootAppliesSelectionChangedAfterDiscovery() throws Exception {
        final Path shared = Files.createTempDirectory("lpr-cluster-boot-");
        try (PluginTestRuntime producer = PluginTestRuntime.create();
             PluginTestRuntime a = PluginTestRuntime.shared(shared, Map.of())) {
            publish(producer, a, "1.0.0", false); a.discover();
            publish(producer, a, "2.0.0", false);
            final var before = a.repository().selection("shared-demo");
            a.repository().updateSelection("shared-demo", before.revision(), "2.0.0", "1.0.0", true);
            assertEquals(a.manager().startAll(List.of()), List.of("shared-demo"));
            assertEquals(value(a), "2.0.0");
            assertEquals(a.manager().appliedRevision("shared-demo"), before.revision() + 1);
        } finally { delete(shared); }
    }

    @Test(groups = "fast")
    public void testMissedBroadcastUpgradeRollbackAndDurableStop() throws Exception {
        final Path shared = Files.createTempDirectory("lpr-cluster-");
        try (PluginTestRuntime producer = PluginTestRuntime.create();
             PluginTestRuntime a = PluginTestRuntime.shared(shared, Map.of());
             PluginTestRuntime b = PluginTestRuntime.shared(shared, Map.of())) {
            publish(producer, a, "1.0.0", false);
            a.discover(); b.discover();
            assertEquals(a.manager().startAll(List.of()), List.of());
            a.manager().start("shared-demo");
            b.manager().reconcile();
            assertEquals(value(a), "1.0.0"); assertEquals(value(b), "1.0.0");
            publish(producer, a, "2.0.0", false);
            a.manager().activateVersion("shared-demo", "2.0.0");
            assertEquals(value(b), "1.0.0");
            b.manager().reconcile();
            assertEquals(value(b), "2.0.0");
            assertEquals(b.manager().appliedRevision("shared-demo"), a.repository().selection("shared-demo").revision());
            b.manager().rollback("shared-demo");
            a.manager().reconcile();
            assertEquals(value(a), "1.0.0"); assertEquals(value(b), "1.0.0");
            a.manager().stop("shared-demo");
            b.manager().reconcile();
            assertFalse(b.manager().isRunning("shared-demo"));
            try (PluginTestRuntime rebooted = PluginTestRuntime.shared(shared, Map.of())) {
                rebooted.discover(); assertEquals(rebooted.manager().startAll(List.of()), List.of());
                assertFalse(rebooted.manager().isRunning("shared-demo"));
                rebooted.manager().start("shared-demo");
                assertEquals(value(rebooted), "1.0.0");
            }
            assertTrue(a.repository().selection("shared-demo").enabled(), "Host shutdown must not disable cluster intent");
        } finally { delete(shared); }
    }

    @Test(groups = "fast")
    public void testSourceUninstallDoesNotBreakOtherNodesLazyClassLoading() throws Exception {
        final Path shared = Files.createTempDirectory("lpr-cluster-cache-");
        try (PluginTestRuntime producer = PluginTestRuntime.create();
             PluginTestRuntime a = PluginTestRuntime.shared(shared, Map.of());
             PluginTestRuntime b = PluginTestRuntime.shared(shared, Map.of())) {
            publish(producer, a, "1.0.0", false);
            a.discover(); a.manager().start("shared-demo"); b.manager().reconcile();
            a.manager().stop("shared-demo");
            new PluginInstaller(a.repository()).uninstall("shared-demo", "1.0.0");
            assertEquals(value(b), "1.0.0", "Lazy helper must load from the node cache after source removal");
            b.manager().reconcile();
            assertFalse(b.manager().isRunning("shared-demo"));
        } finally { delete(shared); }
    }

    @Test(groups = "fast")
    public void testConcurrentRemoteStopRejectsStaleCandidateWithoutLosingOldRoute() throws Exception {
        final Path shared = Files.createTempDirectory("lpr-cluster-cas-");
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Gate gate = () -> { entered.countDown(); if (!release.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("gate timeout"); } };
        final var worker = Executors.newSingleThreadExecutor();
        try (PluginTestRuntime producer = PluginTestRuntime.create();
             PluginTestRuntime a = PluginTestRuntime.shared(shared, Map.of(Gate.class, gate));
             PluginTestRuntime b = PluginTestRuntime.shared(shared, Map.of())) {
            publish(producer, a, "1.0.0", false);
            a.discover(); a.manager().start("shared-demo"); b.manager().reconcile();
            publish(producer, a, "2.0.0", true);
            final var activation = worker.submit(() -> a.manager().activateVersion("shared-demo", "2.0.0"));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            b.manager().stop("shared-demo");
            release.countDown();
            try { activation.get(10, TimeUnit.SECONDS); fail("Stale activation succeeded"); }
            catch (final java.util.concurrent.ExecutionException e) { assertTrue(e.getCause() instanceof ConcurrentModificationException); }
            assertEquals(value(a), "1.0.0");
            assertEquals(a.services().getServices(Gateway.class).size(), 1);
            assertFalse(a.repository().selection("shared-demo").enabled());
            a.manager().reconcile(); assertFalse(a.manager().isRunning("shared-demo"));
        } finally { release.countDown(); worker.shutdownNow(); delete(shared); }
    }

    @Test(groups = "fast")
    public void testOldRollbackAndCompletionCannotOverwriteNewRevision() throws Exception {
        final Path shared = Files.createTempDirectory("lpr-cluster-watch-");
        try (PluginTestRuntime producer = PluginTestRuntime.create();
             PluginTestRuntime a = PluginTestRuntime.shared(shared, Map.of())) {
            publish(producer, a, "1.0.0", false); a.discover(); a.manager().start("shared-demo");
            a.manager().addActivationListener(new PluginActivationListener() {
                @Override public boolean retainPreviousVersion() { return true; }
                @Override public void onActivated(String id, String version, Optional<String> previous) { }
                @Override public void onRolledBack(String id, String current, String failed, String reason) { }
            });
            publish(producer, a, "2.0.0", false); a.manager().activateVersion("shared-demo", "2.0.0");
            final long oldRevision = a.manager().appliedRevision("shared-demo");
            a.repository().updateSelection("shared-demo", oldRevision, "2.0.0", "1.0.0", true);
            assertFalse(a.manager().rollbackIfCurrent("shared-demo", "2.0.0", oldRevision, "late failure"));
            a.manager().reconcile();
            a.manager().completeUpgrade("shared-demo", "2.0.0", oldRevision);
            assertFalse(a.manager().rollbackIfCurrent("shared-demo", "2.0.0", oldRevision, "late failure"));
            assertTrue(a.manager().rollbackIfCurrent("shared-demo", "2.0.0", oldRevision + 1, "current failure"));
            assertEquals(value(a), "1.0.0");
        } finally { delete(shared); }
    }

    private static String value(final PluginTestRuntime runtime) { return runtime.service(Gateway.class, "shared-demo").value(); }

    private static void publish(final PluginTestRuntime producer, final PluginTestRuntime node,
                                final String version, final boolean gated) throws Exception {
        final String gate = gated ? "context.getPlatformService(" + Gate.class.getCanonicalName() + ".class).enter();" : "";
        final String source = """
                package example;
                import org.killbill.billing.lpr.api.*;
                public final class SharedPlugin implements Plugin {
                    public void start(PluginContext context) throws Exception {
                        %s
                        context.services().register(%s.class, () -> Lazy.value());
                    }
                    public void stop() { }
                }
                """.formatted(gate, Gateway.class.getCanonicalName());
        final Path installed = producer.install(PluginFixture.plugin("shared-demo", version)
                .entrypoint("example.SharedPlugin", source)
                .withClass("example.Lazy", "package example; public class Lazy { public static String value() { return \"" + version + "\"; } }"));
        final var installer = new PluginInstaller(node.repository());
        final var artifact = ArtifactSource.of(installed.resolve("plugin.jar"));
        final String descriptor = Files.readString(installed.resolve("plugin.yaml"));
        installer.install("shared-demo", version, artifact, descriptor);
        installer.install("shared-demo", version, artifact, descriptor); // duplicated broadcast
    }

    private static void delete(final Path path) throws Exception {
        try (var paths = Files.walk(path)) {
            for (final Path entry : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.deleteIfExists(entry); }
        }
    }
}

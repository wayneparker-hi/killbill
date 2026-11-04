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

import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.lpr.api.PluginContext;
import org.killbill.billing.lpr.process.ProcessPluginClient;
import org.killbill.billing.lpr.process.ProcessPluginLauncher;
import org.killbill.billing.lpr.process.ProcessPluginSettings;
import org.killbill.billing.lpr.spi.PluginArtifact;
import org.killbill.billing.lpr.spi.PluginDescriptor;
import org.killbill.billing.lpr.testkit.PluginFixture;
import org.killbill.billing.lpr.testkit.PluginJarBuilder;
import org.killbill.billing.lpr.testkit.PluginTestRuntime;
import org.killbill.billing.payment.plugin.api.PaymentPluginApi;
import org.killbill.billing.payment.plugin.api.PaymentPluginApiException;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;
import org.killbill.billing.payment.plugin.api.PaymentTransactionInfoPlugin;
import org.killbill.billing.util.callcontext.CallContext;
import org.testng.annotations.Test;

import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

public class TestProcessPaymentAuthorization {
    private static final String WORKER = "org.killbill.billing.plugin.remote.PaymentWorker";
    private static final String PARENT = "org.killbill.billing.plugin.remote.ParentPlugin";

    @Test(groups = "fast")
    public void testAuthorizationThroughTypedRegistryAndResourceLifecycle() throws Exception {
        try (final var harness = new Harness()) {
            harness.install("1.0.0");
            harness.runtime.discover();
            harness.runtime.startAll();
            final var result = authorize(harness.gateway(), new BigDecimal("12.34"));
            assertEquals(result.getAmount(), new BigDecimal("12.34"));
            assertEquals(result.getCurrency(), Currency.USD);
            assertEquals(result.getStatus(), PaymentPluginStatus.PROCESSED);
            assertEquals(result.getFirstPaymentReferenceId(), Long.toString(harness.clients.get(0).pid()));
            assertNotEquals(harness.clients.get(0).pid(), ProcessHandle.current().pid());
            assertSame(result.getClass().getClassLoader(), PaymentTransactionInfoPlugin.class.getClassLoader());
            assertThrows(ClassNotFoundException.class, () -> Class.forName(WORKER));
            final PaymentPluginApi retained = harness.gateway();
            harness.runtime.manager().stop("remote-payment");
            assertNull(harness.typed.getServiceForName("remote-payment"));
            assertFalse(harness.clients.get(0).isAlive());
            assertThrows(PaymentPluginApiException.class, () -> authorize(retained, BigDecimal.ONE));
        }
    }

    @Test(groups = "fast")
    public void testCrashTimeoutAndMalformedResultNeverBecomePaymentSuccess() throws Exception {
        for (final String amount : List.of("13", "14", "15")) {
            try (final var harness = new Harness()) {
                harness.install("1.0.0");
                harness.runtime.discover();
                harness.runtime.startAll();
                final var failure = expectThrows(PaymentPluginApiException.class, () -> authorize(harness.gateway(), new BigDecimal(amount)));
                assertTrue(List.of("POC_RESULT_UNKNOWN", "POC_UNAVAILABLE").contains(failure.getErrorType()));
                final var coreService = harness.runtime.services().forPlugin("core", "1.0.0");
                coreService.register(Runnable.class, () -> {});
                harness.runtime.services().getService(Runnable.class, "core").orElseThrow().run();
                assertEquals(harness.clients.size(), 1, "Never restart or retry a payment automatically");
            }
        }
    }

    @Test(groups = "fast")
    public void testVersionSwitchAndOldWorkerCleanupLeaveNewServiceUsable() throws Exception {
        try (final var harness = new Harness()) {
            harness.install("1.0.0");
            harness.runtime.discover();
            harness.runtime.startAll();
            final long oldPid = harness.clients.get(0).pid();
            harness.install("2.0.0");
            harness.runtime.discover();
            harness.runtime.manager().activateVersion("remote-payment", "2.0.0");
            assertEquals(harness.clients.size(), 2);
            final long newPid = harness.clients.get(1).pid();
            assertNotEquals(newPid, oldPid);
            assertFalse(ProcessHandle.of(oldPid).map(ProcessHandle::isAlive).orElse(false));
            assertEquals(authorize(harness.gateway(), BigDecimal.ONE).getFirstPaymentReferenceId(), Long.toString(newPid));
            harness.runtime.services().unregisterAll("remote-payment", "1.0.0");
            assertNotNull(harness.gateway());
        }
    }

    @Test(groups = "fast")
    public void testStoppedParentPluginLoaderIsCollectibleWithRetainedProcessClient() throws Exception {
        try (final var harness = new Harness()) {
            final WeakReference<ClassLoader> loader = startAndStopForGc(harness);
            final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (loader.get() != null && System.nanoTime() < deadline) { System.gc(); Thread.sleep(30); }
            assertNull(loader.get());
            assertFalse(harness.clients.get(0).isAlive());
        }
    }

    private WeakReference<ClassLoader> startAndStopForGc(final Harness harness) throws Exception {
        harness.install("1.0.0");
        harness.runtime.discover();
        harness.runtime.startAll();
        final var reference = harness.runtime.services().getServices(PaymentPluginApi.class).get(0);
        final var loader = new WeakReference<>(reference.service().getClass().getClassLoader());
        assertNotSame(loader.get(), getClass().getClassLoader());
        harness.runtime.manager().stop("remote-payment");
        return loader;
    }

    private static PaymentTransactionInfoPlugin authorize(final PaymentPluginApi api, final BigDecimal amount) throws Exception {
        final CallContext context = mock(CallContext.class);
        final UUID tenant = UUID.randomUUID();
        when(context.getTenantId()).thenReturn(tenant);
        when(context.getUserToken()).thenReturn(UUID.randomUUID());
        final UUID payment = UUID.randomUUID();
        final UUID transaction = UUID.randomUUID();
        final var result = api.authorizePayment(UUID.randomUUID(), payment, transaction, UUID.randomUUID(), amount, Currency.USD, List.of(), context);
        assertEquals(result.getKbPaymentId(), payment);
        assertEquals(result.getKbTransactionPaymentId(), transaction);
        assertEquals(result.getSecondPaymentReferenceId(), tenant.toString());
        return result;
    }

    private static final class Harness implements AutoCloseable {
        private final Path root = Files.createTempDirectory(Path.of("/tmp"), "poc-payment-");
        private final Path sockets = Files.createDirectory(root.resolve("sockets"));
        private final List<ProcessPluginClient> clients = new ArrayList<>();
        private final MapBackedPluginServiceRegistry<PaymentPluginApi> typed = new MapBackedPluginServiceRegistry<>(PaymentPluginApi.class);
        private final PluginTestRuntime runtime;
        private final Path jar;

        Harness() throws Exception {
            jar = new PluginJarBuilder().build(Map.of(WORKER, workerSource()), root.resolve("worker.jar"));
            final Function<PluginContext, PaymentPluginApi> factory = context -> {
                final var settings = new ProcessPluginSettings(Path.of(ProcessHandle.current().info().command().orElseThrow()), sockets,
                        Duration.ofSeconds(5), Duration.ofMillis(500), Duration.ofSeconds(2), 4096, 32);
                try {
                    final var client = context.resources().manage(new ProcessPluginLauncher().launch(
                            PluginArtifact.of(context.pluginId(), context.version(), jar),
                            PluginDescriptor.of(context.pluginId(), context.version(), WORKER), settings));
                    clients.add(client);
                    return ProcessAuthorizationPoc.proxy(client);
                } catch (final java.io.IOException e) { throw new IllegalStateException(e); }
            };
            runtime = PluginTestRuntime.create(Map.of(Function.class, factory));
            runtime.services().addListener(new KillbillPluginServiceBridge(List.of(typed)));
        }

        void install(final String version) {
            runtime.install(PluginFixture.plugin("remote-payment", version).entrypoint(PARENT, parentSource()));
        }

        PaymentPluginApi gateway() { return typed.getServiceForName("remote-payment"); }

        @Override
        public void close() throws Exception {
            runtime.close();
            for (final var client : clients) {
                client.close();
                assertFalse(ProcessHandle.of(client.pid()).map(ProcessHandle::isAlive).orElse(false));
            }
            try (var paths = Files.list(sockets)) { assertEquals(paths.count(), 0L); }
            try (var paths = Files.walk(root)) {
                for (final Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
            }
        }
    }

    private static String parentSource() {
        return """
                package org.killbill.billing.plugin.remote;
                import org.killbill.billing.lpr.api.*;
                import org.killbill.billing.payment.plugin.api.PaymentPluginApi;
                import java.lang.reflect.*;
                import java.util.function.Function;
                public final class ParentPlugin implements Plugin {
                    public void start(PluginContext context) {
                        PaymentPluginApi remote = (PaymentPluginApi) context.getPlatformService(Function.class).apply(context);
                        PaymentPluginApi scoped = (PaymentPluginApi) Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[] {PaymentPluginApi.class}, (proxy, method, args) -> {
                                    try { return method.invoke(remote, args); }
                                    catch (InvocationTargetException e) { throw e.getCause(); }
                                });
                        context.services().register(PaymentPluginApi.class, scoped);
                    }
                    public void stop() {}
                }
                """;
    }

    private static String workerSource() {
        return """
                package org.killbill.billing.plugin.remote;
                import org.killbill.billing.lpr.api.ProcessPlugin;
                import java.util.*;
                public final class PaymentWorker implements ProcessPlugin {
                    public List<String> invoke(String operation, List<String> args) {
                        if (!operation.equals("authorize.v1") || args.size() != 8) throw new IllegalArgumentException("schema");
                        if (args.get(4).equals("13")) Runtime.getRuntime().halt(73);
                        if (args.get(4).equals("14")) while (true) { Thread.onSpinWait(); }
                        if (args.get(4).equals("15")) return List.of("invalid");
                        List<String> result = new ArrayList<>(args);
                        result.add(Long.toString(ProcessHandle.current().pid()));
                        result.add("PROCESSED");
                        return result;
                    }
                }
                """;
    }
}

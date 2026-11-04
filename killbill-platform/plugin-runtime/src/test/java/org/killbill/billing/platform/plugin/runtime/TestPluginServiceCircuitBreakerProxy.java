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

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.killbill.billing.lpr.core.LprMetricsCollector;
import org.killbill.billing.lpr.core.NoOpLprMetrics;
import org.killbill.billing.platform.plugin.api.DefaultPluginServiceDescriptor;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * Tests that the circuit-breaker proxy correctly wraps plugin service interfaces.
 */
public class TestPluginServiceCircuitBreakerProxy {

    public interface EchoService {
        String echo(String value);

        int count();
    }

    private PluginInvocationGuard guard;
    private ScheduledExecutorService scheduler;

    @BeforeMethod
    public void setUp() {
        final StubConfig config = new StubConfig();
        config.defaultTimeout = 200L;
        config.failureThreshold = 2;
        config.openDuration = 500L;
        final LprMetricsCollector metrics = new LprMetricsCollector(NoOpLprMetrics.INSTANCE);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        guard = new PluginInvocationGuard(config, metrics,
                                          Executors.newCachedThreadPool());
    }

    @AfterMethod
    public void tearDown() {
        guard.shutdown();
        scheduler.shutdownNow();
    }

    @Test
    public void testProxyDelegatesAndTimesOut() throws Exception {
        final EchoService real = new EchoService() {
            @Override
            public String echo(final String value) {
                try {
                    Thread.sleep(10_000);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return value;
            }

            @Override
            public int count() {
                return 42;
            }
        };

        final DefaultPluginServiceDescriptor descriptor =
                new DefaultPluginServiceDescriptor("echo-plugin", "1.0.0", "echo-plugin");
        final EchoService proxied =
                PluginServiceCircuitBreakerProxy.wrap(real, EchoService.class, descriptor, guard);

        try {
            proxied.echo("hi");
            fail("Expected PluginInvocationException");
        } catch (final PluginInvocationException e) {
            assertTrue(e.getMessage().contains("timed out"));
        }

        // Non-slow method still works.
        assertEquals(proxied.count(), 42);
    }

    @Test
    public void testProxyPreservesReturnValue() throws Exception {
        final EchoService real = new EchoService() {
            @Override
            public String echo(final String value) {
                return value;
            }

            @Override
            public int count() {
                return 0;
            }
        };
        final DefaultPluginServiceDescriptor descriptor =
                new DefaultPluginServiceDescriptor("echo-plugin", "1.0.0", "echo-plugin");
        final EchoService proxied =
                PluginServiceCircuitBreakerProxy.wrap(real, EchoService.class, descriptor, guard);
        assertEquals(proxied.echo("hello"), "hello");
    }

    @Test
    public void testProxyWithoutGuardIsUnchanged() throws Exception {
        final EchoService real = new EchoService() {
            @Override
            public String echo(final String value) {
                return value;
            }

            @Override
            public int count() {
                return 0;
            }
        };
        final DefaultPluginServiceDescriptor descriptor =
                new DefaultPluginServiceDescriptor("echo-plugin", "1.0.0", "echo-plugin");
        final EchoService proxied =
                PluginServiceCircuitBreakerProxy.wrap(real, EchoService.class, descriptor, null);
        assertSame(proxied, real);
    }

    private static final class StubConfig extends TestPluginRuntimeConfig {

        long defaultTimeout = 200L;
        String timeoutOverrides;
        int failureThreshold = 2;
        long openDuration = 500L;

        @Override
        public long getPluginInvocationDefaultTimeout() {
            return defaultTimeout;
        }

        @Override
        public String getPluginInvocationTimeoutOverrides() {
            return timeoutOverrides;
        }

        @Override
        public int getPluginCircuitBreakerFailureThreshold() {
            return failureThreshold;
        }

        @Override
        public long getPluginCircuitBreakerOpenDuration() {
            return openDuration;
        }
    }
}

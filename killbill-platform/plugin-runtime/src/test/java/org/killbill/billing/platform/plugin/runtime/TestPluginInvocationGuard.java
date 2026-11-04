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

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.killbill.billing.lpr.core.LprMetricsCollector;
import org.killbill.billing.lpr.core.NoOpLprMetrics;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * Unit tests for {@link PluginInvocationGuard}.
 */
public class TestPluginInvocationGuard {

    private StubConfig config;
    private LprMetricsCollector metrics;
    private ScheduledExecutorService scheduler;
    private PluginInvocationGuard guard;

    @BeforeMethod
    public void setUp() {
        config = new StubConfig();
        config.defaultTimeout = 200L;
        config.failureThreshold = 3;
        config.openDuration = 500L;
        metrics = new LprMetricsCollector(NoOpLprMetrics.INSTANCE);
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
    public void testSuccess() throws Exception {
        final String result = guard.call("good-plugin", () -> "ok");
        assertEquals(result, "ok");
        assertEquals(guard.breakerFor("good-plugin").state(), CircuitBreaker.State.CLOSED);
    }

    @Test
    public void testTimeout() throws Exception {
        final java.util.concurrent.CountDownLatch interrupted = new java.util.concurrent.CountDownLatch(1);
        try {
            guard.call("slow-plugin", () -> {
                try {
                    Thread.sleep(10_000);
                } catch (final InterruptedException e) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return "never";
            });
            fail("Expected PluginInvocationException");
        } catch (final PluginInvocationException e) {
            assertTrue(e.getMessage().contains("timed out"));
        }
        assertTrue(interrupted.await(2, java.util.concurrent.TimeUnit.SECONDS), "Slow callable should have been interrupted on timeout");
    }

    @Test
    public void testPerPluginTimeoutOverride() throws Exception {
        config.timeoutOverrides = "fast-plugin=50";
        // Re-create guard so overrides are parsed.
        guard.shutdown();
        guard = new PluginInvocationGuard(config, metrics,
                                          Executors.newCachedThreadPool());

        final long start = System.currentTimeMillis();
        try {
            guard.call("fast-plugin", () -> {
                Thread.sleep(10_000);
                return "never";
            });
            fail("Expected PluginInvocationException");
        } catch (final PluginInvocationException e) {
            final long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed < 150, "Override should time out faster than default 200ms: " + elapsed);
        }
    }

    @Test
    public void testCircuitBreakerOpensAfterConsecutiveFailures() throws Exception {
        final AtomicInteger calls = new AtomicInteger(0);
        final Callable<String> failing = () -> {
            calls.incrementAndGet();
            throw new RuntimeException("boom");
        };

        for (int i = 0; i < 3; i++) {
            try {
                guard.call("flaky-plugin", failing);
                fail("Expected RuntimeException");
            } catch (final RuntimeException expected) {
                assertEquals(expected.getMessage(), "boom");
            }
        }
        // The fourth call should fail fast without executing the callable.
        final int callsBefore = calls.get();
        try {
            guard.call("flaky-plugin", failing);
            fail("Expected PluginInvocationException");
        } catch (final PluginInvocationException e) {
            assertTrue(e.getMessage().contains("Circuit breaker is OPEN"));
        }
        assertEquals(calls.get(), callsBefore, "Open breaker should not allow the callable to run");
    }

    @Test
    public void testCircuitBreakerClosesAfterOpenDuration() throws Exception {
        final Callable<String> failing = () -> {
            throw new RuntimeException("boom");
        };
        for (int i = 0; i < 3; i++) {
            try {
                guard.call("recovering-plugin", failing);
                fail("Expected RuntimeException");
            } catch (final RuntimeException expected) {
                // expected
            }
        }
        assertEquals(guard.breakerFor("recovering-plugin").state(), CircuitBreaker.State.OPEN);

        Thread.sleep(600);

        // One probe call is allowed in HALF_OPEN.
        try {
            guard.call("recovering-plugin", failing);
            fail("Expected RuntimeException");
        } catch (final RuntimeException expected) {
            // expected
        }
        // After the probe failure it opens again.
        assertEquals(guard.breakerFor("recovering-plugin").state(), CircuitBreaker.State.OPEN);

        Thread.sleep(600);

        // A successful probe closes it.
        final String result = guard.call("recovering-plugin", () -> "ok");
        assertEquals(result, "ok");
        assertEquals(guard.breakerFor("recovering-plugin").state(), CircuitBreaker.State.CLOSED);
    }

    @Test
    public void testRuntimeExceptionPassesThrough() throws Exception {
        final RuntimeException cause = new RuntimeException("plugin bug");
        try {
            guard.call("buggy-plugin", () -> {
                throw cause;
            });
            fail("Expected RuntimeException");
        } catch (final RuntimeException thrown) {
            assertEquals(thrown, cause);
        }
    }

    @Test
    public void testDeadLoopIsInterrupted() throws Exception {
        final long start = System.currentTimeMillis();
        try {
            guard.call("loop-plugin", () -> {
                while (!Thread.currentThread().isInterrupted()) {
                    // Busy loop.
                }
                return "done";
            });
            fail("Expected PluginInvocationException");
        } catch (final PluginInvocationException e) {
            assertTrue(e.getMessage().contains("timed out"));
        }
        final long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 500, "Dead loop call should time out quickly, elapsed=" + elapsed);
    }

    private static final class StubConfig extends TestPluginRuntimeConfig {

        long defaultTimeout = 200L;
        String timeoutOverrides;
        int failureThreshold = 3;
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

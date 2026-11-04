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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.killbill.billing.payment.plugin.api.PaymentPluginApiException;
import org.killbill.billing.platform.plugin.api.DefaultPluginServiceDescriptor;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class TestInvocationIsolation {
    public interface PaymentOperation {
        String pay() throws PaymentPluginApiException;
    }

    @Test(groups = "fast")
    public void checkedPaymentExceptionPreservesIdentity() {
        final PluginInvocationGuard guard = new PluginInvocationGuard(new TestPluginRuntimeConfig(), null);
        try {
            final PaymentPluginApiException expected = new PaymentPluginApiException("failure", "declined");
            final PaymentOperation proxy = PluginServiceCircuitBreakerProxy.wrap(() -> { throw expected; }, PaymentOperation.class,
                    new DefaultPluginServiceDescriptor("payment", "1", "payment"), guard);
            assertSame(expectThrows(PaymentPluginApiException.class, proxy::pay), expected);
        } finally {
            guard.shutdown();
        }
    }

    @Test(groups = "fast")
    public void staleResultsCannotResetBreaker() throws Exception {
        final CircuitBreaker breaker = new CircuitBreaker(1, 20);
        final CircuitBreaker.Permit stale = breaker.acquire();
        breaker.recordFailure(breaker.acquire());
        breaker.recordSuccess(stale);
        assertEquals(breaker.state(), CircuitBreaker.State.OPEN);
        Thread.sleep(30);
        final CircuitBreaker.Permit probe = breaker.acquire();
        assertNotNull(probe);
        assertNull(breaker.acquire(), "Only one half-open probe may enter");
        breaker.recordSuccess(probe);
        breaker.recordFailure(stale);
        assertEquals(breaker.state(), CircuitBreaker.State.CLOSED);
    }

    @Test(groups = "fast")
    public void ignoredInterruptionRetainsSlotAndPoolIsBounded() throws Exception {
        final PluginInvocationGuard guard = new PluginInvocationGuard(new TestPluginRuntimeConfig() {
            @Override public long getPluginInvocationDefaultTimeout() { return 100; }
            @Override public int getPluginInvocationMaxThreads() { return 2; }
            @Override public int getPluginInvocationMaxConcurrentPerPlugin() { return 1; }
        }, null);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(2);
        final java.util.concurrent.Callable<String> stubborn = () -> {
            try {
                while (release.getCount() > 0) {
                    try { release.await(); } catch (final InterruptedException ignored) { /* deliberately uncooperative */ }
                }
                return "done";
            } finally { finished.countDown(); }
        };
        try {
            assertTrue(expectThrows(PluginInvocationException.class, () -> guard.call("a", stubborn)).getMessage().contains("timed out"));
            assertTrue(expectThrows(PluginInvocationException.class, () -> guard.call("a", () -> "bad"))
                               .getMessage().contains("concurrent call limit"));
            assertEquals(guard.call("healthy", () -> "ok"), "ok");
            expectThrows(PluginInvocationException.class, () -> guard.call("b", stubborn));
            assertTrue(expectThrows(PluginInvocationException.class, () -> guard.call("c", () -> "bad"))
                               .getMessage().contains("capacity exhausted"));
        } finally {
            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            guard.shutdown();
        }
    }
}

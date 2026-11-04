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

package org.killbill.billing.lpr.lifecycle;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.annotations.Test;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;
import static org.testng.Assert.fail;

public class TestDefaultPluginStopExecutor {

    @Test(groups = "fast")
    public void blockedStopRetainsCapacityAndUsesDaemonWorker() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch exited = new CountDownLatch(1);
        final AtomicReference<Thread> worker = new AtomicReference<>();
        try (DefaultPluginStopExecutor executor = new DefaultPluginStopExecutor(1, 100)) {
            expectThrows(TimeoutException.class, () -> executor.execute(() -> {
                worker.set(Thread.currentThread());
                entered.countDown();
                try {
                    while (release.getCount() > 0) {
                        try { release.await(); } catch (final InterruptedException ignored) { }
                    }
                } finally { exited.countDown(); }
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(worker.get().isDaemon());
            assertSame(worker.get().getContextClassLoader(), getClass().getClassLoader());
            expectThrows(RejectedExecutionException.class, () -> executor.execute(() -> fail("Over capacity")));
            release.countDown();
            assertTrue(exited.await(2, TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }

    @Test(groups = "fast")
    public void propagatesWorkerErrorsAndValidatesLimits() throws Exception {
        expectThrows(IllegalArgumentException.class, () -> new DefaultPluginStopExecutor(0, 1));
        expectThrows(IllegalArgumentException.class, () -> new DefaultPluginStopExecutor(1, 0));
        final AssertionError failure = new AssertionError("worker failure");
        try (DefaultPluginStopExecutor executor = new DefaultPluginStopExecutor(1, 2000)) {
            assertSame(expectThrows(AssertionError.class, () -> executor.execute(() -> { throw failure; })), failure);
        }
    }

    @Test(groups = "fast")
    public void interruptedCallerRequestsWorkerInterruptionWithoutCancellingCleanup() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch cleaned = new CountDownLatch(1);
        final AtomicReference<Throwable> result = new AtomicReference<>();
        try (DefaultPluginStopExecutor executor = new DefaultPluginStopExecutor(1, 5000)) {
            final Thread caller = new Thread(() -> {
                try {
                    executor.execute(() -> {
                        entered.countDown();
                        try { new CountDownLatch(1).await(); }
                        catch (final InterruptedException expected) { }
                        finally { cleaned.countDown(); }
                    });
                } catch (final Throwable failure) { result.set(failure); }
            });
            caller.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(2000);
            assertFalse(caller.isAlive());
            assertTrue(result.get() instanceof InterruptedException);
            assertTrue(cleaned.await(2, TimeUnit.SECONDS));
        }
    }
}

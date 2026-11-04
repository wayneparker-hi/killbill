/* Copyright 2020-2026 The Billing Project, LLC. Licensed under the Apache License, Version 2.0. */
package org.killbill.queue;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.killbill.commons.metrics.impl.NoOpMetricRegistry;
import org.killbill.notificationq.api.NotificationQueueConfig;
import org.killbill.notificationq.dao.NotificationEventModelDao;
import org.killbill.queue.dao.EventEntryModelDao;
import org.skife.config.AugmentedConfigurationObjectFactory;
import org.testng.annotations.Test;

import static org.awaitility.Awaitility.await;
import static org.testng.Assert.*;

public class TestQueueCompletionShutdown {
    @Test(groups = "fast")
    public void testShutdownDrainsEveryCompletedBatch() throws Exception { verifyDrain(false); }

    @Test(groups = "fast")
    public void testShutdownDrainsRetriesAfterTheCurrentCompletionBatch() throws Exception { verifyDrain(true); }

    private void verifyDrain(final boolean retries) throws Exception {
        final var probe = new CompletionProbe();
        final var result = new java.util.concurrent.atomic.AtomicBoolean();
        final Thread stopping = new Thread(() -> result.set(probe.stopQueue()), "completion-stopper");
        probe.dispatchCompletedOrFailedEvents(new NotificationEventModelDao());
        probe.startQueue();
        try {
            assertTrue(probe.entered.await(5, TimeUnit.SECONDS));
            stopping.start();
            // awaitTermination is reached only after the stop flag and executor shutdown.
            await().atMost(5, TimeUnit.SECONDS).until(() -> Arrays.stream(stopping.getStackTrace())
                    .anyMatch(frame -> frame.getMethodName().equals("awaitTermination")));
            for (int i = 0; i < 40; i++) {
                if (retries) { probe.dispatchRetriedEvents(new NotificationEventModelDao()); }
                else { probe.dispatchCompletedOrFailedEvents(new NotificationEventModelDao()); }
            }
            probe.release.countDown();
            stopping.join(5_000);
            assertFalse(stopping.isAlive());
            assertTrue(result.get());
            assertEquals(probe.completed.get(), retries ? 1 : 41);
            assertEquals(probe.retried.get(), retries ? 45 : 0);
        } finally {
            probe.release.countDown();
            stopping.join(5_000);
            probe.stopDispatch();
        }
    }

    private static final class CompletionProbe extends DefaultQueueLifecycle {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger completed = new AtomicInteger();
        final AtomicInteger retried = new AtomicInteger();

        CompletionProbe() {
            super("completion-probe", config(), new NoOpMetricRegistry());
        }

        private static NotificationQueueConfig config() {
            final var properties = new java.util.Properties();
            properties.putAll(Map.of(
                    "org.killbill.notificationq.main.lifecycle.complete.nbThreads", "1",
                    "org.killbill.notificationq.main.sleep", "10",
                    "org.killbill.notificationq.main.shutdownTimeout", "5s"));
            return new AugmentedConfigurationObjectFactory(properties)
                    .buildWithReplacements(NotificationQueueConfig.class, Map.of("instanceName", "main"));
        }

        @Override
        public boolean initQueue() { return true; }

        @Override
        public boolean isStarted() { return true; }

        @Override
        public boolean stopQueue() { return stopLifecycleCompletion(); }

        boolean stopDispatch() { return stopLifecycleDispatcher(); }

        @Override
        public DispatchResultMetrics doDispatchEvents() { return new DispatchResultMetrics(0, 0); }

        @Override
        public void doProcessCompletedEvents(final Iterable<? extends EventEntryModelDao> events) {
            if (entered.getCount() != 0) {
                entered.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (final InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            }
            events.forEach(event -> completed.incrementAndGet());
        }

        @Override
        public void doProcessRetriedEvents(final Iterable<? extends EventEntryModelDao> events) {
            events.forEach(event -> retried.incrementAndGet());
            // Leave a second retry batch while the first batch is being persisted.
            if (retried.get() == 40) {
                for (int i = 0; i < 5; i++) { dispatchRetriedEvents(new NotificationEventModelDao()); }
            }
        }
    }
}

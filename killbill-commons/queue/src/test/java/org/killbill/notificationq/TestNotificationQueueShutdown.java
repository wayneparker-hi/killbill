/* Copyright 2020-2026 The Billing Project, LLC. Licensed under the Apache License, Version 2.0. */
package org.killbill.notificationq;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.killbill.notificationq.api.NotificationEvent;
import org.killbill.notificationq.api.NotificationQueue;
import org.killbill.notificationq.api.NotificationQueueService.NotificationQueueHandler;
import org.testng.annotations.Test;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import static org.awaitility.Awaitility.await;
import static org.testng.Assert.*;

public class TestNotificationQueueShutdown {
    @Test(groups = "fast")
    public void testStoppingOneSharedQueueLeavesTheOtherRunning() throws Exception {
        try (final var storage = new QueueShutdownStorage()) {
            final var service = storage.service("5s");
            final var deliveries = new AtomicInteger();
            final var first = queue(service, "first", (event, date, token, key1, key2) -> fail("Stopped queue invoked"));
            final var second = queue(service, "second", (event, date, token, key1, key2) -> deliveries.incrementAndGet());
            first.startQueue();
            second.startQueue();
            try {
                assertTrue(first.stopQueue(), "Logical stop must not report a shared dispatcher timeout");
                assertTrue(first.stopQueue(), "Repeated logical stop must be harmless");
                assertTrue(service.isStarted(), "Other queue still owns the dispatcher");
                record(storage, second, "active");
                history(storage, second, 1);
                assertEquals(deliveries.get(), 1);
                assertTrue(second.stopQueue());
                assertFalse(service.isStarted());
            } finally {
                first.stopQueue();
                second.stopQueue();
            }
        }
    }

    @Test(groups = "fast")
    public void testInflightCompletionAndPendingNotificationSurviveRestartWithoutDuplicates() throws Exception {
        try (final var storage = new QueueShutdownStorage(); final var stopper = Executors.newSingleThreadExecutor()) {
            final var service = storage.service("5s");
            final var entered = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            final var deliveries = new AtomicInteger();
            final var queue = queue(service, "inflight", (event, date, token, key1, key2) -> {
                deliveries.incrementAndGet();
                entered.countDown();
                waitFor(release);
            });
            queue.startQueue();
            try {
                record(storage, queue, "inflight");
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                final var stopped = stopper.submit(queue::stopQueue);
                await().atMost(5, TimeUnit.SECONDS).until(() -> !queue.isStarted());
                await().atMost(5, TimeUnit.SECONDS).until(this::handlerStopIsWaiting);
                assertFalse(stopped.isDone(), "Stop must wait for the active handler");
                release.countDown();
                assertTrue(stopped.get(5, TimeUnit.SECONDS));
                assertEquals(storage.count("notifications_history", queue.getFullQName(), "PROCESSED"), 1);
                record(storage, queue, "pending");
                final var restarted = storage.service("5s").createNotificationQueue(queue.getServiceName(), queue.getQueueName(),
                        (event, date, token, key1, key2) -> deliveries.incrementAndGet());
                restarted.startQueue();
                try {
                    history(storage, restarted, 2);
                } finally { assertTrue(restarted.stopQueue()); }
                assertEquals(deliveries.get(), 2, "One inflight plus one pending; no replay of completed work");
            } finally {
                release.countDown();
                queue.stopQueue();
            }
        }
    }

    @Test(groups = "fast")
    public void testRealTimeoutIsReportedAndExpiredClaimIsRedelivered() throws Exception {
        try (final var storage = new QueueShutdownStorage()) {
            final var service = storage.service("100ms");
            final var entered = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            final var exited = new CountDownLatch(1);
            final var deliveries = new AtomicInteger();
            final var queue = queue(service, "timeout", (event, date, token, key1, key2) -> {
                deliveries.incrementAndGet();
                entered.countDown();
                try { waitFor(release); } finally { exited.countDown(); }
            });
            queue.startQueue();
            try {
                record(storage, queue, "timeout");
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(queue.stopQueue(), "A real handler timeout must remain visible");
                assertEquals(storage.count("notifications", queue.getFullQName(), "IN_PROCESSING"), 1);
                assertEquals(storage.count("notifications_history", queue.getFullQName(), "PROCESSED"), 0);
            } finally {
                release.countDown();
                assertTrue(exited.await(5, TimeUnit.SECONDS));
                queue.stopQueue();
            }
            // Expire the real DB claim; the new service uses the production reaper and DAO.
            storage.clock.addDeltaFromReality(5_000);
            final var recovered = storage.service("5s").createNotificationQueue(queue.getServiceName(), queue.getQueueName(),
                    (event, date, token, key1, key2) -> deliveries.incrementAndGet());
            recovered.startQueue();
            try { history(storage, recovered, 1); } finally { assertTrue(recovered.stopQueue()); }
            assertEquals(deliveries.get(), 2, "Timeout recovery is at least once and may repeat a handler side effect");
        }
    }

    private NotificationQueue queue(final DefaultNotificationQueueService service, final String name,
                                    final NotificationQueueHandler handler) throws Exception {
        return service.createNotificationQueue("shutdown", name + "-" + UUID.randomUUID().toString().substring(0, 8), handler);
    }

    private boolean handlerStopIsWaiting() {
        return Thread.getAllStackTraces().values().stream().anyMatch(stack ->
                Arrays.stream(stack).anyMatch(frame -> frame.getMethodName().equals("awaitTermination")) &&
                Arrays.stream(stack).anyMatch(frame -> frame.getClassName().equals("org.killbill.queue.dispatching.Dispatcher") &&
                                                       frame.getMethodName().equals("stopDispatcher")));
    }

    private void record(final QueueShutdownStorage storage, final NotificationQueue queue, final String value) throws Exception {
        queue.recordFutureNotification(storage.clock.getUTCNow(), new Key(value), UUID.randomUUID(), 1L, 1L);
    }

    private void history(final QueueShutdownStorage storage, final NotificationQueue queue, final int count) {
        await().atMost(10, TimeUnit.SECONDS).until(() -> storage.count("notifications_history", queue.getFullQName(), "PROCESSED") == count);
    }

    private static void waitFor(final CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Handler gate timed out"); }
        } catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    public static final class Key implements NotificationEvent {
        private final String value;

        @JsonCreator
        public Key(@JsonProperty("value") final String value) { this.value = value; }

        public String getValue() { return value; }
    }
}

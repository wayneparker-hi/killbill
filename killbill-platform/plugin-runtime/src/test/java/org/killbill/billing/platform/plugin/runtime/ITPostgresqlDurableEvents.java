/* Copyright 2020-2026 The Billing Project, LLC. Licensed under the Apache License, Version 2.0. */
package org.killbill.billing.platform.plugin.runtime;

import java.nio.file.Files;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.killbill.billing.lpr.core.DefaultServiceRegistry;

import org.testng.annotations.Test;

import static org.killbill.billing.platform.plugin.runtime.PostgresqlDurableEventsStorage.*;
import static org.testng.Assert.*;

@Test(groups = "postgresql")
public class ITPostgresqlDurableEvents {
    public void testConfirmedPublicationSurvivesPublisherHalt() throws Exception {
        final var storage = provisioned();
        final UUID id = UUID.randomUUID();
        DurableEventsWorkerProcess.run(storage, "publish-halt", id, 23);
        assertEquals(Files.readString(storage.directory.resolve("published-" + id)), id.toString());
        storage.verifyEnvelope(ACTIVE, id, "AVAILABLE");
        DurableEventsWorkerProcess.run(storage, "consume", id, 0);
        verifyProcessed(storage, id);
        assertEquals(storage.effects(id), 1);
        assertEquals(Files.readAllLines(storage.directory.resolve("deliveries-" + id)), java.util.List.of(id.toString()));
    }

    public void testSideEffectBeforeConsumerHaltIsReplayedWithTheSameId() throws Exception {
        final var storage = provisioned();
        final UUID id = UUID.randomUUID();
        DurableEventsWorkerProcess.run(storage, "publish-halt", id, 23);
        DurableEventsWorkerProcess.run(storage, "consume-halt", id, 24);
        storage.verifyEnvelope(ACTIVE, id, "IN_PROCESSING");
        assertEquals(storage.count(HISTORY, id, "PROCESSED"), 0);
        assertEquals(storage.effects(id), 1, "Business effect committed before the consumer halted");
        DurableEventsWorkerProcess.run(storage, "consume", id, 0);
        verifyProcessed(storage, id);
        storage.verifyEnvelope(HISTORY, id, "REAPED");
        assertEquals(storage.count(HISTORY, id, "REAPED"), 1, "Expired claim is archived separately from the successful replay");
        assertEquals(storage.effects(id), 1, "UUID deduplication must suppress a duplicate effect");
        assertEquals(Files.readAllLines(storage.directory.resolve("deliveries-" + id)), java.util.List.of(id.toString(), id.toString()));
    }

    public void testTransientFailurePermanentFailureAndMissingSubscription() throws Exception {
        final var storage = provisioned();
        final var bus = storage.newBus();
        final var registry = new DefaultServiceRegistry();
        final UUID retry = UUID.randomUUID(), failed = UUID.randomUUID(), missing = UUID.randomUUID();
        final var retryAttempts = new AtomicInteger();
        final var failedAttempts = new AtomicInteger();
        bus.initQueue();
        try (final var adapter = new PersistentPluginEventBus(bus, registry, TestPersistentPluginEventBus.enabled())) {
            adapter.start();
            final var consumer = adapter.open("target", registry.forPlugin("target", "1.0.0"));
            consumer.subscribe("retry", event -> { if (retryAttempts.incrementAndGet() == 1) { throw new IllegalStateException("transient"); } });
            consumer.subscribe("failed", event -> { failedAttempts.incrementAndGet(); throw new IllegalStateException("permanent"); });
            final var publisher = adapter.open("sender", registry.forPlugin("sender", "1.0.0"));
            publisher.publish("target", "retry", message(retry));
            publisher.publish("target", "failed", message(failed));
            publisher.publish("target", "missing", message(missing));
            bus.startQueue();
            try {
                storage.awaitHistory(retry, "PROCESSED");
                storage.awaitHistory(failed, "FAILED");
                storage.awaitHistory(missing, "FAILED");
                assertEquals(retryAttempts.get(), 2);
                assertEquals(failedAttempts.get(), 3);
                verifyProcessed(storage, retry);
                storage.verifyEnvelope(HISTORY, failed, "FAILED");
                storage.verifyEnvelope(HISTORY, missing, "FAILED");
                assertEquals(storage.count(ACTIVE, failed, "AVAILABLE"), 0);
                assertEquals(storage.count(HISTORY, missing, "PROCESSED"), 0);
            } finally { assertTrue(bus.stopQueue()); consumer.close(); publisher.close(); }
        }
    }

    public void testWriteLockTimeoutIsRejectedAndPublicationRecovers() throws Exception {
        final var storage = provisioned();
        final var bus = storage.newBus();
        final var registry = new DefaultServiceRegistry();
        final UUID id = UUID.randomUUID();
        bus.initQueue();
        try (final var adapter = new PersistentPluginEventBus(bus, registry, TestPersistentPluginEventBus.enabled())) {
            adapter.start();
            final var publisher = adapter.open("sender", registry.forPlugin("sender", "1.0.0"));
            try {
                try (var lock = storage.source.getConnection(); var statement = lock.createStatement()) {
                    lock.setAutoCommit(false);
                    statement.execute("LOCK TABLE bus_ext_events IN ACCESS EXCLUSIVE MODE");
                    try {
                        final var error = expectThrows(IllegalStateException.class, () -> publisher.publish("target", "jobs", message(id)));
                        assertTrue(hasSqlState(error, "55P03"), "Expected the actual PostgreSQL lock_timeout, got " + error);
                        System.out.println("durable write fault SQLSTATE=55P03");
                    } finally { lock.rollback(); }
                }
                assertEquals(storage.count(ACTIVE, id, "AVAILABLE"), 0, "Rejected publication cannot claim storage success");
                publisher.publish("target", "jobs", message(id));
                storage.verifyEnvelope(ACTIVE, id, "AVAILABLE");
                final var consumer = adapter.open("target", registry.forPlugin("target", "1.0.0"));
                consumer.subscribe("jobs", event -> assertEquals(event, message(id)));
                bus.startQueue();
                storage.awaitHistory(id, "PROCESSED");
                verifyProcessed(storage, id);
            } finally { bus.startQueue(); assertTrue(bus.stopQueue()); publisher.close(); }
        }
    }

    private void verifyProcessed(final PostgresqlDurableEventsStorage storage, final UUID id) throws Exception {
        storage.verifyEnvelope(HISTORY, id, "PROCESSED");
        assertEquals(storage.count(ACTIVE, id, "AVAILABLE"), 0);
        assertEquals(storage.count(ACTIVE, id, "IN_PROCESSING"), 0);
        assertEquals(storage.count(HISTORY, id, "FAILED"), 0);
    }

    private boolean hasSqlState(final Throwable error, final String state) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException && state.equals(((SQLException) cause).getSQLState())) { return true; }
        }
        return false;
    }
}

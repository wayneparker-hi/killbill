/* Copyright 2020-2026 The Billing Project, LLC. Licensed under the Apache License, Version 2.0. */
package org.killbill.billing.platform.plugin.runtime;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import org.testng.annotations.Test;

import static org.killbill.billing.platform.plugin.runtime.DurableEventsNodeWorker.*;
import static org.killbill.billing.platform.plugin.runtime.PostgresqlDurableEventsStorage.*;
import static org.testng.Assert.*;

@Test(groups = "postgresql")
public class ITPostgresqlDurableEventsStaleWorker {
    public void testLateSuccessDoesNotCompleteTheNewAttempt() throws Exception { exercise("stale-success"); }

    public void testLateFailureDoesNotRetryTheNewAttempt() throws Exception { exercise("stale-failure"); }

    private void exercise(final String mode) throws Exception {
        final var storage = provisioned();
        final UUID id = UUID.randomUUID();
        Files.write(storage.directory.resolve("messages"), List.of(id.toString()));
        DurableEventsWorkerProcess.run(storage, "publish-halt", id, 23);
        try (final var a = start(storage, mode, "a")) {
            awaitReady(storage, mode, "a");
            signal(storage.directory, "go", "");
            assertEquals(awaitSignal(storage.directory, "running-a"), mode + "-a");
            assertEquals(awaitSignal(storage.directory, "entered-a"), id.toString());
            storage.verifyUnexpiredClaim(id, mode + "-a");
            final var old = storage.attempt(ACTIVE, id, "IN_PROCESSING", mode + "-a");
            a.pause();
            try (final var b = start(storage, mode, "b")) {
                final var current = awaitTakeover(storage, id, mode, old, a, b);
                a.resume();
                signal(storage.directory, "release-a", "");
                assertEquals(awaitSignal(storage.directory, "returned-a"), mode.equals("stale-failure") ? "failure" : "success");
                stop(storage, "a", a);
                assertTrue(b.process.isAlive(), "Peer must remain in its handler while the stale result is flushed");
                assertFalse(Files.exists(storage.directory.resolve("returned-b")));
                assertEquals(storage.attempts(ACTIVE, id), List.of(current), "Stale ACK/retry must leave the new attempt unchanged");
                storage.verifyUnexpiredClaim(id, mode + "-b");
                verifyHistory(storage, id, mode, false);
                signal(storage.directory, "release-b", "");
                assertEquals(awaitSignal(storage.directory, "returned-b"), "success");
                stop(storage, "b", b);
                verifyHistory(storage, id, mode, true);
                assertTrue(storage.attempts(ACTIVE, id).isEmpty(), "No abandoned or resurrected attempt");
                assertEquals(storage.effects(id), 1);
                for (final String node : List.of("a", "b")) {
                    assertEquals(Files.readAllLines(storage.directory.resolve("deliveries-" + node)), List.of(id.toString()));
                }
            }
        }
    }

    private Attempt awaitTakeover(final PostgresqlDurableEventsStorage storage, final UUID id, final String mode,
                                  final Attempt old, final DurableEventsWorkerProcess a, final DurableEventsWorkerProcess b) throws Exception {
        awaitReady(storage, mode, "b");
        assertEquals(awaitSignal(storage.directory, "running-b"), mode + "-b");
        assertEquals(awaitSignal(storage.directory, "entered-b"), id.toString());
        a.verifySuspended(true);
        assertTrue(a.process.isAlive() && b.process.isAlive());
        assertNotEquals(a.process.pid(), b.process.pid());
        assertTrue(old.available().isBefore(java.time.Instant.now()), "Original claim must actually expire");
        storage.verifyEnvelope(HISTORY, id, "REAPED");
        assertEquals(storage.owner(HISTORY, id, "REAPED"), mode + "-a");
        final var current = storage.attempt(ACTIVE, id, "IN_PROCESSING", mode + "-b");
        assertNotEquals(current.recordId(), old.recordId(), "Reaper must create a separate physical attempt");
        storage.verifyUnexpiredClaim(id, mode + "-b");
        assertEquals(storage.count(HISTORY, id, "PROCESSED"), 0);
        assertEquals(storage.effects(id), 1);
        return current;
    }

    private void verifyHistory(final PostgresqlDurableEventsStorage storage, final UUID id, final String mode, final boolean peerCompleted) throws Exception {
        final var history = storage.attempts(HISTORY, id);
        final boolean lateSuccess = mode.equals("stale-success");
        assertEquals(history.size(), 1 + (lateSuccess ? 1 : 0) + (peerCompleted ? 1 : 0));
        storage.verifyEnvelope(HISTORY, id, "REAPED");
        storage.attempt(HISTORY, id, "REAPED", mode + "-a");
        if (lateSuccess) { storage.attempt(HISTORY, id, "PROCESSED", mode + "-a"); }
        if (peerCompleted) { storage.attempt(HISTORY, id, "PROCESSED", mode + "-b"); }
        assertEquals(storage.count(HISTORY, id, "FAILED"), 0);
        assertEquals(storage.count(HISTORY, id, "PROCESSED"), (lateSuccess ? 1 : 0) + (peerCompleted ? 1 : 0));
    }

    private DurableEventsWorkerProcess start(final PostgresqlDurableEventsStorage storage, final String mode, final String node) throws Exception {
        return DurableEventsWorkerProcess.start(storage, DurableEventsNodeWorker.class, mode, node);
    }

    private void awaitReady(final PostgresqlDurableEventsStorage storage, final String mode, final String node) throws Exception {
        assertEquals(awaitSignal(storage.directory, "ready-" + node), mode + "-" + node + "|" + java.util.TimeZone.getDefault().getID());
    }

    private void stop(final PostgresqlDurableEventsStorage storage, final String node, final DurableEventsWorkerProcess worker) throws Exception {
        signal(storage.directory, "stop-" + node, "");
        worker.awaitExit(0);
    }
}

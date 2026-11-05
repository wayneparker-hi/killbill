/* Copyright 2020-2026 The Billing Project, LLC. Licensed under the Apache License, Version 2.0. */
package org.killbill.billing.platform.plugin.runtime;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.testng.annotations.Test;

import static org.killbill.billing.platform.plugin.runtime.PostgresqlDurableEventsStorage.*;
import static org.killbill.billing.platform.plugin.runtime.DurableEventsNodeWorker.*;
import static org.testng.Assert.*;

@Test(groups = "postgresql")
public class ITPostgresqlDurableEventsCluster {
    public void testCompetingNodesCompleteWithinTheirClaims() throws Exception {
        final var storage = provisioned();
        final List<UUID> messages = publishBatch(storage);
        try (final var a = start(storage, "normal", "a"); final var b = start(storage, "normal", "b")) {
            final List<UUID> inFlight = observeBoth(storage, "normal", a, b);
            assertNotEquals(inFlight.get(0), inFlight.get(1), "Two nodes must not hold the same unexpired message");
            signal(storage.directory, "release-a", "");
            signal(storage.directory, "release-b", "");
            awaitBatch(storage, messages);
            stop(storage, "a", a);
            stop(storage, "b", b);
            final var deliveries = new ArrayList<UUID>();
            for (final String node : List.of("a", "b")) {
                for (final UUID id : deliveries(storage, node)) {
                    assertEquals(storage.owner(HISTORY, id, "PROCESSED"), "normal-" + node);
                    deliveries.add(id);
                }
            }
            assertEquals(deliveries.size(), messages.size());
            for (final UUID id : messages) {
                assertEquals(Collections.frequency(deliveries, id), 1, "Duplicate/missing delivery " + id);
                assertEquals(storage.count(HISTORY, id, "REAPED"), 0, "Normal case must not outlive its claim");
            }
        }
    }

    public void testSurvivingNodeRecoversAfterPeerIsKilledBeforeAck() throws Exception {
        final var storage = provisioned();
        final List<UUID> messages = publishBatch(storage);
        try (final var a = start(storage, "failover", "a"); final var b = start(storage, "failover", "b")) {
            final UUID interrupted = observeBoth(storage, "failover", a, b).get(0);
            storage.verifyEnvelope(ACTIVE, interrupted, "IN_PROCESSING");
            assertEquals(storage.count(HISTORY, interrupted, "PROCESSED"), 0);
            assertEquals(storage.effects(interrupted), 1, "Side effect precedes peer termination");
            a.kill();
            assertFalse(a.process.isAlive(), "Faulted peer must be dead before recovery");
            assertTrue(b.process.isAlive(), "Standby must already be running when its peer dies");
            signal(storage.directory, "release-b", "");
            awaitBatch(storage, messages);
            stop(storage, "b", b);
            storage.verifyEnvelope(HISTORY, interrupted, "REAPED");
            assertEquals(storage.owner(HISTORY, interrupted, "REAPED"), "failover-a");
            assertEquals(storage.owner(HISTORY, interrupted, "PROCESSED"), "failover-b");
            assertEquals(Collections.frequency(deliveries(storage, "a"), interrupted), 1);
            assertEquals(Collections.frequency(deliveries(storage, "b"), interrupted), 1);
            assertEquals(storage.effects(interrupted), 1, "Cross-node replay is deduplicated by business UUID");
            assertEquals(deliveries(storage, "a"), List.of(interrupted), "Faulted node cannot ACK or process another message");
            final List<UUID> survivingDeliveries = deliveries(storage, "b");
            assertEquals(survivingDeliveries.size(), messages.size());
            for (final UUID id : messages) {
                assertEquals(Collections.frequency(survivingDeliveries, id), 1);
                assertEquals(storage.owner(HISTORY, id, "PROCESSED"), "failover-b");
            }
        }
    }

    private List<UUID> publishBatch(final PostgresqlDurableEventsStorage storage) throws Exception {
        final var messages = new ArrayList<UUID>();
        for (int i = 0; i < 12; i++) { messages.add(UUID.randomUUID()); }
        Files.write(storage.directory.resolve("messages"), messages.stream().map(UUID::toString).toList());
        try (final var publisher = start(storage, "publish", "batch")) { publisher.awaitExit(23); }
        for (final UUID id : messages) { storage.verifyEnvelope(ACTIVE, id, "AVAILABLE"); }
        return List.copyOf(messages);
    }

    private DurableEventsWorkerProcess start(final PostgresqlDurableEventsStorage storage, final String mode, final String node) throws Exception {
        return DurableEventsWorkerProcess.start(storage, DurableEventsNodeWorker.class, mode, node);
    }

    private List<UUID> observeBoth(final PostgresqlDurableEventsStorage storage, final String mode,
                                  final DurableEventsWorkerProcess a, final DurableEventsWorkerProcess b) throws Exception {
        final String timezone = "|" + java.util.TimeZone.getDefault().getID();
        assertEquals(awaitSignal(storage.directory, "ready-a"), mode + "-a" + timezone);
        assertEquals(awaitSignal(storage.directory, "ready-b"), mode + "-b" + timezone);
        signal(storage.directory, "go", "");
        final var inFlight = new ArrayList<UUID>();
        for (final String node : List.of("a", "b")) {
            assertEquals(awaitSignal(storage.directory, "running-" + node), mode + "-" + node);
            final UUID id = UUID.fromString(awaitSignal(storage.directory, "entered-" + node));
            storage.verifyUnexpiredClaim(id, mode + "-" + node);
            inFlight.add(id);
            System.out.println("durable node=" + node + " owner=" + mode + "-" + node + " in-flight=" + id);
        }
        assertTrue(a.process.isAlive() && b.process.isAlive(), "Both peers must be alive at the observation");
        assertNotEquals(a.process.pid(), b.process.pid());
        storage.verifyUnexpiredClaim(inFlight.get(0), mode + "-a");
        storage.verifyUnexpiredClaim(inFlight.get(1), mode + "-b");
        return List.copyOf(inFlight);
    }

    private void awaitBatch(final PostgresqlDurableEventsStorage storage, final List<UUID> messages) throws Exception {
        for (final UUID id : messages) {
            storage.awaitHistory(id, "PROCESSED");
            storage.verifyEnvelope(HISTORY, id, "PROCESSED");
            assertEquals(storage.count(ACTIVE, id, "AVAILABLE"), 0);
            assertEquals(storage.count(ACTIVE, id, "IN_PROCESSING"), 0);
            assertEquals(storage.count(HISTORY, id, "FAILED"), 0);
            assertEquals(storage.effects(id), 1);
        }
    }

    private void stop(final PostgresqlDurableEventsStorage storage, final String node, final DurableEventsWorkerProcess worker) throws Exception {
        signal(storage.directory, "stop-" + node, "");
        worker.awaitExit(0);
    }

    private List<UUID> deliveries(final PostgresqlDurableEventsStorage storage, final String node) throws Exception {
        return Files.readAllLines(storage.directory.resolve("deliveries-" + node)).stream().map(UUID::fromString).toList();
    }
}

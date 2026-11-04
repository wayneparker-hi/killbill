/* Copyright 2020-2026 The Billing Project, LLC. Licensed under the Apache License, Version 2.0. */
package org.killbill.billing.platform.plugin.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

import org.killbill.billing.lpr.api.DurableEvent;
import org.killbill.billing.lpr.core.DefaultServiceRegistry;

import static org.testng.Assert.*;

/** A real child JVM; halt deliberately bypasses lifecycle shutdown and completion ACK. */
public final class DurableEventsCrashWorker {
    public static void main(final String[] args) throws Exception {
        final var storage = new PostgresqlDurableEventsStorage(Path.of(args[0]));
        final String mode = args[1];
        final UUID id = UUID.fromString(args[2]);
        final var bus = storage.newBus();
        final var registry = new DefaultServiceRegistry();
        bus.initQueue();
        try (final var adapter = new PersistentPluginEventBus(bus, registry, TestPersistentPluginEventBus.enabled())) {
            adapter.start();
            if (mode.equals("publish-halt")) {
                adapter.open("sender", registry.forPlugin("sender", "1.0.0"))
                        .publish("target", "jobs", PostgresqlDurableEventsStorage.message(id));
                Files.writeString(storage.directory.resolve("published-" + id), id.toString());
                Runtime.getRuntime().halt(23);
            }
            assertTrue(mode.equals("consume") || mode.equals("consume-halt"), "Unknown mode: " + mode);
            final var scope = adapter.open("target", registry.forPlugin("target", "1.0.0"));
            scope.subscribe("jobs", event -> received(storage, event, id, mode));
            bus.startQueue();
            try { storage.awaitHistory(id, "PROCESSED"); }
            finally { assertTrue(bus.stopQueue()); scope.close(); }
        }
    }

    private static void received(final PostgresqlDurableEventsStorage storage, final DurableEvent event,
                                 final UUID expected, final String mode) {
        assertEquals(event, PostgresqlDurableEventsStorage.message(expected));
        try {
            storage.effect(event);
            Files.writeString(storage.directory.resolve("deliveries-" + expected), event.id() + "\n",
                              StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (final Exception error) { throw new IllegalStateException(error); }
        if (mode.equals("consume-halt")) { Runtime.getRuntime().halt(24); }
    }
}

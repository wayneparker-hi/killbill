/* Copyright 2020-2026 The Billing Project, LLC. Licensed under the Apache License, Version 2.0. */
package org.killbill.billing.platform.plugin.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.killbill.CreatorName;
import org.killbill.billing.lpr.api.DurableEvent;
import org.killbill.billing.lpr.core.DefaultServiceRegistry;

import static org.testng.Assert.*;

/** File barriers coordinate the probe; all claim, dispatch, ACK and recovery use the production queue. */
public final class DurableEventsNodeWorker {
    public static void main(final String[] args) throws Exception {
        final var storage = new PostgresqlDurableEventsStorage(Path.of(args[0]));
        final String mode = args[1], node = args[2];
        final List<UUID> messages = Files.readAllLines(storage.directory.resolve("messages")).stream().map(UUID::fromString).toList();
        assertTrue(List.of("publish", "normal", "failover", "stale-success", "stale-failure").contains(mode));
        final var bus = storage.newBus(Map.of("claimed", "1", "queue.capacity", "1", "queue.mode", "POLLING",
                                             "claim.time", mode.equals("normal") ? "30s" : "6s",
                                             "reapThreshold", mode.equals("normal") ? "40s" : "8s"));
        final var registry = new DefaultServiceRegistry();
        assertTrue(bus.initQueue());
        try (final var adapter = new PersistentPluginEventBus(bus, registry, TestPersistentPluginEventBus.enabled())) {
            adapter.start();
            if (mode.equals("publish")) {
                final var publisher = adapter.open("sender", registry.forPlugin("sender", "1.0.0"));
                for (final UUID id : messages) { publisher.publish("target", "jobs", PostgresqlDurableEventsStorage.message(id)); }
                Runtime.getRuntime().halt(23);
            }
            final var consumer = adapter.open("target", registry.forPlugin("target", "1.0.0"));
            consumer.subscribe("jobs", event -> received(storage, event, messages, node, mode));
            signal(storage.directory, "ready-" + node, CreatorName.get() + "|" + java.util.TimeZone.getDefault().getID());
            awaitSignal(storage.directory, "go");
            assertTrue(bus.startQueue());
            try {
                signal(storage.directory, "running-" + node, CreatorName.get());
                awaitSignal(storage.directory, "stop-" + node);
            } finally { assertTrue(bus.stopQueue()); consumer.close(); }
        }
    }

    private static void received(final PostgresqlDurableEventsStorage storage, final DurableEvent event,
                                 final List<UUID> messages, final String node, final String mode) {
        assertTrue(messages.contains(event.id()), "Foreign message " + event.id());
        assertEquals(event, PostgresqlDurableEventsStorage.message(event.id()));
        try {
            storage.effect(event);
            Files.writeString(storage.directory.resolve("deliveries-" + node), event.id() + "\n",
                              StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (!Files.exists(storage.directory.resolve("entered-" + node))) {
                signal(storage.directory, "entered-" + node, event.id().toString());
            }
            awaitSignal(storage.directory, "release-" + node);
            if (mode.startsWith("stale-")) {
                final boolean failed = mode.equals("stale-failure") && node.equals("a");
                signal(storage.directory, "returned-" + node, failed ? "failure" : "success");
                System.out.println("durable handler returned uuid=" + event.id() + " node=" + node + " failed=" + failed);
                if (failed) { throw new IllegalStateException("deliberate late failure"); }
            }
        } catch (final Exception error) { throw new IllegalStateException(error); }
    }

    static void signal(final Path directory, final String name, final String value) throws Exception {
        final Path pending = directory.resolve(name + ".tmp");
        Files.writeString(pending, value);
        Files.move(pending, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE);
    }

    static String awaitSignal(final Path directory, final String name) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        final Path signal = directory.resolve(name);
        while (System.nanoTime() < deadline) {
            if (Files.exists(signal)) { return Files.readString(signal); }
            Thread.sleep(20);
        }
        throw new IllegalStateException("Missing node signal " + signal);
    }
}

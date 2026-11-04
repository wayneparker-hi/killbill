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
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.killbill.billing.platform.plugin.runtime;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import org.killbill.billing.lpr.core.DefaultServiceRegistry;
import org.killbill.bus.DefaultPersistentBus;
import org.killbill.bus.InMemoryPersistentBus;
import org.killbill.bus.api.BusEvent;
import org.killbill.bus.api.PersistentBus;
import org.killbill.bus.api.PersistentBusConfig;
import org.skife.config.AugmentedConfigurationObjectFactory;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

/** Isolated file database, production queue DDL/DAO/dispatcher. No shared developer DB. */
public class TestPersistentPluginEventStorage {
    @Test(groups = "fast")
    public void testStoredMessageSurvivesQueueAndDatabaseReopen() throws Exception {
        try (final var db = new Storage()) {
            final var producer = db.newBus();
            final var registry = new DefaultServiceRegistry();
            final var envelope = TestPersistentPluginEventBus.envelope("target", "jobs");
            producer.initQueue();
            try {
                try (final var adapter = new PersistentPluginEventBus(producer, registry, TestPersistentPluginEventBus.enabled())) {
                    adapter.start();
                    final var source = adapter.open("sender", registry.forPlugin("sender", "1.0.0"));
                    source.publish("target", "jobs", envelope.message());
                    assertEquals(db.count("bus_events", envelope.id(), "AVAILABLE"), 1);
                }
                // Close the actual database and recreate queue/adapter; no plugin Class objects persisted.
                try (var connection = db.dataSource.getConnection(); var statement = connection.createStatement()) {
                    statement.execute("SHUTDOWN");
                }
                final var restarted = db.newBus();
                restarted.initQueue();
                final var received = new java.util.concurrent.atomic.AtomicReference<org.killbill.billing.lpr.api.DurableEvent>();
                try (final var adapter = new PersistentPluginEventBus(restarted, registry, TestPersistentPluginEventBus.enabled())) {
                    adapter.start();
                    final var consumer = adapter.open("target", registry.forPlugin("target", "1.0.0"));
                    consumer.subscribe("jobs", received::set);
                    restarted.startQueue();
                    db.awaitHistory(envelope.id(), "PROCESSED");
                    assertEquals(received.get(), envelope.message());
                    consumer.close();
                } finally { assertTrue(restarted.stopQueue()); }
            } finally {
                // initQueue creates an idle executor but stopQueue currently expects startQueue first.
                producer.startQueue();
                assertTrue(producer.stopQueue());
            }
        }
    }

    @Test(groups = "fast")
    public void testRetrySuccessPermanentFailureAndMissingConsumerDeadLetters() throws Exception {
        try (final var db = new Storage()) {
            final var bus = db.newBus();
            bus.initQueue();
            final var registry = new DefaultServiceRegistry();
            final var retry = TestPersistentPluginEventBus.envelope("target", "retry");
            final var failed = TestPersistentPluginEventBus.envelope("target", "failed");
            final var missing = TestPersistentPluginEventBus.envelope("target", "missing");
            final var attempts = new AtomicInteger();
            final var failures = new AtomicInteger();
            try (final var adapter = new PersistentPluginEventBus(bus, registry, retryTestConfig())) {
                adapter.start();
                final var consumer = adapter.open("target", registry.forPlugin("target", "1.0.0"));
                consumer.subscribe("retry", message -> { if (attempts.incrementAndGet() == 1) { throw new IllegalStateException("transient"); } });
                consumer.subscribe("failed", message -> { failures.incrementAndGet(); throw new IllegalStateException("permanent"); });
                final var source = adapter.open("sender", registry.forPlugin("sender", "1.0.0"));
                source.publish("target", "retry", retry.message());
                source.publish("target", "failed", failed.message());
                source.publish("target", "missing", missing.message());
                bus.startQueue();
                db.awaitHistory(retry.id(), "PROCESSED");
                db.awaitHistory(failed.id(), "FAILED");
                db.awaitHistory(missing.id(), "FAILED");
                assertEquals(attempts.get(), 2);
                assertEquals(failures.get(), 3); // initial attempt plus configured 2 retries
                assertEquals(db.count("bus_events", failed.id(), "AVAILABLE"), 0);
                consumer.close();
                source.close();
            } finally { assertTrue(bus.stopQueue()); }
        }
    }

    @Test(groups = "fast")
    public void testStrictEntryRejectsUninitializedMemorySerializationAndStorageFailures() throws Exception {
        try (final var db = new Storage()) {
            final var bus = db.newBus();
            final var event = TestPersistentPluginEventBus.envelope("target", "jobs");
            assertThrows(PersistentBus.EventBusException.class, () -> bus.postReliable(event));
            assertThrows(PersistentBus.EventBusException.class, () -> bus.registerReliable(new Object()));
            bus.initQueue();
            try {
                assertThrows(PersistentBus.EventBusException.class, () -> bus.postReliable(new BrokenEvent()));
                try (var connection = db.dataSource.getConnection(); var statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE bus_events RENAME TO unavailable_events");
                    try { assertThrows(PersistentBus.EventBusException.class, () -> bus.postReliable(event)); }
                    finally { statement.execute("ALTER TABLE unavailable_events RENAME TO bus_events"); }
                }
                bus.postReliable(event);
                assertEquals(db.count("bus_events", event.id(), "AVAILABLE"), 1);
            } finally { bus.startQueue(); assertTrue(bus.stopQueue()); }
            final var config = new AugmentedConfigurationObjectFactory(db.properties).buildWithReplacements(PersistentBusConfig.class, Map.of("instanceName", "main"));
            final var memory = new InMemoryPersistentBus(config);
            memory.initQueue();
            assertThrows(PersistentBus.EventBusException.class, () -> memory.postReliable(event));
            assertThrows(PersistentBus.EventBusException.class, () -> memory.registerReliable(new Object()));
            memory.stopQueue();
            db.properties.setProperty("org.killbill.persistent.bus.main.inMemory", "true");
            final var fakeDurable = db.newBus();
            fakeDurable.initQueue();
            try {
                assertThrows(PersistentBus.EventBusException.class, () -> fakeDurable.postReliable(event));
                assertThrows(PersistentBus.EventBusException.class, () -> fakeDurable.registerReliable(new Object()));
            } finally { fakeDurable.startQueue(); assertTrue(fakeDurable.stopQueue()); }
        }
    }

    @Test(groups = "fast")
    public void testStoppedAdapterDoesNotSilentlyAcknowledgeQueueDrain() throws Exception {
        try (final var db = new Storage()) {
            final var bus = db.newBus();
            bus.initQueue();
            final var event = TestPersistentPluginEventBus.envelope("target", "jobs");
            try (final var adapter = new PersistentPluginEventBus(bus, new DefaultServiceRegistry(), TestPersistentPluginEventBus.enabled())) {
                adapter.start();
                bus.postReliable(event);
                adapter.close();
                bus.startQueue();
                db.awaitHistory(event.id(), "FAILED");
            } finally { assertTrue(bus.stopQueue()); }
        }
    }

    private static PluginRuntimeConfig retryTestConfig() {
        return new TestPluginRuntimeConfig() {
            @Override public boolean isPluginDurableEventsEnabled() { return true; }
            @Override public int getPluginDurableEventMaxPublications() { return 1; }
            @Override public int getPluginDurableEventMaxDeliveries() { return 2; }
        };
    }

    public static final class BrokenEvent implements BusEvent {
        public String getPayload() { throw new IllegalStateException("serialization failed"); }
        @Override public UUID getUserToken() { return UUID.randomUUID(); }
        @Override public Long getSearchKey1() { return null; }
        @Override public Long getSearchKey2() { return null; }
    }

    static final class Storage implements AutoCloseable {
        final Path directory = Files.createTempDirectory("lpr-events-db-");
        final JdbcDataSource dataSource = new JdbcDataSource();
        final Properties properties = new Properties();

        Storage() throws Exception {
            dataSource.setURL("jdbc:h2:file:" + directory.resolve("events") + ";MODE=LEGACY;DB_CLOSE_ON_EXIT=FALSE");
            properties.setProperty("org.killbill.persistent.bus.main.sleep", "10");
            properties.setProperty("org.killbill.persistent.bus.main.nbThreads", "2");
            properties.setProperty("org.killbill.persistent.bus.main.max.failure.retry", "2");
            properties.setProperty("org.killbill.persistent.bus.main.shutdownTimeout", "5s");
            try (Connection connection = dataSource.getConnection(); var ddl = DefaultPersistentBus.class.getResourceAsStream("/org/killbill/queue/ddl.sql")) {
                assertNotNull(ddl);
                RunScript.execute(connection, new StringReader(new String(ddl.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }

        DefaultPersistentBus newBus() { return new DefaultPersistentBus(dataSource, properties); }

        int count(final String table, final UUID id, final String state) throws Exception {
            try (var connection = dataSource.getConnection();
                 var statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE user_token=? AND processing_state=?")) {
                statement.setString(1, id.toString());
                statement.setString(2, state);
                try (var result = statement.executeQuery()) { result.next(); return result.getInt(1); }
            }
        }

        void awaitHistory(final UUID id, final String state) throws Exception {
            final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline) {
                if (count("bus_events_history", id, state) == 1) { return; }
                Thread.sleep(20);
            }
            fail("Missing persistent history " + id + " " + state);
        }

        @Override
        public void close() throws Exception {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            } finally {
                try (var paths = Files.walk(directory)) {
                    for (final var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
                }
            }
        }
    }
}

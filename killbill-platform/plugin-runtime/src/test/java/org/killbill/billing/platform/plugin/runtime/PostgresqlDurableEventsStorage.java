/* Copyright 2020-2026 The Billing Project, LLC. Licensed under the Apache License, Version 2.0. */
package org.killbill.billing.platform.plugin.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.killbill.billing.lpr.api.DurableEvent;
import org.killbill.bus.DefaultPersistentBus;
import org.killbill.bus.api.PersistentBusConfig;
import org.killbill.clock.DefaultClock;
import org.killbill.commons.jdbi.notification.DatabaseTransactionNotificationApi;
import org.killbill.commons.metrics.impl.NoOpMetricRegistry;
import org.killbill.queue.InTransaction;
import org.killbill.queue.QueueObjectMapper;
import org.postgresql.ds.PGSimpleDataSource;
import org.skife.config.AugmentedConfigurationObjectFactory;

import static org.testng.Assert.*;

/** Connects only to a fresh database provisioned by tools/validation; never creates roles or a shared schema. */
final class PostgresqlDurableEventsStorage {
    static final String ACTIVE = "bus_ext_events";
    static final String HISTORY = "bus_ext_events_history";
    final PGSimpleDataSource source = new PGSimpleDataSource();
    final Path directory;
    final Path config;

    static PostgresqlDurableEventsStorage provisioned() throws Exception {
        final String prefix = "org.killbill.billing.dbi.test.localDb.";
        assertTrue(Boolean.getBoolean("org.killbill.billing.dbi.test.useLocalDb"), "An isolated PostgreSQL DB is required");
        final var values = new Properties();
        for (final String name : new String[]{"database", "username", "password"}) {
            values.setProperty(name, System.getProperty(prefix + name, ""));
        }
        final Path output = Path.of(System.getProperty(prefix + "output", System.getProperty("java.io.tmpdir")));
        Files.createDirectories(output);
        final Path directory = Files.createTempDirectory(output, "durable-events-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        final Path config = directory.resolve("connection.properties");
        try (var stream = Files.newOutputStream(config)) { values.store(stream, "Private test connection"); }
        Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-------"));
        final var storage = new PostgresqlDurableEventsStorage(config);
        try (var connection = storage.source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS validation_durable_effects " +
                              "(message_id varchar(36) PRIMARY KEY, topic varchar(128), payload text)");
        }
        System.out.println("Using PostgreSQL local database: " + values.getProperty("database") + "; evidence=" + directory);
        return storage;
    }

    PostgresqlDurableEventsStorage(final Path config) throws Exception {
        this.config = config;
        directory = config.getParent();
        final var values = new Properties();
        try (var stream = Files.newInputStream(config)) { values.load(stream); }
        assertTrue(values.getProperty("database", "").matches("kb_accept_[0-9a-f]{32}"), "Refuse non-acceptance DB");
        source.setServerNames(new String[]{"localhost"});
        source.setPortNumbers(new int[]{5432});
        source.setDatabaseName(values.getProperty("database"));
        source.setUser(values.getProperty("username"));
        source.setPassword(values.getProperty("password"));
        source.setConnectTimeout(5);
        source.setSocketTimeout(10);
        source.setOptions("-c lock_timeout=300ms -c statement_timeout=2s");
    }

    DefaultPersistentBus newBus() {
        final var properties = new Properties();
        final String prefix = "org.killbill.persistent.bus.external.";
        properties.setProperty(prefix + "tableName", ACTIVE);
        properties.setProperty(prefix + "historyTableName", HISTORY);
        properties.setProperty(prefix + "sleep", "10");
        properties.setProperty(prefix + "nbThreads", "1");
        properties.setProperty(prefix + "max.failure.retry", "2");
        properties.setProperty(prefix + "shutdownTimeout", "5s");
        properties.setProperty(prefix + "claim.time", "1s");
        properties.setProperty(prefix + "reapThreshold", "2s");
        properties.setProperty(prefix + "reapSchedule", "20ms");
        final var config = new AugmentedConfigurationObjectFactory(properties)
                .buildWithReplacements(PersistentBusConfig.class, Map.of("instanceName", "external"));
        return new DefaultPersistentBus(InTransaction.buildDDBI(source), new DefaultClock(), config,
                                        new NoOpMetricRegistry(), new DatabaseTransactionNotificationApi());
    }

    static DurableEvent message(final UUID id) { return new DurableEvent(id, "invoice", "{\"text\":\"恢复验证\"}"); }

    int count(final String table, final UUID id, final String state) throws SQLException {
        assertTrue(table.equals(ACTIVE) || table.equals(HISTORY));
        try (var connection = source.getConnection();
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE user_token=? AND processing_state=?")) {
            statement.setString(1, id.toString());
            statement.setString(2, state);
            try (var rows = statement.executeQuery()) { rows.next(); return rows.getInt(1); }
        }
    }

    void awaitHistory(final UUID id, final String state) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (count(HISTORY, id, state) == 1) { return; }
            Thread.sleep(20);
        }
        fail("Missing PostgreSQL history " + id + " / " + state);
    }

    void verifyEnvelope(final String table, final UUID id, final String state) throws Exception {
        assertTrue(table.equals(ACTIVE) || table.equals(HISTORY));
        try (var connection = source.getConnection();
             var statement = connection.prepareStatement("SELECT class_name,event_json,processing_state FROM " + table +
                                                         " WHERE user_token=? AND processing_state=?")) {
            statement.setString(1, id.toString());
            statement.setString(2, state);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next(), "Missing envelope " + id);
                assertEquals(rows.getString(1), PersistentPluginEvent.class.getName());
                final var envelope = QueueObjectMapper.get().readValue(rows.getString(2), PersistentPluginEvent.class);
                assertEquals(envelope.message(), message(id));
                assertEquals(rows.getString(3), state);
                assertFalse(rows.next(), "Duplicate queue envelope " + id);
            }
        }
    }

    void effect(final DurableEvent event) throws SQLException {
        try (var connection = source.getConnection();
             var statement = connection.prepareStatement("INSERT INTO validation_durable_effects(message_id,topic,payload) " +
                                                         "VALUES (?,?,?) ON CONFLICT (message_id) DO NOTHING")) {
            statement.setString(1, event.id().toString());
            statement.setString(2, event.topic());
            statement.setString(3, event.payload());
            statement.executeUpdate();
        }
    }

    int effects(final UUID id) throws SQLException {
        try (var connection = source.getConnection();
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM validation_durable_effects WHERE message_id=?")) {
            statement.setString(1, id.toString());
            try (var rows = statement.executeQuery()) { rows.next(); return rows.getInt(1); }
        }
    }
}

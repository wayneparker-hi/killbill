/* Copyright 2020-2026 The Billing Project, LLC. Licensed under the Apache License, Version 2.0. */
package org.killbill.notificationq;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.tools.RunScript;
import org.killbill.clock.ClockMock;
import org.killbill.commons.metrics.impl.NoOpMetricRegistry;
import org.killbill.notificationq.api.NotificationQueueConfig;
import org.killbill.queue.InTransaction;
import org.postgresql.ds.PGSimpleDataSource;
import org.skife.config.AugmentedConfigurationObjectFactory;
import org.skife.jdbi.v2.DBI;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/** Isolated H2 by default; external PostgreSQL is provisioned by tools/validation. */
final class QueueShutdownStorage implements AutoCloseable {
    final ClockMock clock = new ClockMock();
    final DataSource source;
    final DBI dbi;

    QueueShutdownStorage() throws Exception {
        if (Boolean.getBoolean("org.killbill.billing.dbi.test.useLocalDb")) {
            final String prefix = "org.killbill.billing.dbi.test.localDb.";
            final String database = System.getProperty(prefix + "database", "");
            assertTrue(database.matches("kb_accept_[0-9a-f]{32}"), "Only a generated acceptance database is allowed");
            final PGSimpleDataSource postgres = new PGSimpleDataSource();
            postgres.setServerNames(new String[]{"localhost"});
            postgres.setPortNumbers(new int[]{5432});
            postgres.setDatabaseName(database);
            postgres.setUser(System.getProperty(prefix + "username"));
            postgres.setPassword(System.getProperty(prefix + "password"));
            source = postgres;
            System.out.println("Using PostgreSQL local database: " + database);
        } else {
            source = JdbcConnectionPool.create("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=LEGACY", "sa", "");
            try (var connection = source.getConnection();
                 var ddl = getClass().getResourceAsStream("/org/killbill/queue/ddl.sql")) {
                assertNotNull(ddl);
                RunScript.execute(connection, new InputStreamReader(ddl, StandardCharsets.UTF_8));
            }
        }
        dbi = new DBI(source);
        InTransaction.setupDBI(dbi);
    }

    DefaultNotificationQueueService service(final String timeout) {
        final NotificationQueueConfig config = new AugmentedConfigurationObjectFactory(properties(timeout))
                .buildWithReplacements(NotificationQueueConfig.class, Map.of("instanceName", "main"));
        return new DefaultNotificationQueueService(dbi, clock, config, new NoOpMetricRegistry());
    }

    static Properties properties(final String timeout) {
        final Properties properties = new Properties();
        final String prefix = "org.killbill.notificationq.main.";
        properties.setProperty(prefix + "sleep", "10");
        properties.setProperty(prefix + "notification.nbThreads", "1");
        properties.setProperty(prefix + "lifecycle.complete.nbThreads", "1");
        properties.setProperty(prefix + "shutdownTimeout", timeout);
        properties.setProperty(prefix + "claim.time", "1s");
        properties.setProperty(prefix + "reapThreshold", "2s");
        properties.setProperty(prefix + "reapSchedule", "20ms");
        return properties;
    }

    int count(final String table, final String queue, final String state) throws Exception {
        try (var connection = source.getConnection();
             var query = connection.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE queue_name=? AND processing_state=?")) {
            query.setString(1, queue);
            query.setString(2, state);
            try (var rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    @Override
    public void close() {
        if (source instanceof JdbcConnectionPool) {
            ((JdbcConnectionPool) source).dispose();
        }
    }
}

/* Copyright 2020-2026 The Billing Project, LLC. Licensed under the Apache License, Version 2.0. */
package org.killbill.billing.platform.plugin.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.*;

final class DurableEventsWorkerProcess implements AutoCloseable {
    final Process process;
    final Path log;

    private DurableEventsWorkerProcess(final Process process, final Path log) {
        this.process = process;
        this.log = log;
    }

    static void run(final PostgresqlDurableEventsStorage storage, final String mode,
                    final UUID id, final int expectedExit) throws Exception {
        try (final var worker = start(storage, DurableEventsCrashWorker.class, mode, id.toString())) {
            worker.awaitExit(expectedExit);
        }
    }

    static DurableEventsWorkerProcess start(final PostgresqlDurableEventsStorage storage, final Class<?> type,
                                           final String mode, final String argument) throws Exception {
        final var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Duser.language=en");
        command.add("-Duser.country=US");
        command.add("-Duser.timezone=" + java.util.TimeZone.getDefault().getID());
        command.add("-Dorg.killbill.queue.creator.name=" + mode + "-" + argument);
        for (final String name : new String[]{"java.lang", "java.io", "java.util", "java.base"}) {
            command.add("--add-opens=java.base/" + name + "=ALL-UNNAMED");
        }
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(type.getName());
        command.add(storage.config.toString());
        command.add(mode);
        command.add(argument);
        final Path log = storage.directory.resolve(mode + "-" + argument + ".log");
        final var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        for (final String name : new String[]{"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}) {
            builder.environment().remove(name);
        }
        final Process process = builder.start();
        System.out.println("durable worker start pid=" + process.pid() + " log=" + log);
        return new DurableEventsWorkerProcess(process, log);
    }

    void awaitExit(final int expectedExit) throws Exception {
        assertTrue(process.waitFor(25, TimeUnit.SECONDS), "Child JVM timeout; see " + log);
        System.out.println("durable worker pid=" + process.pid() + " exit=" + process.exitValue() + " log=" + log);
        assertEquals(process.exitValue(), expectedExit, "Unexpected child exit; see " + log);
    }

    void kill() throws Exception {
        assertTrue(process.isAlive(), "Child exited before fault injection; see " + log);
        process.destroyForcibly();
        awaitExit(137);
    }

    @Override
    public void close() throws Exception {
        if (process.isAlive()) {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Could not terminate child " + process.pid());
            System.out.println("durable worker cleanup pid=" + process.pid() + " exit=" + process.exitValue() + " log=" + log);
        }
    }
}

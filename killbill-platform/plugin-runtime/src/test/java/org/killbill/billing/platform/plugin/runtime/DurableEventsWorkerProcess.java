/* Copyright 2020-2026 The Billing Project, LLC. Licensed under the Apache License, Version 2.0. */
package org.killbill.billing.platform.plugin.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.*;

final class DurableEventsWorkerProcess {
    static void run(final PostgresqlDurableEventsStorage storage, final String mode,
                    final UUID id, final int expectedExit) throws Exception {
        final var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Duser.language=en");
        command.add("-Duser.country=US");
        for (final String name : new String[]{"java.lang", "java.io", "java.util", "java.base"}) {
            command.add("--add-opens=java.base/" + name + "=ALL-UNNAMED");
        }
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(DurableEventsCrashWorker.class.getName());
        command.add(storage.config.toString());
        command.add(mode);
        command.add(id.toString());
        final Path log = storage.directory.resolve(mode + "-" + id + ".log");
        final var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        for (final String name : new String[]{"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}) {
            builder.environment().remove(name);
        }
        final Process worker = builder.start();
        try {
            assertTrue(worker.waitFor(25, TimeUnit.SECONDS), "Child JVM timeout; see " + log);
            System.out.println("durable worker mode=" + mode + " pid=" + worker.pid() + " exit=" + worker.exitValue() + " log=" + log);
            assertEquals(worker.exitValue(), expectedExit, "Unexpected child exit; see " + log);
        } finally {
            if (worker.isAlive()) {
                worker.destroyForcibly();
                assertTrue(worker.waitFor(5, TimeUnit.SECONDS), "Could not terminate child " + worker.pid());
            }
        }
    }
}

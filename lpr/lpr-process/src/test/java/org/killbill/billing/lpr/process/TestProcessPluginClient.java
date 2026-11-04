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
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.lpr.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.killbill.billing.lpr.spi.PluginArtifact;
import org.killbill.billing.lpr.spi.PluginDescriptor;
import org.killbill.billing.lpr.testkit.PluginJarBuilder;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class TestProcessPluginClient {
    private Path fixtures;
    private Path jar;
    private static final String ENTRY = "org.killbill.billing.plugin.process.Fixture";
    static final Path JAVA = Path.of(ProcessHandle.current().info().command().orElseThrow());

    @BeforeClass(groups = "fast")
    public void buildPlugin() throws Exception {
        fixtures = Files.createTempDirectory(Path.of("/tmp"), "lpr-proc-fixture-");
        jar = new PluginJarBuilder().build(Map.of(ENTRY, source().replace("STARTUP_PID_PATH", fixtures.resolve("startup-pid").toString())), fixtures.resolve("fixture.jar"));
    }

    @AfterClass(groups = "fast", alwaysRun = true)
    public void removePlugin() throws Exception {
        try (var files = Files.walk(fixtures)) {
            for (final Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
        }
    }

    static ProcessPluginSettings settings(final Path root) {
        return new ProcessPluginSettings(JAVA, root, Duration.ofSeconds(5), Duration.ofMillis(600),
                                         Duration.ofSeconds(2), 1024, 32);
    }

    private ProcessPluginClient launch(final Path root) throws IOException {
        return new ProcessPluginLauncher().launch(PluginArtifact.of("fixture", "1.0.0", jar),
                PluginDescriptor.of("fixture", "1.0.0", ENTRY), settings(root));
    }

    @Test(groups = "fast")
    public void testRealForkPrivateClassAndDataRoundTrip() throws Exception {
        final Path root = Files.createTempDirectory(Path.of("/tmp"), "lpr-sock-");
        try (final var client = launch(root)) {
            assertNotEquals(client.pid(), ProcessHandle.current().pid());
            assertEquals(client.invoke("pid", List.of()), List.of(Long.toString(client.pid())));
            assertEquals(client.invoke("echo", List.of("中文", "\n\u0000", "", "😀")), List.of("中文", "\n\u0000", "", "😀"));
            assertThrows(ClassNotFoundException.class, () -> Class.forName(ENTRY));
            assertEquals(client.invoke("host-visible", List.of()), List.of("false"));
            try (var files = Files.list(root)) {
                final Path directory = files.findFirst().orElseThrow();
                assertEquals(Files.getPosixFilePermissions(directory), PosixFilePermissions.fromString("rwx------"));
            }
        } finally { assertEmptyAndRemove(root); }
    }

    @Test(groups = "fast")
    public void testRemoteFailureDoesNotClaimRollbackAndChannelCanContinue() throws Exception {
        final Path root = Files.createTempDirectory(Path.of("/tmp"), "lpr-sock-");
        try (final var client = launch(root)) {
            final var failure = expectThrows(ProcessPluginCallException.class, () -> client.invoke("throw", List.of()));
            assertTrue(failure.isRemoteFailure());
            assertTrue(failure.isResultUnknown());
            assertTrue(client.isAlive());
            assertEquals(client.invoke("echo", List.of("after-failure")), List.of("after-failure"));
        } finally { assertEmptyAndRemove(root); }
    }

    @Test(groups = "fast")
    public void testCrashAndActualChildHeapExhaustionDoNotKillHostOrOtherPlugin() throws Exception {
        final Path root = Files.createTempDirectory(Path.of("/tmp"), "lpr-sock-");
        try (final var healthy = launch(root)) {
            for (final String operation : List.of("crash", "oom")) {
                try (final var failed = launch(root)) {
                    final var error = expectThrows(ProcessPluginCallException.class, () -> failed.invoke(operation, List.of()));
                    assertTrue(error.isResultUnknown());
                    assertFalse(error.isRemoteFailure());
                    assertFalse(ProcessHandle.of(failed.pid()).map(ProcessHandle::isAlive).orElse(false));
                    assertThrows(IllegalStateException.class, () -> failed.invoke("echo", List.of()));
                    assertEquals(healthy.invoke("pid", List.of()), List.of(Long.toString(healthy.pid())));
                }
            }
        } finally { assertEmptyAndRemove(root); }
    }

    @Test(groups = "fast")
    public void testDeadlineKillsNonCooperativeProcessIncludingShutdownHook() throws Exception {
        final Path root = Files.createTempDirectory(Path.of("/tmp"), "lpr-sock-");
        try (final var client = launch(root)) {
            final long start = System.nanoTime();
            final var error = expectThrows(ProcessPluginCallException.class, () -> client.invoke("hang", List.of()));
            assertTrue(error.isResultUnknown());
            assertEquals(error.getSuppressed().length, 0, "Worker must exit within the cleanup budget");
            assertTrue(System.nanoTime() - start < Duration.ofSeconds(5).toNanos());
            assertFalse(ProcessHandle.of(client.pid()).map(ProcessHandle::isAlive).orElse(false));
        } finally { assertEmptyAndRemove(root); }
    }

    @Test(groups = "fast")
    public void testPreflightBudgetRejectsWithoutDispatchOrClosingChannel() throws Exception {
        final Path root = Files.createTempDirectory(Path.of("/tmp"), "lpr-sock-");
        try (final var client = launch(root)) {
            for (final String value : List.of("x".repeat(1100), "中".repeat(400), "\ud800")) {
                assertThrows(IllegalArgumentException.class, () -> client.invoke("echo", List.of(value)));
            }
            assertThrows(IllegalArgumentException.class, () -> client.invoke(" ", List.of()));
            assertThrows(IllegalArgumentException.class, () -> client.invoke("echo", java.util.Collections.nCopies(256, "")));
            assertEquals(client.invoke("echo", List.of("still-alive")), List.of("still-alive"));
        } finally { assertEmptyAndRemove(root); }
    }

    @Test(groups = "fast")
    public void testOversizeOrNullPluginResultsFailWithoutSuccess() throws Exception {
        final Path root = Files.createTempDirectory(Path.of("/tmp"), "lpr-sock-");
        try {
            for (final String operation : List.of("huge", "null")) {
                try (final var client = launch(root)) {
                    assertThrows(ProcessPluginCallException.class, () -> client.invoke(operation, List.of()));
                }
            }
        } finally { assertEmptyAndRemove(root); }
    }

    @Test(groups = "fast")
    public void testStdoutCannotBlockProtocolOrCreateLogFile() throws Exception {
        final Path root = Files.createTempDirectory(Path.of("/tmp"), "lpr-sock-");
        try (final var client = launch(root)) {
            assertEquals(client.invoke("output", List.of()), List.of("done"));
        } finally { assertEmptyAndRemove(root); }
    }

    @Test(groups = "fast")
    public void testConcurrentAdmissionAndClosingInflightIo() throws Exception {
        final Path root = Files.createTempDirectory(Path.of("/tmp"), "lpr-sock-");
        final Path entered = fixtures.resolve("entered-" + java.util.UUID.randomUUID());
        try (final var client = launch(root); final var executor = Executors.newSingleThreadExecutor()) {
            final var future = executor.submit(() -> client.invoke("marked-hang", List.of(entered.toString())));
            awaitFile(entered);
            assertThrows(RejectedExecutionException.class, () -> client.invoke("echo", List.of("must-not-run")));
            client.close();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertFalse(client.isAlive());
            client.close();
        } finally { Files.deleteIfExists(entered); assertEmptyAndRemove(root); }
    }

    @Test(groups = "fast")
    public void testInterruptedCallPreservesInterruptAndTerminatesWorker() throws Exception {
        final Path root = Files.createTempDirectory(Path.of("/tmp"), "lpr-sock-");
        final Path entered = fixtures.resolve("entered-" + java.util.UUID.randomUUID());
        try (final var client = launch(root)) {
            final var error = new AtomicReference<Throwable>();
            final var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
            final Thread thread = new Thread(() -> {
                try { client.invoke("marked-hang", List.of(entered.toString())); }
                catch (final Throwable e) { error.set(e); interrupted.set(Thread.currentThread().isInterrupted()); }
            });
            thread.start();
            try { awaitFile(entered); thread.interrupt(); }
            finally { thread.join(5000); }
            assertFalse(thread.isAlive());
            assertTrue(error.get() instanceof ProcessPluginCallException);
            assertTrue(interrupted.get());
            assertFalse(ProcessHandle.of(client.pid()).map(ProcessHandle::isAlive).orElse(false));
        } finally { Files.deleteIfExists(entered); assertEmptyAndRemove(root); }
    }

    @Test(groups = "fast")
    public void testStartupFailureAndDeadlineCleanEveryResource() throws Exception {
        final Path root = Files.createTempDirectory(Path.of("/tmp"), "lpr-sock-");
        try {
            for (final String entry : List.of("missing.Entry", ENTRY + "$FailStart", ENTRY + "$HangStart")) {
                final var configured = settings(root);
                final var shortStartup = new ProcessPluginSettings(JAVA, root, Duration.ofMillis(900), configured.callTimeout(),
                        configured.stopTimeout(), configured.maxFrameBytes(), configured.maxHeapMiB());
                assertThrows(IOException.class, () -> new ProcessPluginLauncher().launch(PluginArtifact.of("fixture", "1.0.0", jar),
                        PluginDescriptor.of("fixture", "1.0.0", entry), shortStartup));
                try (var files = Files.list(root)) { assertEquals(files.count(), 0L); }
                final Path marker = fixtures.resolve("startup-pid");
                if (Files.exists(marker)) {
                    final long pid = Long.parseLong(Files.readString(marker));
                    assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
                    Files.delete(marker);
                }
            }
        } finally { assertEmptyAndRemove(root); }
    }

    @Test(groups = "fast")
    public void testArtifactIdentityAndSettingsAreRejectedBeforeSpawn() throws Exception {
        final Path root = Files.createTempDirectory(Path.of("/tmp"), "lpr-sock-");
        try {
            assertThrows(IllegalArgumentException.class, () -> new ProcessPluginLauncher().launch(PluginArtifact.of("fixture", "1.0.0", jar),
                    PluginDescriptor.of("other", "1.0.0", ENTRY), settings(root)));
            assertThrows(IllegalArgumentException.class, () -> new ProcessPluginSettings(JAVA, root, Duration.ZERO, Duration.ofSeconds(1),
                    Duration.ofSeconds(1), 1024, 32));
            assertThrows(IllegalArgumentException.class, () -> new ProcessPluginSettings(JAVA, root, Duration.ofSeconds(1), Duration.ofSeconds(1),
                    Duration.ofSeconds(1), 1_048_577, 32));
        } finally { assertEmptyAndRemove(root); }
    }

    @Test(groups = "fast")
    public void testWorkerExitsWhenLaunchingParentHalts() throws Exception {
        final Path root = Files.createTempDirectory(Path.of("/tmp"), "lpr-sock-");
        final Path pidFile = fixtures.resolve("orphan-pid");
        final String classpath = ProcessPluginLauncher.runtimeClasspath() + java.io.File.pathSeparator
                + Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        final Process parent = new ProcessBuilder(JAVA.toString(), "-cp", classpath, AbruptParent.class.getName(),
                root.toString(), jar.toString(), pidFile.toString()).redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        long childPid = -1;
        try {
            assertTrue(parent.waitFor(10, TimeUnit.SECONDS));
            assertEquals(parent.exitValue(), 0);
            childPid = Long.parseLong(Files.readString(pidFile));
            final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) { Thread.sleep(20); }
            assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            parent.destroyForcibly();
            if (childPid > 0) { ProcessHandle.of(childPid).ifPresent(ProcessHandle::destroyForcibly); }
            Files.deleteIfExists(pidFile);
            Files.deleteIfExists(Path.of(pidFile + ".entered"));
            // An abruptly killed parent cannot delete its own files; operator cleans stale private directories.
            try (var files = Files.walk(root)) {
                for (final Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
            }
        }
    }

    @Test(groups = "fast")
    public void testInheritedJavaOptionsCannotOverrideWorkerRuntime() throws Exception {
        final Path root = Files.createTempDirectory(Path.of("/tmp"), "lpr-env-");
        final String classpath = ProcessPluginLauncher.runtimeClasspath() + java.io.File.pathSeparator
                + Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        final var builder = new ProcessBuilder(JAVA.toString(), "-cp", classpath, EnvironmentParent.class.getName(),
                root.toString(), jar.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("JAVA_TOOL_OPTIONS", "-Dlpr.test.tool=true -Xmx256m");
        builder.environment().put("JDK_JAVA_OPTIONS", "-Dlpr.test.jdk=true");
        builder.environment().put("_JAVA_OPTIONS", "-Dlpr.test.legacy=true");
        final Process parent = builder.start();
        try {
            assertTrue(parent.waitFor(10, TimeUnit.SECONDS));
            assertEquals(parent.exitValue(), 0);
        } finally { parent.destroyForcibly(); assertEmptyAndRemove(root); }
    }

    public static final class EnvironmentParent {
        public static void main(final String[] args) throws Exception {
            final var settings = new ProcessPluginSettings(Path.of(ProcessHandle.current().info().command().orElseThrow()), Path.of(args[0]),
                    Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(2), 1024, 32);
            try (final var client = new ProcessPluginLauncher().launch(PluginArtifact.of("fixture", "1.0.0", Path.of(args[1])),
                    PluginDescriptor.of("fixture", "1.0.0", ENTRY), settings)) {
                if (!Boolean.getBoolean("lpr.test.tool") || !Boolean.getBoolean("lpr.test.jdk") || !Boolean.getBoolean("lpr.test.legacy")) {
                    throw new IllegalStateException("Control parent did not inherit Java options");
                }
                final var result = client.invoke("java-options", List.of());
                if (!result.subList(0, 3).equals(List.of("false", "false", "false")) || Long.parseLong(result.get(3)) > 32) {
                    throw new IllegalStateException("Worker inherited Java option overrides");
                }
            }
        }
    }

    public static final class AbruptParent {
        public static void main(final String[] args) throws Exception {
            final Path java = Path.of(ProcessHandle.current().info().command().orElseThrow());
            final var config = new ProcessPluginSettings(java, Path.of(args[0]), Duration.ofSeconds(5), Duration.ofSeconds(1),
                                                        Duration.ofSeconds(1), 1024, 32);
            final var client = new ProcessPluginLauncher().launch(PluginArtifact.of("fixture", "1.0.0", Path.of(args[1])),
                    PluginDescriptor.of("fixture", "1.0.0", ENTRY), config);
            Files.writeString(Path.of(args[2]), Long.toString(client.pid()));
            final Path entered = Path.of(args[2] + ".entered");
            final Thread blocked = new Thread(() -> {
                try { client.invoke("marked-hang", List.of(entered.toString())); }
                catch (final Exception failure) { throw new IllegalStateException(failure); }
            });
            blocked.setDaemon(true);
            blocked.start();
            final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!Files.exists(entered) && System.nanoTime() < deadline) { Thread.sleep(5); }
            if (!Files.exists(entered)) { Runtime.getRuntime().halt(2); }
            Runtime.getRuntime().halt(0);
        }
    }

    private static void awaitFile(final Path file) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!Files.exists(file) && System.nanoTime() < deadline) { Thread.sleep(5); }
        assertTrue(Files.exists(file));
    }

    private static void assertEmptyAndRemove(final Path root) throws Exception {
        try (var files = Files.list(root)) { assertEquals(files.count(), 0L, "Worker left socket resources"); }
        Files.delete(root);
    }

    private static String source() {
        return """
                package org.killbill.billing.plugin.process;
                import java.util.*;
                import org.killbill.billing.lpr.api.ProcessPlugin;
                public final class Fixture implements ProcessPlugin {
                    public List<String> invoke(String operation, List<String> arguments) throws Exception {
                        switch (operation) {
                            case "pid": return List.of(Long.toString(ProcessHandle.current().pid()));
                            case "java-options": return List.of(Boolean.toString(Boolean.getBoolean("lpr.test.tool")),
                                    Boolean.toString(Boolean.getBoolean("lpr.test.jdk")), Boolean.toString(Boolean.getBoolean("lpr.test.legacy")),
                                    Long.toString(Runtime.getRuntime().maxMemory() / (1024 * 1024)));
                            case "crash": Runtime.getRuntime().halt(73); break;
                            case "oom":
                                List<byte[]> memory = new ArrayList<>();
                                while (true) { memory.add(new byte[1024 * 1024]); }
                            case "throw": throw new IllegalStateException("remote business failure");
                            case "huge": return List.of("x".repeat(5000));
                            case "null": return null;
                            case "marked-hang": java.nio.file.Files.writeString(java.nio.file.Path.of(arguments.get(0)), "started");
                            case "hang":
                                Runtime.getRuntime().addShutdownHook(new Thread(() -> { while (true) { Thread.onSpinWait(); } }));
                                while (true) { Thread.onSpinWait(); }
                            case "output": for (int i = 0; i < 10000; i++) System.out.println("x".repeat(100)); return List.of("done");
                            case "host-visible":
                                try { Class.forName("org.killbill.billing.lpr.testkit.PluginJarBuilder"); return List.of("true"); }
                                catch (ClassNotFoundException expected) { return List.of("false"); }
                            default: return arguments;
                        }
                        return List.of();
                    }
                    public static final class FailStart implements ProcessPlugin {
                        public void start() { throw new IllegalStateException("start failure"); }
                        public List<String> invoke(String op, List<String> args) { return args; }
                    }
                    public static final class HangStart implements ProcessPlugin {
                        public void start() throws Exception {
                            java.nio.file.Files.writeString(java.nio.file.Path.of("STARTUP_PID_PATH"), Long.toString(ProcessHandle.current().pid()));
                            while (true) { Thread.onSpinWait(); }
                        }
                        public List<String> invoke(String op, List<String> args) { return args; }
                    }
                }
                """;
    }
}

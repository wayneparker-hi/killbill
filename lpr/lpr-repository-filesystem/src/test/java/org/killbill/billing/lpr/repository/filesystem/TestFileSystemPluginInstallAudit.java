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

package org.killbill.billing.lpr.repository.filesystem;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.killbill.billing.lpr.spi.PluginInstallAudit;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class TestFileSystemPluginInstallAudit {
    @Test(groups = "fast")
    public void testConcurrentRecordsAreCompleteAndNeverOverwrite() throws Exception {
        final var root = Files.createTempDirectory("lpr-audit-concurrent-");
        final var workers = Executors.newFixedThreadPool(4);
        try {
            final var a = new FileSystemPluginInstallAudit(root);
            final var b = new FileSystemPluginInstallAudit(root);
            final var event = new PluginInstallAudit(UUID.randomUUID(), Instant.now(), "demo", "1.0.0", "https://example.org/plugin.jar",
                    null, null, "a".repeat(64), null, false, List.of(), PluginInstallAudit.Stage.STARTED, null);
            final var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 24; i++) {
                final var sink = i % 2 == 0 ? a : b;
                futures.add(workers.submit(() -> { sink.record(event); return null; }));
            }
            for (final var future : futures) { future.get(10, TimeUnit.SECONDS); }
            try (var files = Files.list(root)) {
                final var paths = files.toList(); assertEquals(paths.size(), 24);
                for (final var path : paths) {
                    final var fields = new java.util.Properties();
                    try (var reader = Files.newBufferedReader(path)) { fields.load(reader); }
                    assertEquals(fields.getProperty("operationId"), event.operationId().toString());
                    assertEquals(fields.getProperty("actualSha256"), event.actualSha256());
                }
            }
        } finally { workers.shutdownNow(); RepositoryFiles.delete(root); }
    }

    @Test(groups = "fast")
    public void testUnavailableAuditDirectoryFailsWithoutPartialRecord() throws Exception {
        final var root = Files.createTempDirectory("lpr-audit-unavailable-");
        try {
            final var directory = root.resolve("audit");
            final var sink = new FileSystemPluginInstallAudit(directory);
            Files.delete(directory); Files.writeString(directory, "unavailable");
            final var event = new PluginInstallAudit(UUID.randomUUID(), Instant.now(), "demo", "1.0.0", "file:/plugin.jar",
                    null, null, null, null, false, List.of(), PluginInstallAudit.Stage.STARTED, null);
            assertThrows(java.io.IOException.class, () -> sink.record(event));
            assertEquals(Files.readString(directory), "unavailable");
        } finally { RepositoryFiles.delete(root); }
    }
}

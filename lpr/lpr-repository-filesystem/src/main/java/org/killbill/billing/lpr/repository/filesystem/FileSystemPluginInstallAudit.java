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

import java.io.IOException;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.UUID;
import org.killbill.billing.lpr.spi.PluginInstallAudit;
import org.killbill.billing.lpr.spi.PluginInstallAuditSink;

/** One immutable properties record per event; UUID filenames isolate concurrent operations/processes. */
public final class FileSystemPluginInstallAudit implements PluginInstallAuditSink {
    private final Path directory;

    public FileSystemPluginInstallAudit(final Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)) { throw new IOException("Audit directory cannot be a symbolic link"); }
        Files.createDirectories(directory);
        this.directory = directory.toRealPath();
    }

    @Override
    public void record(final PluginInstallAudit event) throws IOException {
        final Properties fields = fieldsOf(event);
        final Path target = directory.resolve(event.operationId() + "-" + event.stage() + "-" + UUID.randomUUID() + ".properties");
        final Path staging = Files.createTempFile(directory, ".incoming-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.WRITE);
                 Writer writer = Channels.newWriter(channel, StandardCharsets.UTF_8)) {
                fields.store(writer, "LPR plugin installation audit");
                writer.flush();
                channel.force(true);
            }
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(staging); }
    }

    private static Properties fieldsOf(final PluginInstallAudit event) {
        final Properties fields = new Properties();
        fields.setProperty("operationId", event.operationId().toString());
        fields.setProperty("timestamp", event.timestamp().toString());
        fields.setProperty("pluginId", event.pluginId());
        fields.setProperty("version", event.version());
        fields.setProperty("source", event.source());
        fields.setProperty("stage", event.stage().name());
        fields.setProperty("signatureRequired", Boolean.toString(event.signatureRequired()));
        fields.setProperty("signers", String.join(",", event.signers()));
        put(fields, "resolvedSource", event.resolvedSource());
        put(fields, "expectedSha256", event.expectedSha256());
        put(fields, "actualSha256", event.actualSha256());
        put(fields, "descriptorSha256", event.descriptorSha256());
        put(fields, "failureCode", event.failureCode());
        return fields;
    }

    private static void put(final Properties fields, final String key, final String value) {
        if (value != null) { fields.setProperty(key, value); }
    }
}

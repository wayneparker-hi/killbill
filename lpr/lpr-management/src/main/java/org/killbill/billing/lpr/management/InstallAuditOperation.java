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

package org.killbill.billing.lpr.management;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.killbill.billing.lpr.spi.PluginInstallAudit;
import org.killbill.billing.lpr.spi.PluginInstallAudit.Stage;
import org.killbill.billing.lpr.spi.PluginInstallAuditSink;

/** One invocation's local state; never shared across concurrent installers. */
final class InstallAuditOperation {
    private final UUID id = UUID.randomUUID();
    private final PluginInstallAuditSink sink;
    private final String pluginId;
    private final String version;
    private final ArtifactSource source;
    private final boolean signatureRequired;
    private String actual;
    private String resolvedSource;
    private String descriptor;
    private List<String> signers = List.of();

    InstallAuditOperation(final PluginInstallAuditSink sink, final String pluginId, final String version,
                          final ArtifactSource source, final boolean signatureRequired) {
        this.sink = sink; this.pluginId = pluginId; this.version = version;
        this.source = source; this.signatureRequired = signatureRequired;
    }

    void downloaded(final byte[] bytes, final URI resolved) {
        actual = ArtifactDigests.sha256(bytes);
        resolvedSource = sanitize(resolved);
    }
    void verified(final String contents, final List<String> trustedSigners) {
        descriptor = ArtifactDigests.sha256(contents.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        signers = List.copyOf(trustedSigners);
    }

    void record(final Stage stage, final String failureCode) throws IOException {
        sink.record(new PluginInstallAudit(id, Instant.now(), pluginId, version, sanitize(source.uri()),
                resolvedSource, source.sha256().orElse(null), actual, descriptor, signatureRequired, signers, stage, failureCode));
    }

    static String sanitize(final URI uri) {
        try {
            if (uri.isOpaque()) { return uri.getScheme() + ":<redacted>"; }
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toASCIIString();
        } catch (final URISyntaxException invalid) { return "<redacted>"; }
    }
}

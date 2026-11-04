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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Immutable host policy; node commands cannot weaken it. Trust anchors are administrator supplied. */
public record ArtifactVerificationPolicy(boolean requireSha256, boolean requireSignature,
                                         List<X509Certificate> trustedCertificates, long maxArtifactBytes, long maxExpandedBytes) {
    public ArtifactVerificationPolicy(final boolean requireSha256, final boolean requireSignature,
                                      final List<X509Certificate> trustedCertificates) {
        this(requireSha256, requireSignature, trustedCertificates, 64L * 1024 * 1024, 256L * 1024 * 1024);
    }

    public ArtifactVerificationPolicy {
        if (maxArtifactBytes <= 0 || maxArtifactBytes > Integer.MAX_VALUE || maxExpandedBytes <= 0) {
            throw new IllegalArgumentException("Invalid artifact/expanded byte limits");
        }
        trustedCertificates = List.copyOf(Objects.requireNonNull(trustedCertificates));
        if (requireSignature && trustedCertificates.isEmpty()) {
            throw new IllegalArgumentException("Signature verification requires trusted certificates");
        }
    }

    public static ArtifactVerificationPolicy optional() {
        return new ArtifactVerificationPolicy(false, false, List.of());
    }

    public static ArtifactVerificationPolicy fromPem(final boolean requireSha256, final boolean requireSignature,
                                                       final Path certificates) throws IOException {
        if (!requireSignature) { return new ArtifactVerificationPolicy(requireSha256, false, List.of()); }
        if (certificates == null) { throw new IllegalArgumentException("Trusted certificate PEM path is required"); }
        try (InputStream input = Files.newInputStream(certificates)) {
            final Collection<? extends java.security.cert.Certificate> parsed =
                    CertificateFactory.getInstance("X.509").generateCertificates(input);
            return new ArtifactVerificationPolicy(requireSha256, true,
                    parsed.stream().map(certificate -> (X509Certificate) certificate).toList());
        } catch (final java.security.cert.CertificateException e) {
            throw new IOException("Cannot parse trusted certificates", e);
        }
    }
}

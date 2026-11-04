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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.GeneralSecurityException;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Fully consumes verified entries before consulting CodeSigner. No artifact code is loaded. */
final class JarSignatureVerifier {
    record VerifiedJar(String descriptor, List<String> signers) { }
    private final ArtifactVerificationPolicy policy;

    JarSignatureVerifier(final ArtifactVerificationPolicy policy) { this.policy = policy; }

    VerifiedJar verify(final byte[] bytes) throws IOException {
        final Path staging = Files.createTempFile("lpr-signature-", ".jar");
        try {
            Files.write(staging, bytes);
            preflight(staging);
            try (JarFile jar = new JarFile(staging.toFile(), true)) { return verifyEntries(jar); }
        } catch (final GeneralSecurityException | SecurityException e) {
            throw new IOException("JAR signature or certificate validation failed", e);
        } finally { Files.deleteIfExists(staging); }
    }

    private void preflight(final Path path) throws IOException {
        long total = 0;
        final Set<String> names = new HashSet<>();
        try (var zip = new java.util.zip.ZipFile(path.toFile())) {
            final var entries = zip.entries();
            while (entries.hasMoreElements()) {
                final var entry = entries.nextElement();
                validateName(entry.getName(), names);
                final long size = entry.getSize();
                if (size < 0 || size > policy.maxExpandedBytes() - total) {
                    throw new IOException("Expanded JAR exceeds configured byte limit");
                }
                total += size;
            }
        }
    }

    private VerifiedJar verifyEntries(final JarFile jar) throws IOException, GeneralSecurityException {
        final var manifest = jar.getManifest();
        if (manifest != null && manifest.getMainAttributes().getValue("Class-Path") != null) {
            throw new SecurityException("Signed installations cannot reference an external manifest Class-Path");
        }
        final Set<String> names = new HashSet<>();
        Set<String> commonSigners = null;
        String descriptor = null;
        long remaining = policy.maxExpandedBytes();
        final var entries = jar.entries();
        while (entries.hasMoreElements()) {
            final JarEntry entry = entries.nextElement();
            validateName(entry.getName(), names);
            if (entry.isDirectory()) { continue; }
            final EntryContents contents = readEntry(jar, entry, remaining);
            remaining -= contents.bytes();
            if (signatureMetadata(entry.getName())) { continue; }
            final Set<String> trusted = trustedSigners(entry.getCodeSigners());
            if (trusted.isEmpty()) { throw new SecurityException("Unsigned or untrusted payload entry"); }
            if (commonSigners == null) { commonSigners = new HashSet<>(trusted); }
            else { commonSigners.retainAll(trusted); }
            if (commonSigners.isEmpty()) { throw new SecurityException("No common trusted signer for all entries"); }
            if ("plugin.yaml".equals(entry.getName())) { descriptor = contents.descriptor(); }
        }
        if (descriptor == null || commonSigners == null) { throw new SecurityException("Signed plugin.yaml is required"); }
        return new VerifiedJar(descriptor, commonSigners.stream().sorted().toList());
    }

    private record EntryContents(String descriptor, long bytes) { }

    private static EntryContents readEntry(final JarFile jar, final JarEntry entry, final long remaining) throws IOException {
        if ("META-INF/INDEX.LIST".equalsIgnoreCase(entry.getName())) {
            throw new SecurityException("Signed installations cannot extend classpaths through a JAR index");
        }
        try (var input = jar.getInputStream(entry)) {
            if (!"plugin.yaml".equals(entry.getName())) {
                return new EntryContents(null, ArtifactStreams.consume(input, java.io.OutputStream.nullOutputStream(), remaining));
            }
            final byte[] descriptor = ArtifactStreams.read(input, Math.min(1024 * 1024, remaining));
            return new EntryContents(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(descriptor)).toString(), descriptor.length);
        }
    }

    private static void validateName(final String name, final Set<String> names) {
        if (!names.add(name) || name.startsWith("/") || name.contains("\\")
            || java.util.Arrays.stream(name.split("/", -1)).anyMatch(part -> part.equals("..") || part.equals("."))) {
            throw new SecurityException("Duplicate or ambiguous JAR entry");
        }
    }

    private Set<String> trustedSigners(final CodeSigner[] signers) throws GeneralSecurityException {
        if (signers == null) { return Set.of(); }
        final Set<String> trusted = new HashSet<>();
        for (final CodeSigner signer : signers) {
            final var certificates = signer.getSignerCertPath().getCertificates().stream()
                    .map(certificate -> (X509Certificate) certificate).toList();
            try {
                validateChain(certificates);
                trusted.add(ArtifactDigests.sha256(certificates.get(0).getEncoded()));
            } catch (final GeneralSecurityException invalid) {
                // A second, trusted signer can still authenticate every payload entry.
            }
        }
        return trusted;
    }

    private void validateChain(final List<X509Certificate> certificates) throws GeneralSecurityException {
        if (certificates.isEmpty()) { throw new java.security.cert.CertificateException("Missing signer certificate"); }
        for (final X509Certificate certificate : certificates) { certificate.checkValidity(); }
        final X509Certificate leaf = certificates.get(0);
        final boolean[] usage = leaf.getKeyUsage();
        if (usage != null && !usage[0]) { throw new java.security.cert.CertificateException("Signer cannot create digital signatures"); }
        final List<String> eku = leaf.getExtendedKeyUsage();
        if (eku != null && !eku.contains("1.3.6.1.5.5.7.3.3")) { throw new java.security.cert.CertificateException("Signer is not for code signing"); }
        GeneralSecurityException failure = null;
        for (final X509Certificate anchor : policy.trustedCertificates()) {
            try { validateAgainst(certificates, anchor); return; }
            catch (final GeneralSecurityException invalid) { failure = invalid; }
        }
        throw new GeneralSecurityException("Signer does not chain to a configured trust anchor", failure);
    }

    private static void validateAgainst(final List<X509Certificate> certificates,
                                         final X509Certificate anchor) throws GeneralSecurityException {
        anchor.checkValidity();
        final List<X509Certificate> chain = new ArrayList<>(certificates);
        final int anchorIndex = chain.indexOf(anchor);
        if (anchorIndex >= 0) { chain.subList(anchorIndex, chain.size()).clear(); }
        final CertPath path = CertificateFactory.getInstance("X.509").generateCertPath(chain);
        final PKIXParameters parameters = new PKIXParameters(Set.of(new TrustAnchor(anchor, null)));
        parameters.setRevocationEnabled(false); // Offline policy; no implicit network or revocation promise.
        CertPathValidator.getInstance("PKIX").validate(path, parameters);
    }

    private static boolean signatureMetadata(final String name) {
        final String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/") || upper.substring(9).contains("/")) { return false; }
        return upper.equals("META-INF/MANIFEST.MF") || upper.endsWith(".SF") || upper.endsWith(".RSA")
               || upper.endsWith(".DSA") || upper.endsWith(".EC") || upper.startsWith("META-INF/SIG-");
    }
}

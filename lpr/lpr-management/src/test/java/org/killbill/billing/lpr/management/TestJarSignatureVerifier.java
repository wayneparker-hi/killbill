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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class TestJarSignatureVerifier {
    private SignedJarFixtures fixture;
    private Path trusted;
    private Path signed;
    private Path expired;
    private Path wrongPurpose;
    private Path root;

    @BeforeClass(groups = "fast")
    public void setup() throws Exception {
        fixture = new SignedJarFixtures();
        trusted = fixture.certificate("trusted", "-1d", "365", "codeSigning");
        fixture.certificate("untrusted", "-1d", "365", "codeSigning");
        expired = fixture.certificate("expired", "-10d", "1", "codeSigning");
        wrongPurpose = fixture.certificate("wrongpurpose", "-1d", "365", "serverAuth");
        root = fixture.chain();
        signed = fixture.jar("trusted");
    }

    @AfterClass(alwaysRun = true, groups = "fast")
    public void cleanup() throws Exception { if (fixture != null) { fixture.close(); } }

    @Test(groups = "fast")
    public void testTrustedSelfSignedJarAndSignerFingerprint() throws Exception {
        final var result = verifier(trusted).verify(Files.readAllBytes(signed));
        assertEquals(result.descriptor(), SignedJarFixtures.DESCRIPTOR);
        assertEquals(result.signers(), List.of(ArtifactDigests.sha256(SignedJarFixtures.readCertificate(trusted).getEncoded())));
    }

    @Test(groups = "fast")
    public void testCertificateChainToConfiguredRoot() throws Exception {
        assertFalse(verifier(root).verify(Files.readAllBytes(fixture.jar("leaf"))).signers().isEmpty());
    }

    @Test(groups = "fast")
    public void testUnsignedAndUntrustedJarsAreRejected() throws Exception {
        final byte[] unsigned = Files.readAllBytes(fixture.jar(null));
        final byte[] untrusted = Files.readAllBytes(fixture.jar("untrusted"));
        assertThrows(IOException.class, () -> verifier(trusted).verify(unsigned));
        assertThrows(IOException.class, () -> verifier(trusted).verify(untrusted));
    }

    @Test(groups = "fast")
    public void testTamperedPayloadAndDescriptorAreRejected() throws Exception {
        for (final String name : List.of("example/Demo.class", "plugin.yaml")) {
            final byte[] tampered = Files.readAllBytes(fixture.rewrite(signed, Map.of(name, "tampered".getBytes())));
            assertThrows(IOException.class, () -> verifier(trusted).verify(tampered));
        }
    }

    @Test(groups = "fast")
    public void testAddedUnsignedEntriesIncludingMetaInfServicesAreRejected() throws Exception {
        for (final String name : List.of("extra.class", "META-INF/services/example.Service")) {
            final byte[] partial = Files.readAllBytes(fixture.rewrite(signed, Map.of(name, new byte[]{4})));
            assertThrows(IOException.class, () -> verifier(trusted).verify(partial));
        }
    }

    @Test(groups = "fast")
    public void testExpiredAndWrongPurposeCertificatesAreRejectedEvenWhenTrusted() throws Exception {
        final byte[] expiredJar = Files.readAllBytes(fixture.jar("expired"));
        final byte[] wrongPurposeJar = Files.readAllBytes(fixture.jar("wrongpurpose"));
        assertThrows(IOException.class, () -> verifier(expired).verify(expiredJar));
        assertThrows(IOException.class, () -> verifier(wrongPurpose).verify(wrongPurposeJar));
    }

    @Test(groups = "fast")
    public void testDuplicateAndAmbiguousEntryNamesAreRejected() throws Exception {
        for (final String name : List.of("../outside.class", "example/./Other.class", "/absolute.class", "example\\Other.class")) {
            final byte[] ambiguous = Files.readAllBytes(fixture.rewrite(signed, Map.of(name, new byte[]{5})));
            assertThrows(IOException.class, () -> verifier(trusted).verify(ambiguous));
        }
        final Path added = fixture.rewrite(signed, Map.of("PLUGIN.YAML", SignedJarFixtures.DESCRIPTOR.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        final byte[] duplicate = Files.readAllBytes(added);
        final byte[] from = "PLUGIN.YAML".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final byte[] to = "plugin.yaml".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(from.length, to.length);
        for (int i = 0; i <= duplicate.length - from.length; i++) {
            if (java.util.Arrays.equals(java.util.Arrays.copyOfRange(duplicate, i, i + from.length), from)) {
                System.arraycopy(to, 0, duplicate, i, to.length);
            }
        }
        assertThrows(IOException.class, () -> verifier(trusted).verify(duplicate));
    }

    @Test(groups = "fast")
    public void testSignedExternalClasspathsAndJarIndexesAreRejected() throws Exception {
        final Path classpath = fixture.rewrite(fixture.jar(null), Map.of("META-INF/MANIFEST.MF",
                "Manifest-Version: 1.0\r\nClass-Path: ../unverified.jar\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        fixture.sign(classpath, "trusted");
        final Path index = fixture.rewrite(fixture.jar(null), Map.of("META-INF/INDEX.LIST", "JarIndex-Version: 1.0\n".getBytes()));
        fixture.sign(index, "trusted");
        assertThrows(IOException.class, () -> verifier(trusted).verify(Files.readAllBytes(classpath)));
        assertThrows(IOException.class, () -> verifier(trusted).verify(Files.readAllBytes(index)));
    }

    @Test(groups = "fast")
    public void testSigningTrustConfigurationFailsClosed() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new ArtifactVerificationPolicy(false, true, List.of()));
        assertThrows(IllegalArgumentException.class, () -> ArtifactVerificationPolicy.fromPem(false, true, null));
        final Path empty = fixture.root.resolve("empty.pem"); Files.writeString(empty, "not a certificate");
        assertThrows(IOException.class, () -> ArtifactVerificationPolicy.fromPem(false, true, empty));
    }

    @Test(groups = "fast")
    public void testSignedInstallerRejectsDescriptorOverrideAndPublishesIdenticalBytes() throws Exception {
        final Path storage = fixture.root.resolve("repository");
        final var repository = new org.killbill.billing.lpr.repository.filesystem.FileSystemPluginRepository(storage,
                new org.killbill.billing.lpr.descriptor.yaml.YamlDescriptorParser());
        final var events = new java.util.ArrayList<org.killbill.billing.lpr.spi.PluginInstallAudit>();
        final var installer = new PluginInstaller(repository, ArtifactVerificationPolicy.fromPem(true, true, trusted), events::add);
        final byte[] bytes = Files.readAllBytes(signed);
        final var source = ArtifactSource.of(signed).withSha256(ArtifactDigests.sha256(bytes));
        assertThrows(java.io.UncheckedIOException.class, () -> installer.install("signed-demo", "1.0.0", source, "different"));
        assertFalse(Files.exists(storage.resolve("signed-demo/1.0.0")));
        final Path installed = installer.install("signed-demo", "1.0.0", source, SignedJarFixtures.DESCRIPTOR);
        assertEquals(Files.readAllBytes(installed.resolve("plugin.jar")), bytes);
        final var published = events.get(events.size() - 1);
        assertTrue(published.signatureRequired());
        assertEquals(published.signers().size(), 1);
        assertEquals(published.descriptorSha256(), ArtifactDigests.sha256(SignedJarFixtures.DESCRIPTOR.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test(groups = "fast")
    public void testExpandedJarAndDescriptorLimitsAreEnforced() throws Exception {
        final var certificates = List.of(SignedJarFixtures.readCertificate(trusted));
        final byte[] bytes = Files.readAllBytes(signed);
        final var limited = new JarSignatureVerifier(new ArtifactVerificationPolicy(false, true, certificates, 65536, 16));
        assertThrows(IOException.class, () -> limited.verify(bytes));
        final Path oversized = fixture.rewrite(fixture.jar(null), Map.of("plugin.yaml", new byte[1024 * 1024 + 1]));
        fixture.sign(oversized, "trusted");
        assertThrows(IOException.class, () -> verifier(trusted).verify(Files.readAllBytes(oversized)));
    }

    @Test(groups = "fast")
    public void testInvalidByteLimitsAreRejected() {
        for (final long size : new long[]{0, -1, (long) Integer.MAX_VALUE + 1}) {
            assertThrows(IllegalArgumentException.class, () -> new ArtifactVerificationPolicy(false, false, List.of(), size, 1024));
        }
        assertThrows(IllegalArgumentException.class, () -> new ArtifactVerificationPolicy(false, false, List.of(), 1024, 0));
    }

    private JarSignatureVerifier verifier(final Path pem) throws Exception {
        return new JarSignatureVerifier(ArtifactVerificationPolicy.fromPem(false, true, pem));
    }
}

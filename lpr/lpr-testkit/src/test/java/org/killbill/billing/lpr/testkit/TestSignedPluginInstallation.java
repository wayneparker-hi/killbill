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

package org.killbill.billing.lpr.testkit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.killbill.billing.lpr.management.ArtifactSource;
import org.killbill.billing.lpr.management.ArtifactVerificationPolicy;
import org.killbill.billing.lpr.management.PluginInstaller;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

/** Signed bytes pass through the real installer, shared cache, ClassLoader and registry. */
public class TestSignedPluginInstallation {
    public interface Gateway { String value(); }

    @Test(groups = "fast")
    public void testSignedInstallUpgradeRollbackAndUnsignedRejectionAcrossNodes() throws Exception {
        final Path root = Files.createTempDirectory("lpr-signed-cluster-");
        try (PluginTestRuntime producer = PluginTestRuntime.create();
             PluginTestRuntime a = PluginTestRuntime.shared(root.resolve("shared"), Map.of());
             PluginTestRuntime b = PluginTestRuntime.shared(root.resolve("shared"), Map.of())) {
            final Path pem = createSigner(root);
            final var policy = ArtifactVerificationPolicy.fromPem(true, true, pem);
            final var events = new java.util.ArrayList<org.killbill.billing.lpr.spi.PluginInstallAudit>();
            final var installer = new PluginInstaller(a.repository(), policy, events::add);
            final Path one = artifact(producer, "1.0.0");
            sign(root, one);
            installer.install("signed-demo", "1.0.0", source(one), null);
            a.discover(); a.manager().start("signed-demo"); b.manager().reconcile();
            assertEquals(value(a), "1.0.0"); assertEquals(value(b), "1.0.0");
            final Path two = artifact(producer, "2.0.0");
            assertThrows(java.io.UncheckedIOException.class, () -> installer.install("signed-demo", "2.0.0", source(two), null));
            assertEquals(value(a), "1.0.0"); assertFalse(a.repository().installedVersions("signed-demo").contains("2.0.0"));
            sign(root, two);
            installer.install("signed-demo", "2.0.0", source(two), null);
            a.manager().activateVersion("signed-demo", "2.0.0"); b.manager().reconcile();
            assertEquals(value(a), "2.0.0"); assertEquals(value(b), "2.0.0");
            b.manager().rollback("signed-demo"); a.manager().reconcile();
            assertEquals(value(a), "1.0.0"); assertEquals(value(b), "1.0.0");
            assertEquals(events.stream().filter(e -> e.stage() == org.killbill.billing.lpr.spi.PluginInstallAudit.Stage.PUBLISHED).count(), 2L);
            assertTrue(events.stream().filter(e -> e.stage() == org.killbill.billing.lpr.spi.PluginInstallAudit.Stage.PUBLISHED)
                    .allMatch(e -> e.signers().size() == 1 && e.signatureRequired()));
        } finally { delete(root); }
    }

    private static String value(final PluginTestRuntime runtime) { return runtime.service(Gateway.class, "signed-demo").value(); }

    private static ArtifactSource source(final Path jar) throws Exception {
        final String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        return ArtifactSource.of(jar).withSha256(digest);
    }

    private static Path artifact(final PluginTestRuntime producer, final String version) throws Exception {
        final String code = """
                package example;
                import org.killbill.billing.lpr.api.*;
                public class SignedDemo implements Plugin {
                    public void start(PluginContext context) { context.services().register(%s.class, () -> "%s"); }
                    public void stop() { }
                }
                """.formatted(Gateway.class.getCanonicalName(), version);
        final Path directory = producer.install(PluginFixture.plugin("signed-demo", version).entrypoint("example.SignedDemo", code));
        final Path jar = directory.resolve("plugin.jar");
        command(directory, "jar", "--update", "--file", jar.toString(), "-C", directory.toString(), "plugin.yaml");
        return jar;
    }

    private static Path createSigner(final Path root) throws Exception {
        command(root, "keytool", "-genkeypair", "-alias", "signer", "-keystore", root.resolve("signer.p12").toString(),
                "-storepass", "changeit", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=LPR Test", "-validity", "365",
                "-ext", "KU=digitalSignature", "-ext", "EKU=codeSigning");
        final Path pem = root.resolve("signer.pem");
        command(root, "keytool", "-exportcert", "-rfc", "-alias", "signer", "-keystore", root.resolve("signer.p12").toString(),
                "-storepass", "changeit", "-file", pem.toString());
        return pem;
    }

    private static void sign(final Path root, final Path jar) throws Exception {
        command(root, "jarsigner", "-keystore", root.resolve("signer.p12").toString(), "-storepass", "changeit",
                "-digestalg", "SHA-256", "-sigalg", "SHA256withRSA", jar.toString(), "signer");
    }

    private static void command(final Path root, final String executable, final String... args) throws Exception {
        final var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString()); command.addAll(List.of(args));
        final Path output = Files.createTempFile(root, "command-", ".log");
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(process.exitValue(), 0, Files.readString(output));
        } finally { process.destroyForcibly(); }
    }

    private static void delete(final Path root) throws Exception {
        try (var files = Files.walk(root)) {
            for (final Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.deleteIfExists(file); }
        }
    }
}

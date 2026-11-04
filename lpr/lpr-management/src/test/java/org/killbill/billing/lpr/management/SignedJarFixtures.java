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
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

final class SignedJarFixtures implements AutoCloseable {
    static final String DESCRIPTOR = "id: signed-demo\nversion: 1.0.0\nentrypoint: example.Demo\n";
    final Path root = Files.createTempDirectory("lpr-signature-test-");
    private int next;

    SignedJarFixtures() throws Exception { }

    Path certificate(final String alias, final String start, final String validity, final String eku) throws Exception {
        command("keytool", "-genkeypair", "-alias", alias, "-keystore", root.resolve(alias + ".p12").toString(),
                "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=" + alias, "-startdate", start, "-validity", validity, "-ext", "KU=digitalSignature", "-ext", "EKU=" + eku);
        return export(alias);
    }

    Path chain() throws Exception {
        command("keytool", "-genkeypair", "-alias", "root", "-keystore", root.resolve("root.p12").toString(),
                "-storepass", "changeit", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Root", "-validity", "365", "-ext", "BC=ca:true");
        final Path rootPem = export("root");
        certificate("leaf", "-1d", "365", "codeSigning");
        command("keytool", "-certreq", "-alias", "leaf", "-keystore", root.resolve("leaf.p12").toString(),
                "-storepass", "changeit", "-file", root.resolve("leaf.csr").toString());
        command("keytool", "-gencert", "-alias", "root", "-keystore", root.resolve("root.p12").toString(), "-storepass", "changeit",
                "-infile", root.resolve("leaf.csr").toString(), "-outfile", root.resolve("leaf-signed.pem").toString(),
                "-rfc", "-validity", "180", "-ext", "KU=digitalSignature", "-ext", "EKU=codeSigning");
        command("keytool", "-importcert", "-noprompt", "-alias", "root", "-keystore", root.resolve("leaf.p12").toString(),
                "-storepass", "changeit", "-file", rootPem.toString());
        command("keytool", "-importcert", "-noprompt", "-alias", "leaf", "-keystore", root.resolve("leaf.p12").toString(),
                "-storepass", "changeit", "-file", root.resolve("leaf-signed.pem").toString());
        return rootPem;
    }

    private Path export(final String alias) throws Exception {
        final Path pem = root.resolve(alias + ".pem");
        command("keytool", "-exportcert", "-rfc", "-alias", alias, "-keystore", root.resolve(alias + ".p12").toString(),
                "-storepass", "changeit", "-file", pem.toString());
        return pem;
    }

    Path jar(final String alias) throws Exception {
        final Path jar = root.resolve("plugin-" + next++ + ".jar");
        final Manifest manifest = new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            entry(out, "plugin.yaml", DESCRIPTOR.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            entry(out, "example/Demo.class", new byte[]{1, 2, 3});
        }
        if (alias != null) {
            sign(jar, alias);
        }
        return jar;
    }

    void sign(final Path jar, final String alias) throws Exception {
        command("jarsigner", "-keystore", root.resolve(alias + ".p12").toString(), "-storepass", "changeit",
                "-digestalg", "SHA-256", "-sigalg", "SHA256withRSA", jar.toString(), alias);
    }

    Path rewrite(final Path source, final Map<String, byte[]> replacements) throws Exception {
        final Path target = root.resolve("rewritten-" + next++ + ".jar");
        try (var input = new java.util.jar.JarFile(source.toFile(), false);
             var output = new JarOutputStream(Files.newOutputStream(target))) {
            final var entries = input.entries();
            while (entries.hasMoreElements()) {
                final var entry = entries.nextElement();
                if (!replacements.containsKey(entry.getName())) {
                    try (var stream = input.getInputStream(entry)) { entry(output, entry.getName(), stream.readAllBytes()); }
                }
            }
            for (final var replacement : replacements.entrySet()) { entry(output, replacement.getKey(), replacement.getValue()); }
        }
        return target;
    }

    static X509Certificate readCertificate(final Path pem) throws Exception {
        try (var input = Files.newInputStream(pem)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    private static void entry(final JarOutputStream out, final String name, final byte[] content) throws Exception {
        out.putNextEntry(new JarEntry(name)); out.write(content); out.closeEntry();
    }

    private void command(final String executable, final String... args) throws Exception {
        final List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString()); command.addAll(List.of(args));
        final Path output = root.resolve("command-" + next++ + ".log");
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException(executable + " failed: " + Files.readString(output));
            }
        } finally { process.destroyForcibly(); }
    }

    @Override public void close() throws Exception {
        try (var paths = Files.walk(root)) {
            for (final Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
        }
    }
}

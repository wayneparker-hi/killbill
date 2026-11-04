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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Shared version identities survive uninstall, so a peer can never mistake different bytes for the same version. */
final class PublishedArtifactStore {
    private PublishedArtifactStore() { }

    static void bind(final Path pluginDir, final String version, final byte[] jar, final String descriptor) throws IOException {
        final Path identities = pluginDir.resolve(".artifacts");
        if (Files.isSymbolicLink(identities)) { throw new IOException("Artifact identities must not be a symbolic link"); }
        Files.createDirectories(identities);
        final Path identity = identities.resolve(version);
        final String digest = digest(jar, descriptor);
        if (Files.exists(identity)) {
            if (Files.isSymbolicLink(identity) || !digest.equals(Files.readString(identity))) {
                throw new IllegalStateException("Shared version identity cannot change, including after uninstall: " + version);
            }
            return;
        }
        final Path staging = Files.createTempFile(identities, ".incoming-", ".tmp");
        try {
            Files.writeString(staging, digest);
            Files.move(staging, identity, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(staging); }
    }

    private static String digest(final byte[] jar, final String descriptor) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] description = descriptor.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(8).putInt(jar.length).putInt(description.length).array());
            digest.update(jar);
            digest.update(description);
            return HexFormat.of().formatHex(digest.digest());
        } catch (final NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}

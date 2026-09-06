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

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Where a plugin jar is coming from, and how to know it arrived intact.
 * <p>
 * KPM understood one thing: a coordinate in Kill Bill's own plugin directory, resolved through
 * Nexus metadata. That is a fine default and a poor only option -- it made installing a plugin you
 * built yourself the awkward case. Here the primitive is a URI, and Maven coordinates are a
 * convenience that produces one.
 *
 * @param uri      where to fetch the jar from; {@code file:} and {@code http(s):} both work
 * @param sha1     expected SHA-1 of the jar, if known
 * @param fileName the name to store the jar under, if it should not be derived from the URI
 */
public record ArtifactSource(URI uri, Optional<String> sha1, Optional<String> fileName) {

    public ArtifactSource {
        Objects.requireNonNull(uri, "uri");
        sha1 = Objects.requireNonNullElse(sha1, Optional.empty());
        fileName = Objects.requireNonNullElse(fileName, Optional.empty());
    }

    /**
     * @param uri where to fetch the jar from
     * @return a source with no integrity check
     */
    public static ArtifactSource of(final URI uri) {
        return new ArtifactSource(uri, Optional.empty(), Optional.empty());
    }

    /**
     * @param path a jar already on this machine
     * @return a source reading straight from the filesystem
     */
    public static ArtifactSource of(final Path path) {
        return of(Objects.requireNonNull(path, "path").toUri());
    }

    /**
     * Builds the URI a Maven repository would serve this artifact from.
     * <p>
     * Deliberately only the release layout: {@code group/path/artifact/version/artifact-version.jar}.
     * Snapshot resolution needs {@code maven-metadata.xml} parsing and a timestamped filename, which
     * is a real dependency-resolver's job. A deployment that wants to install a snapshot can point
     * at the resolved URI directly.
     *
     * @param repositoryBase the repository root, e.g. {@code https://repo1.maven.org/maven2}
     * @param groupId        Maven group
     * @param artifactId     Maven artifact
     * @param version        an exact release version
     * @return the artifact source
     * @throws IllegalArgumentException if the version is a snapshot
     */
    public static ArtifactSource ofMavenCoordinates(final URI repositoryBase,
                                                    final String groupId,
                                                    final String artifactId,
                                                    final String version) {
        Objects.requireNonNull(repositoryBase, "repositoryBase");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(version, "version");
        if (version.endsWith("-SNAPSHOT")) {
            throw new IllegalArgumentException(
                    "Cannot resolve the snapshot " + version + " from a repository layout alone. "
                    + "Point at the resolved jar URI instead.");
        }
        final String jar = artifactId + '-' + version + ".jar";
        final String path = groupId.replace('.', '/') + '/' + artifactId + '/' + version + '/' + jar;
        final String base = repositoryBase.toString();
        return new ArtifactSource(URI.create(base.endsWith("/") ? base + path : base + '/' + path),
                                  Optional.empty(),
                                  Optional.of(jar));
    }

    /**
     * @param sha1Hex the expected SHA-1, hex-encoded
     * @return a copy of this source that will be verified after download
     */
    public ArtifactSource withSha1(final String sha1Hex) {
        return new ArtifactSource(uri, Optional.of(sha1Hex), fileName);
    }
}

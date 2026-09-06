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

package org.killbill.billing.lpr.spi;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A plugin version, compared numerically.
 * <p>
 * This exists to fix a specific defect inherited from the OSGi layer: {@code DefaultPluginConfig}
 * ordered plugin versions with {@code String.compareTo}, so {@code "10.0"} sorted <i>below</i>
 * {@code "9.0"}. A deployment that reached double-digit minor versions would silently start the
 * wrong one, and nothing would look broken until behaviour diverged.
 * <p>
 * Semantic Versioning 2.0.0, with two accommodations for how artifacts are actually versioned
 * here: the patch component may be omitted ({@code 1.2} means {@code 1.2.0}), and build metadata
 * after {@code +} is parsed but ignored in comparisons, as the spec requires.
 * <p>
 * Pre-release ordering follows the spec: {@code 1.0.0-SNAPSHOT} precedes {@code 1.0.0}, so a
 * release always wins over the snapshot it came from.
 */
public record SemanticVersion(int major, int minor, int patch, String preRelease, String build)
        implements Comparable<SemanticVersion> {

    private static final Pattern PATTERN = Pattern.compile(
            "^(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?(?:\\+([0-9A-Za-z.-]+))?$");

    public SemanticVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Version components must not be negative");
        }
    }

    /**
     * @param raw a version string such as {@code 1.2.0}, {@code 1.2}, {@code 1.2.0-SNAPSHOT}
     * @return the parsed version
     * @throws IllegalArgumentException if it is not a version this runtime can order
     */
    public static SemanticVersion parse(final String raw) {
        Objects.requireNonNull(raw, "raw");
        final Matcher matcher = PATTERN.matcher(raw.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not a semantic version: '" + raw + "'. Expected MAJOR.MINOR[.PATCH]"
                                               + "[-PRERELEASE][+BUILD], for example 1.2.0 or 1.2.0-SNAPSHOT");
        }
        return new SemanticVersion(Integer.parseInt(matcher.group(1)),
                                   Integer.parseInt(matcher.group(2)),
                                   matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3)),
                                   matcher.group(4),
                                   matcher.group(5));
    }

    /**
     * @param raw a version string
     * @return whether {@link #parse} would succeed
     */
    public static boolean isValid(final String raw) {
        return raw != null && PATTERN.matcher(raw.trim()).matches();
    }

    /**
     * @return true if this is a pre-release such as {@code 1.0.0-SNAPSHOT}
     */
    public boolean isPreRelease() {
        return preRelease != null;
    }

    @Override
    public int compareTo(final SemanticVersion other) {
        int result = Integer.compare(major, other.major);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(minor, other.minor);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(patch, other.patch);
        if (result != 0) {
            return result;
        }
        return comparePreRelease(preRelease, other.preRelease);
    }

    /**
     * Per the spec: a version with a pre-release ranks below the same version without one, and
     * pre-release identifiers are compared field by field, numerically where both are numeric.
     */
    private static int comparePreRelease(final String left, final String right) {
        if (Objects.equals(left, right)) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }

        final String[] leftParts = left.split("\\.");
        final String[] rightParts = right.split("\\.");
        final int shared = Math.min(leftParts.length, rightParts.length);

        for (int i = 0; i < shared; i++) {
            final int result = comparePreReleaseIdentifier(leftParts[i], rightParts[i]);
            if (result != 0) {
                return result;
            }
        }
        // A longer identifier list ranks higher when everything shared is equal: 1.0.0-alpha.1
        // outranks 1.0.0-alpha.
        return Integer.compare(leftParts.length, rightParts.length);
    }

    private static int comparePreReleaseIdentifier(final String left, final String right) {
        final boolean leftNumeric = isNumeric(left);
        final boolean rightNumeric = isNumeric(right);

        if (leftNumeric && rightNumeric) {
            return Integer.compare(Integer.parseInt(left), Integer.parseInt(right));
        }
        if (leftNumeric) {
            // Numeric identifiers always rank below alphanumeric ones.
            return -1;
        }
        if (rightNumeric) {
            return 1;
        }
        return left.compareTo(right);
    }

    private static boolean isNumeric(final String identifier) {
        if (identifier.isEmpty()) {
            return false;
        }
        for (int i = 0; i < identifier.length(); i++) {
            if (!Character.isDigit(identifier.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        final StringBuilder text = new StringBuilder().append(major).append('.').append(minor).append('.').append(patch);
        if (preRelease != null) {
            text.append('-').append(preRelease);
        }
        if (build != null) {
            text.append('+').append(build);
        }
        return text.toString();
    }
}

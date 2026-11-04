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

package org.killbill.billing.lpr.process;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Explicit caller configuration for the opt-in PoC, never read from system properties. */
public record ProcessPluginSettings(Path javaExecutable, Path socketRoot, Duration startupTimeout,
                                    Duration callTimeout, Duration stopTimeout, int maxFrameBytes, int maxHeapMiB) {
    public ProcessPluginSettings {
        javaExecutable = Objects.requireNonNull(javaExecutable, "javaExecutable").toAbsolutePath().normalize();
        socketRoot = Objects.requireNonNull(socketRoot, "socketRoot").toAbsolutePath().normalize();
        if (!Files.isRegularFile(javaExecutable) || !Files.isExecutable(javaExecutable) || !Files.isDirectory(socketRoot)) {
            throw new IllegalArgumentException("An executable Java binary and existing socket root are required");
        }
        for (final Duration timeout : new Duration[] {startupTimeout, callTimeout, stopTimeout}) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException("Timeouts must be positive and at most one day");
            }
        }
        if (maxFrameBytes < 256 || maxFrameBytes > 1_048_576 || maxHeapMiB < 16) {
            throw new IllegalArgumentException("Frame budget must be 256..1048576 bytes, heap at least 16 MiB");
        }
    }
}

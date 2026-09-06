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

package org.killbill.billing.lpr.api;

import java.util.Map;
import java.util.Optional;

/**
 * A plugin's configuration, already merged by the runtime from system, plugin and tenant layers
 * (later layers win).
 * <p>
 * Plugins should read configuration through this interface rather than {@code System.getProperty}
 * or {@code System.getenv}: values reached behind the runtime's back cannot be overridden per
 * tenant, cannot be reloaded, and do not show up when inspecting a plugin's effective config.
 */
public interface PluginConfig {

    /**
     * @param key configuration key
     * @return the value
     * @throws IllegalArgumentException if the key is absent -- use for values declared required in
     *                                  the descriptor, so misconfiguration fails at startup
     */
    String get(String key);

    /**
     * @param key configuration key
     * @return the value, or empty if absent
     */
    Optional<String> find(String key);

    /**
     * @param key  configuration key
     * @param type target type; String, primitive wrappers and enums are supported
     * @param <T>  target type
     * @return the converted value, or empty if the key is absent
     * @throws IllegalArgumentException if the value cannot be converted
     */
    <T> Optional<T> find(String key, Class<T> type);

    /**
     * @return an immutable snapshot of the effective configuration
     */
    Map<String, String> all();
}

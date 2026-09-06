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

package org.killbill.billing.platform.plugin.api;

/**
 * Identifies a service registration: who published it, and under what name the core finds it.
 * <p>
 * Simpler than the OSGi descriptor it replaces, which carried a bundle symbolic name, a plugin
 * name and a registration name. Nothing in the core ever read the first two -- every registry uses
 * only {@link #getRegistrationName()} -- so they are gone rather than carried forward as fields
 * that must be populated but never consulted.
 */
public interface PluginServiceDescriptor {

    /**
     * @return the publishing plugin's identity
     */
    String getPluginId();

    /**
     * @return the publishing plugin's version
     */
    String getPluginVersion();

    /**
     * The name configuration refers to. Usually the plugin id, but a plugin may publish several
     * services of one type under different names.
     *
     * @return the lookup key
     */
    String getRegistrationName();
}

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

import java.nio.file.Path;

/**
 * Everything a plugin is allowed to reach. The runtime hands one instance to
 * {@link Plugin#start} and it stays valid until the plugin stops.
 * <p>
 * What is deliberately <i>not</i> here: the plugin manager, the plugin registry, the lifecycle
 * manager, and the plugin's own ClassLoader. A plugin extends the platform; it must not be able to
 * steer the runtime that hosts it.
 */
public interface PluginContext {

    /**
     * @return this plugin's stable identity, matching {@code id} in its descriptor
     */
    String pluginId();

    /**
     * @return this plugin's semantic version, matching {@code version} in its descriptor
     */
    String version();

    /**
     * @return configuration resolved for this plugin; never null
     */
    PluginConfig config();

    /**
     * The directory this version of the plugin was installed into.
     * <p>
     * Plugins that ship data files, templates or certificates alongside their jar read them from
     * here. This replaces the OSGi {@code PluginConfigServiceApi}, which answered the same question
     * but had to be asked with a {@code bundleId} -- a framework handle the plugin had no natural
     * way to obtain. A plugin already knows which plugin it is, so the answer belongs on its own
     * context rather than behind a lookup service.
     *
     * @return the version directory, e.g. {@code .../plugins/hello-world/1.2.0}; never null
     */
    Path pluginRoot();

    /**
     * @return the jar this plugin was loaded from; never null
     */
    Path jarPath();

    /**
     * Services this plugin publishes to the core, and services published by the core.
     * <p>
     * Registrations made here are unwound automatically when the plugin stops.
     *
     * @return the registry; never null
     */
    ServiceRegistry services();

    /**
     * In-process publish/subscribe. Delivery is best-effort within this JVM -- no durability, no
     * delivery guarantee, no ordering across publishers. Anything needing those belongs on a real
     * message bus, not here.
     *
     * @return the event bus; never null
     */
    EventBus eventBus();

    /**
     * Where to hand anything that must be released when the plugin stops: executors, subscriptions,
     * connections, watchers.
     * <p>
     * This is not hygiene, it is correctness. An untracked thread keeps the plugin's ClassLoader
     * reachable, so classes are never reclaimed and repeated reloads leak metaspace.
     *
     * @return the resource registry; never null
     */
    ResourceRegistry resources();

    /**
     * Looks up a service the platform publishes to plugins (a core user API, the clock, a
     * DataSource, and so on).
     *
     * @param type the service interface
     * @param <T>  the service type
     * @return the service
     * @throws IllegalArgumentException if the platform publishes no such service
     */
    <T> T getPlatformService(Class<T> type);
}

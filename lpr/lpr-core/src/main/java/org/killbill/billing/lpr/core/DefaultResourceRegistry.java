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

package org.killbill.billing.lpr.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

import org.killbill.billing.lpr.api.ResourceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks one plugin's resources and releases them when it stops.
 * <p>
 * Three properties matter here, and each one exists because getting it wrong makes plugin
 * unloading silently ineffective:
 * <ul>
 *   <li><b>LIFO release.</b> Resources are torn down newest first, so a subscription registered
 *       against an executor is cancelled before the executor it runs on.</li>
 *   <li><b>References are dropped, not just closed.</b> The registry clears its own list as it
 *       goes. A terminated thread still points at the task it ran, so an executor that was shut
 *       down but is still reachable keeps the plugin's ClassLoader alive; holding the resource
 *       "just in case" would defeat the entire point.</li>
 *   <li><b>Every resource is attempted.</b> One resource throwing must not strand the rest, and
 *       {@link #closeAll()} never propagates -- it runs on the shutdown path, where the only worse
 *       outcome than a failed close is a half-finished shutdown.</li>
 * </ul>
 * Safe for concurrent use: plugins do register resources from their own threads.
 */
public class DefaultResourceRegistry implements ResourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultResourceRegistry.class);

    private final String pluginId;
    private final Deque<Runnable> releaseActions = new ArrayDeque<>();
    private boolean closed;

    /**
     * @param pluginId the owning plugin, used in diagnostics
     */
    public DefaultResourceRegistry(final String pluginId) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
    }

    @Override
    public <T extends AutoCloseable> T manage(final T resource) {
        Objects.requireNonNull(resource, "resource");
        register(() -> closeQuietly(resource), resource.getClass().getName());
        return resource;
    }

    @Override
    public void onClose(final Runnable action) {
        Objects.requireNonNull(action, "action");
        register(action, "custom release action");
    }

    /**
     * Registering after {@link #closeAll()} means the plugin allocated something during or after
     * its own shutdown. Releasing it immediately is the only way to keep the promise that nothing
     * outlives the plugin.
     */
    private void register(final Runnable releaseAction, final String description) {
        synchronized (this) {
            if (!closed) {
                releaseActions.push(releaseAction);
                return;
            }
        }
        log.warn("Plugin {} registered a resource ({}) after shutdown; releasing it immediately",
                 pluginId, description);
        runQuietly(releaseAction, description);
    }

    @Override
    public void closeAll() {
        while (true) {
            final Runnable next;
            synchronized (this) {
                closed = true;
                next = releaseActions.poll();
                if (next == null) {
                    return;
                }
            }
            // Outside the lock: a release action may block, and may itself try to register or
            // release resources. Popping first also guarantees each action runs at most once.
            runQuietly(next, "release action");
        }
    }

    /**
     * @return how many resources are still tracked; for tests and diagnostics
     */
    public synchronized int size() {
        return releaseActions.size();
    }

    private void runQuietly(final Runnable action, final String description) {
        try {
            action.run();
        } catch (final RuntimeException e) {
            log.warn("Failed to release a resource ({}) of plugin {}", description, pluginId, e);
        }
    }

    private void closeQuietly(final AutoCloseable resource) {
        try {
            resource.close();
        } catch (final Exception e) {
            log.warn("Failed to close resource {} of plugin {}", resource.getClass().getName(), pluginId, e);
        }
    }

    @Override
    public String toString() {
        return "DefaultResourceRegistry{pluginId=" + pluginId + ", tracked=" + size() + '}';
    }
}

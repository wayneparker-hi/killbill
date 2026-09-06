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

/**
 * Tracks everything a plugin allocates that must be released when it stops.
 * <p>
 * This exists because the JVM has no {@code ClassLoader.unload()}. A plugin's classes are reclaimed
 * only once nothing reachable from a GC root refers to its ClassLoader -- and a live thread, a
 * timer, an open connection or a dangling callback is exactly such a reference. Untracked
 * resources therefore do not merely leak themselves: they make plugin unloading a no-op and turn
 * repeated reloads into a metaspace leak.
 * <p>
 * The rule is simple: if it has a {@code close()}, a {@code shutdown()}, or a background thread,
 * it goes in here.
 * <p>
 * Note that shutting a resource down is not the same as releasing it. On current JDKs a terminated
 * {@code Thread} still references the task it ran, so an {@code ExecutorService} that was shut down
 * but is still held by a field keeps its workers' tasks -- and the plugin's ClassLoader -- alive.
 * Implementations must therefore drop their references after closing, and plugins must not keep
 * their own copies of anything handed to this registry.
 */
public interface ResourceRegistry {

    /**
     * Takes ownership of a resource. Returns the same instance so it can wrap an expression:
     * <pre>
     *   Subscription s = ctx.resources().manage(ctx.eventBus().subscribe(Foo.class, this::onFoo));
     * </pre>
     *
     * @param resource the resource
     * @param <T>      resource type
     * @return the same resource
     */
    <T extends AutoCloseable> T manage(T resource);

    /**
     * Registers a release action for something that is not {@link AutoCloseable}, such as
     * deregistering an MBean or a shutdown hook.
     *
     * @param action the action; must be idempotent
     */
    void onClose(Runnable action);

    /**
     * Releases everything, most recently registered first, so that dependents are torn down before
     * what they depend on.
     * <p>
     * Called by the runtime after {@link Plugin#stop}. Every resource is attempted even if an
     * earlier one throws; failures are logged. Never throws.
     */
    void closeAll();
}

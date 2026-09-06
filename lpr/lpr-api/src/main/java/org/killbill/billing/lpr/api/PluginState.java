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
 * Where a plugin is in its lifecycle.
 *
 * <pre>
 *   INSTALLED --&gt; RESOLVED --&gt; STARTING --&gt; ACTIVE --&gt; STOPPING --&gt; STOPPED --&gt; UNINSTALLED
 *                                  |            |
 *                                  +---&gt; FAILED &lt;+
 * </pre>
 *
 * Only the runtime moves a plugin between states, and only through a compare-and-set, so a
 * concurrent double {@code start()} resolves to one winner rather than two half-started plugins.
 * Plugins cannot set their own state.
 * <p>
 * The predecessor OSGi layer exposed just {@code RUNNING} and {@code STOPPED}, derived from
 * {@code Bundle.getState()}. Callers that only care about that distinction should use
 * {@link #isRunning()} rather than enumerating states, so this enum can grow without breaking them.
 */
public enum PluginState {

    /** The artifact is on disk and its descriptor parsed, but nothing has been loaded yet. */
    INSTALLED,

    /** Requirements check out and a ClassLoader could be built. No plugin code has run. */
    RESOLVED,

    /** {@link Plugin#start} is executing. Services registered so far are not yet routable. */
    STARTING,

    /** Started and healthy. This is the only state in which the plugin serves traffic. */
    ACTIVE,

    /** {@link Plugin#stop} is executing. New traffic is already cut off. */
    STOPPING,

    /** Stopped cleanly. The artifact is still installed and can be started again. */
    STOPPED,

    /** Start or stop threw. The plugin holds no routable services; a restart may recover it. */
    FAILED,

    /** Removed from the runtime. Terminal. */
    UNINSTALLED;

    /**
     * Whether the plugin is currently serving traffic.
     *
     * @return true only in {@link #ACTIVE}
     */
    public boolean isRunning() {
        return this == ACTIVE;
    }

    /**
     * Whether this is an end state that no further transition can leave.
     *
     * @return true for {@link #UNINSTALLED}
     */
    public boolean isTerminal() {
        return this == UNINSTALLED;
    }
}

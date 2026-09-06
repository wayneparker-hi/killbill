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
 * A plugin's entry point. The runtime instantiates the class named by {@code entrypoint.class} in
 * the plugin descriptor, using the plugin's own ClassLoader, and drives it through this interface.
 * <p>
 * Implementations must have a public no-argument constructor.
 * <p>
 * <b>Do not call core business APIs from {@link #start}.</b> Plugins are started during the
 * {@code STARTUP_PRE} lifecycle sequence, before core services have started, so the APIs reachable
 * through {@link PluginContext#getPlatformService} are wired but not yet serving. Subscribe to the
 * runtime-ready event instead and do the work there. This ordering is inherited from Kill Bill's
 * existing lifecycle and is deliberately preserved.
 * <p>
 * Anything acquired in {@link #start} that holds a thread, a socket, or a callback must be handed
 * to {@link PluginContext#resources()}. Resources left untracked keep the plugin's ClassLoader
 * reachable, which silently turns unloading into a no-op.
 */
public interface Plugin {

    /**
     * Brings the plugin up: register services, subscribe to events, allocate resources.
     * <p>
     * Throwing moves the plugin to {@link PluginState#FAILED}; the runtime then releases whatever
     * was registered through the context. Partial state left outside the context is the plugin's
     * own problem, so prefer registering as you go over registering at the end.
     *
     * @param context the plugin's window onto the runtime; never null
     * @throws Exception if the plugin cannot start
     */
    void start(PluginContext context) throws Exception;

    /**
     * Releases what {@link #start} acquired.
     * <p>
     * The runtime has already stopped routing new traffic to this plugin's services and will close
     * the context's tracked resources afterwards, so this method only needs to deal with state the
     * plugin manages itself. It must return promptly and must not throw for the ordinary case.
     *
     * @throws Exception if the plugin cannot stop cleanly; the runtime logs it and continues
     */
    void stop() throws Exception;
}

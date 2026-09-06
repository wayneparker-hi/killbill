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
 * In-process publish/subscribe between the core and plugins.
 * <p>
 * The scope is deliberately narrow: dispatch within this JVM. There is no durability, no retry, no
 * exactly-once, and no ordering guarantee between publishers. A handler that throws is logged and
 * skipped; it does not stop delivery to the others and does not fail the publisher.
 * <p>
 * Business flows that must not lose an event need a durable bus, not this.
 */
public interface EventBus {

    /**
     * Delivers an event to every subscriber registered for its type or a supertype.
     *
     * @param event the event; never null
     */
    void publish(Object event);

    /**
     * Subscribes to a type of event.
     * <p>
     * Hand the returned subscription to {@link PluginContext#resources()}. An orphaned subscription
     * keeps the handler -- and therefore the plugin's ClassLoader -- reachable forever.
     *
     * @param eventType the event type; subtypes are delivered too
     * @param handler   the handler
     * @param <T>       event type
     * @return a subscription that unsubscribes when closed
     */
    <T> Subscription subscribe(Class<T> eventType, EventHandler<T> handler);
}

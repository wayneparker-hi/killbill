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
 * Receives events of one type from the {@link EventBus}.
 * <p>
 * Handlers run on a runtime-managed thread with the plugin's ClassLoader as the thread context
 * ClassLoader. Keep them short: a slow handler delays delivery to the rest.
 *
 * @param <T> event type
 */
@FunctionalInterface
public interface EventHandler<T> {

    /**
     * Handles one event. Exceptions are logged by the runtime and do not affect other subscribers.
     *
     * @param event the event; never null
     */
    void handle(T event);
}

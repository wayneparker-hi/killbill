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

/**
 * A live service registration: the implementation plus who published it.
 * <p>
 * References are snapshots. Holding one across a plugin restart is a mistake -- it pins the old
 * implementation and its ClassLoader. Look the service up again instead.
 *
 * @param <T> service type
 */
public interface ServiceReference<T> {

    /**
     * @return the publishing plugin's id
     */
    String pluginId();

    /**
     * @return the publishing plugin's version
     */
    String version();

    /**
     * @return the service interface this was registered under
     */
    Class<T> type();

    /**
     * @return the implementation
     */
    T service();

    /**
     * @return immutable selector properties supplied at registration; empty if none
     */
    Map<String, Object> properties();
}

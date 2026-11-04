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

import java.util.List;

/**
 * Experimental worker entry point, distinct from the in-process Plugin/PluginContext contract.
 * No host callbacks; only data crosses the process boundary. Implementations need a public
 * no-argument constructor. Process termination releases resources, without a shutdown callback.
 */
public interface ProcessPlugin {
    default void start() throws Exception {}

    /** Sequential calls; throwing reports failure, without promising that side effects rolled back. */
    List<String> invoke(String operation, List<String> arguments) throws Exception;
}

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

package org.killbill.billing.lpr.process;

import java.io.IOException;

/** A failed dispatched call may already have performed external side effects. Never auto-retry. */
public final class ProcessPluginCallException extends IOException {
    private static final long serialVersionUID = 1L;
    private final boolean remoteFailure;

    ProcessPluginCallException(final String message, final boolean remoteFailure, final Throwable cause) {
        super(message, cause);
        this.remoteFailure = remoteFailure;
    }

    public boolean isResultUnknown() { return true; }
    public boolean isRemoteFailure() { return remoteFailure; }
}

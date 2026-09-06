/*
 * Copyright 2014-2020 Groupon, Inc
 * Copyright 2020-2020 Equinix, Inc
 * Copyright 2014-2020 The Billing Project, LLC
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

package org.killbill.billing.plugin.core.resources;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Callable;

import org.jooby.Result;
import org.jooby.Results;
import org.jooby.Status;
import org.killbill.billing.plugin.runtime.KillbillApi;
import org.killbill.clock.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BaseResource {

    private static final Logger logger = LoggerFactory.getLogger(BaseResource.class);

    protected final KillbillApi killbillApi;
    protected final Clock clock;

    public BaseResource(final KillbillApi killbillApi, final Clock clock) {
        this.killbillApi = killbillApi;
        this.clock = clock;
    }

    protected Result invalidRequest(final String message) {
        return Results.with(new ExceptionResponse(message), Status.BAD_REQUEST);
    }

    protected Result withExceptionHandling(final Callable<Result> callable) {
        try {
            return callable.call();
        } catch (final Exception exception) {
            logger.warn("Unable to process request", exception);
            return Results.with(new ExceptionResponse(exception, true), Status.SERVER_ERROR);

        }
    }

    protected void login(final String authHeader) throws UnsupportedEncodingException {
        if (authHeader == null) {
            return;
        }

        final String[] authHeaderChunks = authHeader.split(" ");
        if (authHeaderChunks.length < 2) {
            return;
        }

        final String credentials = new String(Base64.getDecoder().decode(authHeaderChunks[1]), StandardCharsets.UTF_8);
        final int p = credentials.indexOf(":");
        if (p == -1) {
            return;
        }

        final String login = credentials.substring(0, p).trim();
        final String password = credentials.substring(p + 1).trim();
        killbillApi.getSecurityApi().login(login, password);
    }
}

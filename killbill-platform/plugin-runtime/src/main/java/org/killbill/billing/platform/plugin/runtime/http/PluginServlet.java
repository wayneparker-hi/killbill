/*
 * Copyright 2010-2014 Ning, Inc.
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

package org.killbill.billing.platform.plugin.runtime.http;

import java.io.IOException;
import java.util.Vector;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.killbill.commons.utils.annotation.VisibleForTesting;

/**
 * The single servlet Kill Bill's JAX-RS layer forwards every {@code /plugins/...} request to.
 * <p>
 * It finds the plugin owning the longest matching path prefix, strips that prefix from the request,
 * and hands it on. Plugin servlets are initialised lazily, on first use.
 */
@Singleton
public class PluginServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @VisibleForTesting
    final Vector<Servlet> initializedServlets = new Vector<>();
    private final Object servletsMonitor = new Object();

    @Inject
    @VisibleForTesting
    transient PluginServletRouter servletRouter;

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
        serviceViaPlugin(req, resp);
    }

    @Override
    protected void doHead(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
        serviceViaPlugin(req, resp);
    }

    @Override
    protected void doPost(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
        serviceViaPlugin(req, resp);
    }

    @Override
    protected void doPut(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
        serviceViaPlugin(req, resp);
    }

    @Override
    protected void doDelete(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
        serviceViaPlugin(req, resp);
    }

    @Override
    protected void doOptions(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
        serviceViaPlugin(req, resp);
    }

    private void serviceViaPlugin(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
        // requestPath is the full path minus the JAX-RS prefix (/plugins)
        final String requestPath = req.getServletPath() + req.getPathInfo();

        final Servlet pluginServlet = getPluginServlet(requestPath);

        if (pluginServlet != null) {
            initializeServletIfNeeded(req, pluginServlet);
            final PluginServletRequestWrapper requestWrapper = new PluginServletRequestWrapper(req, servletRouter.getPluginPrefixForPath(requestPath));
            pluginServlet.service(requestWrapper, resp);
        } else {
            resp.sendError(404);
        }
    }

    // Request wrapper that hides the plugin prefix from the plugin's servlet, which sees it as its servlet path
    private static final class PluginServletRequestWrapper extends HttpServletRequestWrapper {

        private final String pluginPrefix;

        public PluginServletRequestWrapper(final HttpServletRequest request, final String pluginPrefix) {
            super(request);
            this.pluginPrefix = pluginPrefix;
        }

        @Override
        public String getPathInfo() {
            return super.getPathInfo().replace(pluginPrefix, "");
        }

        @Override
        public String getContextPath() {
            return super.getContextPath() + pluginPrefix;
        }
    }

    // Bridges the gap between the web container and servlets registered by plugins
    private void initializeServletIfNeeded(final HttpServletRequest req, final Servlet pluginServlet) throws ServletException {
        if (!initializedServlets.contains(pluginServlet)) {
            synchronized (servletsMonitor) {
                if (!initializedServlets.contains(pluginServlet)) {
                    final ServletConfig servletConfig = (ServletConfig) req.getAttribute("killbill.plugin.servletConfig");
                    if (servletConfig != null) {
                        // TODO PIERRE The servlet will never be destroyed!
                        pluginServlet.init(servletConfig);
                        initializedServlets.add(pluginServlet);
                    }
                }
            }
        }
    }

    private Servlet getPluginServlet(final String requestPath) {
        if (requestPath != null) {
            return servletRouter.getServiceForPath(requestPath);
        } else {
            return null;
        }
    }
}

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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Singleton;
import jakarta.servlet.Servlet;

import org.killbill.billing.platform.plugin.api.PluginServiceDescriptor;
import org.killbill.billing.platform.plugin.api.PluginServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes {@code /plugins/...} requests to the servlet a plugin registered.
 * <p>
 * It is a prefix-to-servlet map plus longest-prefix matching, expressed in plain
 * {@code jakarta.servlet} types. The table is published as an immutable snapshot, so a request can
 * route while a lifecycle operation installs, stops or upgrades a plugin.
 */
@Singleton
public class PluginServletRouter implements PluginServiceRegistry<Servlet> {

    private static final Logger logger = LoggerFactory.getLogger(PluginServletRouter.class);

    // A request must see one coherent routing table while a lifecycle thread adds or removes a
    // plugin. Replacing immutable snapshots makes reads lock-free and avoids iterating a HashMap
    // while another thread mutates it during a hot upgrade.
    private volatile RoutingTable routes = RoutingTable.empty();

    @Override
    public void registerService(final PluginServiceDescriptor desc, final Servlet httpServlet) {
        // Enforce each route to start with /
        final String pathPrefix = getPathPrefixFromDescriptor(desc);
        if (pathPrefix == null) {
            logger.warn("Skipping registration of plugin servlet for service {} (service info is not specified)", desc.getRegistrationName());
            return;
        }

        logger.info("Registering plugin servlet at " + pathPrefix);
        updateRoutes(pathPrefix, httpServlet, desc);
    }

    public void registerServiceFromPath(final String path, final Servlet httpServlet) {
        final String pathPrefix = sanitizePathPrefix(path);
        updateRoutes(pathPrefix, httpServlet, null);
    }

    private synchronized void updateRoutes(final String pathPrefix,
                                           final Servlet httpServlet,
                                           final PluginServiceDescriptor descriptor) {
        final Map<String, Servlet> servlets = new HashMap<>(routes.servlets());
        final Map<String, PluginServiceDescriptor> registrations = new HashMap<>(routes.registrations());
        servlets.put(pathPrefix, httpServlet);
        if (descriptor != null) {
            registrations.put(descriptor.getRegistrationName(), descriptor);
        }
        routes = new RoutingTable(servlets, registrations);
    }

    @Override
    public void unregisterService(final String serviceName) {
        synchronized (this) {
            final PluginServiceDescriptor desc = routes.registrations().get(serviceName);
            if (desc != null) {
                final String pathPrefix = getPathPrefixFromDescriptor(desc);
                if (pathPrefix == null) {
                    logger.warn("Skipping unregistration of plugin servlet for service {} (service info is not specified)", desc.getRegistrationName());
                    return;
                }

                logger.info("Unregistering plugin servlet " + desc.getRegistrationName() + " at path " + pathPrefix);
                removeRoutes(pathPrefix, desc.getRegistrationName());
            }
        }
    }

    public void unregisterServiceFromPath(final String path) {
        final String pathPrefix = sanitizePathPrefix(path);
        removeRoutes(pathPrefix, null);
    }

    private synchronized void removeRoutes(final String pathPrefix, final String registrationName) {
        final Map<String, Servlet> servlets = new HashMap<>(routes.servlets());
        final Map<String, PluginServiceDescriptor> registrations = new HashMap<>(routes.registrations());
        servlets.remove(pathPrefix);
        if (registrationName != null) {
            registrations.remove(registrationName);
        }
        routes = new RoutingTable(servlets, registrations);
    }

    @Override
    public Servlet getServiceForName(final String serviceName) {
        final RoutingTable snapshot = routes;
        final PluginServiceDescriptor desc = snapshot.registrations().get(serviceName);
        if (desc == null) {
            return null;
        }
        final String registeredPath = getPathPrefixFromDescriptor(desc);
        return snapshot.servlets().get(registeredPath);
    }

    private String getPathPrefixFromDescriptor(final PluginServiceDescriptor desc) {
        return sanitizePathPrefix(desc.getRegistrationName());
    }

    public Servlet getServiceForPath(final String path) {
        return getServletForPathPrefix(path);
    }

    @Override
    public Set<String> getAllServices() {
        return routes.registrations().keySet();
    }

    @Override
    public Class<Servlet> getServiceType() {
        return Servlet.class;
    }

    // TODO PIERRE Naive implementation - we should rather switch to e.g. heap tree
    public String getPluginPrefixForPath(final String pathPrefix) {
        return bestMatchFor(routes.servlets(), pathPrefix);
    }

    private static String bestMatchFor(final Map<String, Servlet> servlets, final String pathPrefix) {
        String bestMatch = null;
        for (final String potentialMatch : servlets.keySet()) {
            if (pathPrefix.startsWith(potentialMatch) && (bestMatch == null || bestMatch.length() < potentialMatch.length())) {
                bestMatch = potentialMatch;
            }
        }
        return bestMatch;
    }

    private Servlet getServletForPathPrefix(final String pathPrefix) {
        final RoutingTable snapshot = routes;
        final String bestMatch = bestMatchFor(snapshot.servlets(), pathPrefix);
        return bestMatch == null ? null : snapshot.servlets().get(bestMatch);
    }

    private static String sanitizePathPrefix(final String inputPath) {
        if (inputPath == null) {
            return null;
        }

        final String pathPrefix;
        if (inputPath.isEmpty() || inputPath.charAt(0) != '/') {
            pathPrefix = "/" + inputPath;
        } else {
            pathPrefix = inputPath;
        }
        return pathPrefix;
    }

    private record RoutingTable(Map<String, Servlet> servlets,
                                Map<String, PluginServiceDescriptor> registrations) {

        private RoutingTable {
            servlets = Map.copyOf(servlets);
            registrations = Map.copyOf(registrations);
        }

        private static RoutingTable empty() {
            return new RoutingTable(Map.of(), Map.of());
        }
    }
}

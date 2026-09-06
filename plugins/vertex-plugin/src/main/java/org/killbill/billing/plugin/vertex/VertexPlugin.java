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

package org.killbill.billing.plugin.vertex;

import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServlet;
import org.killbill.billing.invoice.plugin.api.InvoicePluginApi;
import org.killbill.billing.notification.plugin.api.NotificationPluginApi;
import org.killbill.billing.plugin.api.notification.PluginConfigurationEventHandler;
import org.killbill.billing.plugin.core.resources.jooby.PluginApp;
import org.killbill.billing.plugin.core.resources.jooby.PluginAppBuilder;
import org.killbill.billing.plugin.runtime.PluginBase;
import org.killbill.billing.plugin.vertex.dao.VertexDao;
import org.killbill.billing.plugin.vertex.health.VertexHealthcheck;
import org.killbill.billing.plugin.vertex.health.VertexHealthcheckServlet;
import org.killbill.billing.runtime.api.Healthcheck;

/**
 * Migrated from VertexPlugin.
 * <p>
 * The framework handle, the {@code Hashtable} of registration properties and the per-type
 * {@code registerXxx} helpers are gone; what remains is the plugin's own startup order.
 */
public class VertexPlugin extends PluginBase {

    public static final String PLUGIN_NAME = "killbill-vertex";

    private VertexApiConfigurationHandler vertexApiConfigurationHandler;

    @Override
    protected void startPlugin() throws Exception {
        

        final VertexDao dao = new VertexDao(dataSource());

        vertexApiConfigurationHandler = new VertexApiConfigurationHandler(PLUGIN_NAME, killbillApi);

        final VertexApiClient vertexApiClient = vertexApiConfigurationHandler.createConfigurable(configProperties.getProperties());
        vertexApiConfigurationHandler.setDefaultConfigurable(vertexApiClient);

        // Create and register Health-check
        final VertexHealthcheck vertexHealthcheck = new VertexHealthcheck(vertexApiConfigurationHandler);
        registerService(Healthcheck.class, vertexHealthcheck);

        final VertexTaxCalculator vertexTaxCalculator = new VertexTaxCalculator(vertexApiConfigurationHandler,
                                                                                dao,
                                                                                clock,
                                                                                killbillApi);
        final VertexInvoicePluginApi pluginApi = new VertexInvoicePluginApi(vertexApiConfigurationHandler,
                                                                            killbillApi,
                                                                            configProperties,
                                                                            vertexTaxCalculator,
                                                                            dao,
                                                                            clock);
        // Register the invoice plugin
        registerService(InvoicePluginApi.class, pluginApi);

        // Register the servlet
        final PluginApp pluginApp = new PluginAppBuilder(PLUGIN_NAME,
                                                         killbillApi,
                                                         dataSource(),
                                                         clock,
                                                         configProperties).withRouteClass(VertexHealthcheckServlet.class)
                                                                          .withService(vertexHealthcheck)
                                                                          .withService(dao)
                                                                          .build();

        final HttpServlet invoiceServlet = PluginApp.createServlet(pluginApp);
        registerService(Servlet.class, invoiceServlet);

        // Was dispatcher.registerEventHandlers(...): a configuration handler is a
        // NotificationPluginApi now, published like any other service.
        registerService(NotificationPluginApi.class,
                        new PluginConfigurationEventHandler(vertexApiConfigurationHandler));
    }
}

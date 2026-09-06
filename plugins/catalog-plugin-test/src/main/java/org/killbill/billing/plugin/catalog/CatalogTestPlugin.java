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

package org.killbill.billing.plugin.catalog;

import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServlet;
import org.killbill.billing.catalog.plugin.api.CatalogPluginApi;
import org.killbill.billing.notification.plugin.api.NotificationPluginApi;
import org.killbill.billing.plugin.api.notification.PluginConfigurationEventHandler;
import org.killbill.billing.plugin.core.config.PluginEnvironmentConfig;
import org.killbill.billing.plugin.core.resources.jooby.PluginApp;
import org.killbill.billing.plugin.core.resources.jooby.PluginAppBuilder;
import org.killbill.billing.plugin.runtime.PluginBase;
import org.killbill.billing.runtime.api.Healthcheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Migrated from CatalogTestPlugin.
 * <p>
 * The framework handle, the {@code Hashtable} of registration properties and the per-type
 * {@code registerXxx} helpers are gone; what remains is the plugin's own startup order.
 */
public class CatalogTestPlugin extends PluginBase {

    public static final String PLUGIN_NAME = "killbill-catalog-test";

    private final Logger logger = LoggerFactory.getLogger(CatalogTestPlugin.class);
    private CatalogConfigurationHandler configurationHandler;

    @Override
    protected void startPlugin() throws Exception {
        

        logger.info("Starting {}", PLUGIN_NAME);

        final String region = PluginEnvironmentConfig.getRegion(configProperties.getProperties());
        configurationHandler = new CatalogConfigurationHandler(region, PLUGIN_NAME, killbillApi);
        final CatalogConfiguration defaultConfiguration = createCatalogConfig("WeaponsHire.xml");
        configurationHandler.setDefaultConfigurable(defaultConfiguration);

        final CatalogPluginApi catalogPluginApi = new CatalogPluginApiImpl(configurationHandler, killbillApi);
        registerService(CatalogPluginApi.class, catalogPluginApi);

        // Expose a healthcheck (optional), so other plugins can check on the plugin status
        final Healthcheck healthcheck = new CatalogTestHealthcheck();
        registerService(Healthcheck.class, healthcheck);

        // Register a servlet (optional)
        final PluginApp pluginApp = new PluginAppBuilder(PLUGIN_NAME,
                                                         killbillApi,
                                                         dataSource(),
                                                         clock,
                                                         configProperties).withRouteClass(CatalogTestHealthcheckServlet.class)
                                                                          .withService(healthcheck)
                                                                          .build();
        final HttpServlet httpServlet = PluginApp.createServlet(pluginApp);
        registerService(Servlet.class, httpServlet);

        registerEventHandlers();

        // Was dispatcher.registerEventHandlers(...): a configuration handler is a
        // NotificationPluginApi now, published like any other service.
        registerService(NotificationPluginApi.class,
                        new PluginConfigurationEventHandler(configurationHandler));
    }

    private void registerEventHandlers() {
        final PluginConfigurationEventHandler configHandler = new PluginConfigurationEventHandler(configurationHandler);
        registerService(NotificationPluginApi.class, configHandler);
    }
    private static CatalogConfiguration createCatalogConfig(final String uri) {
        final String raw = String.format("!!org.killbill.billing.plugin.catalog.CatalogYAMLConfiguration\n" +
                "  uri: %s\n" +
                "  validateAccount: false\n" +
                "  accountCatalog: false", uri);
        return CatalogConfigurationHandler.fromYAML(raw);
    }
    @Override
    protected void stopPlugin() throws Exception {
    }
}

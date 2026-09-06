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

package org.killbill.billing.plugin.currency;

import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServlet;
import java.util.Properties;
import org.killbill.billing.currency.plugin.api.CurrencyPluginApi;
import org.killbill.billing.notification.plugin.api.NotificationPluginApi;
import org.killbill.billing.plugin.api.notification.PluginConfigurationEventHandler;
import org.killbill.billing.plugin.core.config.PluginEnvironmentConfig;
import org.killbill.billing.plugin.core.resources.jooby.PluginApp;
import org.killbill.billing.plugin.core.resources.jooby.PluginAppBuilder;
import org.killbill.billing.plugin.currency.dao.CurrencyDao;
import org.killbill.billing.plugin.runtime.PluginBase;
import org.killbill.billing.runtime.api.Healthcheck;

/**
 * Migrated from CurrencyPlugin.
 * <p>
 * The framework handle, the {@code Hashtable} of registration properties and the per-type
 * {@code registerXxx} helpers are gone; what remains is the plugin's own startup order.
 */
public class CurrencyPlugin extends PluginBase {

    public static final String PLUGIN_NAME = "killbill-currency";

    private CurrencyConfigurationHandler currencyConfigurationHandler;

    @Override
    protected void startPlugin() throws Exception {
        

        final String region = PluginEnvironmentConfig.getRegion(configProperties.getProperties());

        // Register an event listener for plugin configuration
        currencyConfigurationHandler = new CurrencyConfigurationHandler(region, PLUGIN_NAME, killbillApi);
        final Properties globalConfiguration = currencyConfigurationHandler.createConfigurable(configProperties.getProperties());
        currencyConfigurationHandler.setDefaultConfigurable(globalConfiguration);

        // Register the CurrencyPluginApi
        final CurrencyDao currencyDao = new CurrencyDao(dataSource());
        final CurrencyPluginApi pluginApi = new StaticCurrencyPluginApi(currencyDao);
        registerService(CurrencyPluginApi.class, pluginApi);

        // Expose a healthcheck, so other plugins can check on the plugin status
        final Healthcheck healthcheck = new CurrencyHealthcheck();
        registerService(Healthcheck.class, healthcheck);

        // Register a servlet
        final PluginApp pluginApp = new PluginAppBuilder(PLUGIN_NAME, killbillApi, dataSource(), clock,
                                                         configProperties).withRouteClass(CurrencyServlet.class)
                                                                          .withRouteClass(CurrencyHealthcheckServlet.class)
                                                                          .withService(healthcheck)
                                                                          .withService(clock)
                                                                          .withService(currencyDao)
                                                                          .withService(pluginApi)
                                                                          .build();
        final HttpServlet httpServlet = PluginApp.createServlet(pluginApp);
        registerService(Servlet.class, httpServlet);

        // Was dispatcher.registerEventHandlers(...): a configuration handler is a
        // NotificationPluginApi now, published like any other service.
        registerService(NotificationPluginApi.class,
                        new PluginConfigurationEventHandler(currencyConfigurationHandler));
    }

    private void registerHandlers() {
        final PluginConfigurationEventHandler configHandler = new PluginConfigurationEventHandler(
                currencyConfigurationHandler);

        registerService(NotificationPluginApi.class, configHandler);
    }
    @Override
    protected void stopPlugin() throws Exception {
        // Do additional work on shutdown (optional)
    }
}

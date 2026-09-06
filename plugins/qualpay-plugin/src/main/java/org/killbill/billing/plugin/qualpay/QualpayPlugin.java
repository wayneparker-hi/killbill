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

package org.killbill.billing.plugin.qualpay;

import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServlet;
import org.killbill.billing.notification.plugin.api.NotificationPluginApi;
import org.killbill.billing.payment.plugin.api.PaymentPluginApi;
import org.killbill.billing.plugin.api.notification.PluginConfigurationEventHandler;
import org.killbill.billing.plugin.core.config.PluginEnvironmentConfig;
import org.killbill.billing.plugin.core.resources.jooby.PluginApp;
import org.killbill.billing.plugin.core.resources.jooby.PluginAppBuilder;
import org.killbill.billing.plugin.qualpay.dao.QualpayDao;
import org.killbill.billing.plugin.runtime.PluginBase;
import org.killbill.billing.runtime.api.Healthcheck;

/**
 * Migrated from QualpayPlugin.
 * <p>
 * The framework handle, the {@code Hashtable} of registration properties and the per-type
 * {@code registerXxx} helpers are gone; what remains is the plugin's own startup order.
 */
public class QualpayPlugin extends PluginBase {

    public static final String PLUGIN_NAME = "killbill-qualpay";

    private QualpayConfigPropertiesConfigurationHandler qualpayConfigPropertiesConfigurationHandler;

    @Override
    protected void startPlugin() throws Exception {
        

        final QualpayDao qualpayDao = new QualpayDao(dataSource());

        final String region = PluginEnvironmentConfig.getRegion(configProperties.getProperties());
        qualpayConfigPropertiesConfigurationHandler = new QualpayConfigPropertiesConfigurationHandler(PLUGIN_NAME, killbillApi, region);

        final QualpayConfigProperties qualpayConfigProperties = qualpayConfigPropertiesConfigurationHandler.createConfigurable(configProperties.getProperties());
        qualpayConfigPropertiesConfigurationHandler.setDefaultConfigurable(qualpayConfigProperties);

        // Register the payment plugin
        final PaymentPluginApi pluginApi = new QualpayPaymentPluginApi(qualpayConfigPropertiesConfigurationHandler,
                                                                       killbillApi,
                                                                       configProperties,
                                                                       clock,
                                                                       qualpayDao);
        registerService(PaymentPluginApi.class, pluginApi);

        // Expose a healthcheck, so other plugins can check on the plugin status
        final Healthcheck healthcheck = new QualpayHealthcheck(qualpayConfigPropertiesConfigurationHandler);
        registerService(Healthcheck.class, healthcheck);

        // Register a servlet
        final PluginApp pluginApp = new PluginAppBuilder(PLUGIN_NAME, killbillApi, dataSource(), clock, configProperties)
                .withRouteClass(QualpayHealthcheckServlet.class).withService(healthcheck)
                .withService(qualpayConfigPropertiesConfigurationHandler)
                .build();
        final HttpServlet httpServlet = PluginApp.createServlet(pluginApp);
        registerService(Servlet.class, httpServlet);

        // Was dispatcher.registerEventHandlers(...): a configuration handler is a
        // NotificationPluginApi now, published like any other service.
        registerService(NotificationPluginApi.class,
                        new PluginConfigurationEventHandler(qualpayConfigPropertiesConfigurationHandler));
    }

    private void registerHandlers() {
        final PluginConfigurationEventHandler handler = new PluginConfigurationEventHandler(qualpayConfigPropertiesConfigurationHandler);
        registerService(NotificationPluginApi.class, handler);
    }}

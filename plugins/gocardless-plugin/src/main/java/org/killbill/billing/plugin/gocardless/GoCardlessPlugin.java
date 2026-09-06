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

package org.killbill.billing.plugin.gocardless;

import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServlet;

import org.killbill.billing.payment.plugin.api.PaymentPluginApi;
import org.killbill.billing.plugin.core.config.PluginEnvironmentConfig;
import org.killbill.billing.plugin.core.resources.jooby.PluginApp;
import org.killbill.billing.plugin.core.resources.jooby.PluginAppBuilder;
import org.killbill.billing.plugin.runtime.PluginBase;
import org.killbill.billing.runtime.api.Healthcheck;

/**
 * The GoCardless payment plugin, migrated from OSGi to LPR.
 * <p>
 * A compact example of what the migration removes: three {@code registerXxx} helpers, each building
 * a {@code Hashtable} of properties and calling {@code registrar.registerService(context, ...)},
 * become three {@code registerService} calls with the name defaulted to the plugin id.
 */
public class GoCardlessPlugin extends PluginBase {

    /** The name Kill Bill routes payments to this plugin by. */
    public static final String PLUGIN_NAME = "killbill-gocardless";

    @Override
    protected void startPlugin() throws Exception {
        final String region = PluginEnvironmentConfig.getRegion(configProperties.getProperties());

        final GoCardlessConfigurationHandler configurationHandler =
                new GoCardlessConfigurationHandler(region, PLUGIN_NAME, killbillApi);
        configurationHandler.setDefaultConfigurable(
                configurationHandler.createConfigurable(configProperties.getProperties()));

        final GoCardlessPaymentPluginApi pluginApi =
                new GoCardlessPaymentPluginApi(configurationHandler, killbillApi, clock);
        registerService(PaymentPluginApi.class, pluginApi);

        final Healthcheck healthcheck = new GoCardlessHealthCheck(configurationHandler);
        registerService(Healthcheck.class, healthcheck);

        final PluginApp pluginApp = new PluginAppBuilder(PLUGIN_NAME, killbillApi, dataSource(), clock,
                                                        configProperties)
                .withRouteClass(GoCardlessCheckoutServlet.class)
                .withRouteClass(GoCardlessHealthCheckServlet.class)
                .withService(healthcheck)
                .withService(pluginApi)
                .withService(clock)
                .build();
        registerService(Servlet.class, (HttpServlet) PluginApp.createServlet(pluginApp));
    }
}

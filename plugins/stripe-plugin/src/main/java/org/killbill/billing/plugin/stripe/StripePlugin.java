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

package org.killbill.billing.plugin.stripe;

import org.killbill.clock.Clock;

import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServlet;

import org.killbill.billing.notification.plugin.api.NotificationPluginApi;
import org.killbill.billing.payment.plugin.api.PaymentPluginApi;
import org.killbill.billing.plugin.api.notification.PluginConfigurationEventHandler;
import org.killbill.billing.plugin.core.config.PluginEnvironmentConfig;
import org.killbill.billing.plugin.core.resources.jooby.PluginApp;
import org.killbill.billing.plugin.core.resources.jooby.PluginAppBuilder;
import org.killbill.billing.plugin.runtime.PluginBase;
import org.killbill.billing.plugin.stripe.dao.StripeDao;
import org.killbill.billing.runtime.api.Healthcheck;

import com.stripe.Stripe;

/**
 * The Stripe payment plugin, migrated from OSGi to LPR.
 * <p>
 * The first migrated plugin that does what a production plugin does -- it keeps its own tables and
 * calls an external HTTP API -- so it is the one that says whether the runtime's platform services
 * are actually sufficient. Two of them carry the weight:
 * <ul>
 *   <li>{@code DataSource}: the plugin's own schema lives in Kill Bill's database, reached through
 *       the shared pool. The OSGi layer handed out a <em>second</em> DataSource of its own; the
 *       plugin runtime publishes the one the platform already has, which is one fewer pool to size
 *       and one fewer thing to leak.</li>
 *   <li>{@code Clock}: taken directly rather than through an {@code Clock} wrapper that
 *       existed only to survive the service disappearing.</li>
 * </ul>
 * Everything else -- the Stripe SDK, the DAO, the payment API implementation, the servlets -- is
 * untouched business code.
 */
public class StripePlugin extends PluginBase {

    public static final String PLUGIN_NAME = "killbill-stripe";

    @Override
    protected void startPlugin() throws Exception {
        final StripeDao stripeDao = new StripeDao(dataSource());

        final String region = PluginEnvironmentConfig.getRegion(configProperties.getProperties());
        final StripeConfigPropertiesConfigurationHandler configurationHandler =
                new StripeConfigPropertiesConfigurationHandler(PLUGIN_NAME, killbillApi, region);
        configurationHandler.setDefaultConfigurable(
                configurationHandler.createConfigurable(configProperties.getProperties()));

        final StripeHealthcheck healthcheck = new StripeHealthcheck(configurationHandler);
        registerService(Healthcheck.class, healthcheck);

        Stripe.setAppInfo("Kill Bill", "7.2.0", "https://killbill.io");
        final StripePaymentPluginApi pluginApi = new StripePaymentPluginApi(configurationHandler,
                                                                           killbillApi,
                                                                           configProperties,
                                                                           clock,
                                                                           stripeDao);
        registerService(PaymentPluginApi.class, pluginApi);

        final PluginApp pluginApp = new PluginAppBuilder(PLUGIN_NAME, killbillApi, dataSource(), clock,
                                                        configProperties)
                .withRouteClass(StripeHealthcheckServlet.class)
                .withRouteClass(StripeCheckoutServlet.class)
                .withService(healthcheck)
                .withService(pluginApi)
                .withService(clock)
                .build();
        final HttpServlet stripeServlet = PluginApp.createServlet(pluginApp);
        registerService(Servlet.class, stripeServlet);

        // Was registerService(NotificationPluginApi.class, ...); a configuration handler is now a
        // NotificationPluginApi published like any other service.
        registerService(NotificationPluginApi.class,
                        new PluginConfigurationEventHandler(configurationHandler));
    }
}

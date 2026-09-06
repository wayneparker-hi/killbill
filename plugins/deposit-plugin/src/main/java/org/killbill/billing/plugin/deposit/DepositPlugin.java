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

package org.killbill.billing.plugin.deposit;

import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServlet;
import java.util.Dictionary;
import org.killbill.billing.control.plugin.api.PaymentControlPluginApi;
import org.killbill.billing.notification.plugin.api.NotificationPluginApi;
import org.killbill.billing.payment.plugin.api.PaymentPluginApi;
import org.killbill.billing.plugin.api.notification.PluginConfigurationEventHandler;
import org.killbill.billing.plugin.core.config.PluginEnvironmentConfig;
import org.killbill.billing.plugin.core.resources.jooby.PluginApp;
import org.killbill.billing.plugin.core.resources.jooby.PluginAppBuilder;
import org.killbill.billing.plugin.deposit.dao.DepositDao;
import org.killbill.billing.plugin.runtime.PluginBase;
import org.killbill.billing.runtime.api.Healthcheck;

/**
 * Migrated from DepositPlugin.
 * <p>
 * The framework handle, the {@code Hashtable} of registration properties and the per-type
 * {@code registerXxx} helpers are gone; what remains is the plugin's own startup order.
 */
public class DepositPlugin extends PluginBase {

    public static final String PLUGIN_NAME = "killbill-deposit";

    private DepositConfigurationHandler depositConfigurationHandler;

    @Override
    protected void startPlugin() throws Exception {
        

        final String region = PluginEnvironmentConfig.getRegion(configProperties.getProperties());

        depositConfigurationHandler = new DepositConfigurationHandler(region, PLUGIN_NAME, killbillApi);
        depositConfigurationHandler.setDefaultConfigurable(new DepositConfiguration());

        final PaymentControlPluginApi paymentControlPluginApi = new DepositPaymentControlPluginApi(depositConfigurationHandler,
                                                                                                   killbillApi,
                                                                                                   configProperties,
                                                                                                   clock);
        registerService(PaymentControlPluginApi.class, paymentControlPluginApi);

        final DepositDao depositDao = new DepositDao(dataSource());
        final PaymentPluginApi paymentPluginApi = new DepositPaymentPluginApi(killbillApi, configProperties, clock, depositDao);
        registerService(PaymentPluginApi.class, paymentPluginApi);

        final Healthcheck healthcheck = new DepositHealthcheck();
        registerService(Healthcheck.class, healthcheck);

        final PluginApp pluginApp = new PluginAppBuilder(PLUGIN_NAME,
                                                         killbillApi,
                                                         dataSource(),
                                                         clock,
                                                         configProperties).withRouteClass(DepositServlet.class)
                                                                          .withRouteClass(DepositHealthcheckServlet.class)
                                                                          .withService(healthcheck)
                                                                          .build();
        final HttpServlet httpServlet = PluginApp.createServlet(pluginApp);
        registerService(Servlet.class, httpServlet);

        // Was dispatcher.registerEventHandlers(...): a configuration handler is a
        // NotificationPluginApi now, published like any other service.
        registerService(NotificationPluginApi.class,
                        new PluginConfigurationEventHandler(depositConfigurationHandler));
    }

    private void registerHandlers() {
        final PluginConfigurationEventHandler configHandler = new PluginConfigurationEventHandler(depositConfigurationHandler);
        registerService(NotificationPluginApi.class, configHandler);
    }}

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

package org.killbill.billing.plugin.payment.retries;

import jakarta.servlet.Servlet;
import org.killbill.billing.control.plugin.api.PaymentControlPluginApi;
import org.killbill.billing.notification.plugin.api.NotificationPluginApi;
import org.killbill.billing.plugin.api.notification.PluginConfigurationEventHandler;
import org.killbill.billing.plugin.payment.retries.api.PaymentRetriesApi;
import org.killbill.billing.plugin.payment.retries.config.DefaultPaymentRetriesApi;
import org.killbill.billing.plugin.payment.retries.config.PaymentRetriesConfiguration;
import org.killbill.billing.plugin.payment.retries.config.PaymentRetriesConfigurationHandler;
import org.killbill.billing.plugin.runtime.PluginBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Migrated from PaymentRetriesPlugin.
 * <p>
 * The framework handle, the {@code Hashtable} of registration properties and the per-type
 * {@code registerXxx} helpers are gone; what remains is the plugin's own startup order.
 */
public class PaymentRetriesPlugin extends PluginBase {

    public static final String PLUGIN_NAME = "payment-retries-plugin";

    private static final Logger log = LoggerFactory.getLogger(PaymentRetriesPlugin.class);

    private PaymentRetriesConfigurationHandler paymentRetriesConfigurationHandler;

    @Override
    protected void startPlugin() throws Exception {
        

        paymentRetriesConfigurationHandler = new PaymentRetriesConfigurationHandler(PLUGIN_NAME, killbillApi, log);

        final PaymentRetriesConfiguration globalConfigurable = paymentRetriesConfigurationHandler.createConfigurable(configProperties.getProperties());
        paymentRetriesConfigurationHandler.setDefaultConfigurable(globalConfigurable);

        final PaymentRetriesApi paymentRetriesApi = new DefaultPaymentRetriesApi(killbillApi);
        registerService(PaymentRetriesApi.class, paymentRetriesApi);

        final PaymentControlPluginApi paymentControlPluginApi = new PaymentRetriesPaymentControlPluginApi(paymentRetriesConfigurationHandler,
                                                                                                          paymentRetriesApi,
                                                                                                          killbillApi,
                                                                                                          configProperties,
                                                                                                          log,
                                                                                                          clock);
        registerService(PaymentControlPluginApi.class, paymentControlPluginApi);

        final PaymentRetriesServlet analyticsServlet = new PaymentRetriesServlet(paymentRetriesApi);
        registerService(Servlet.class, analyticsServlet);

        // Was dispatcher.registerEventHandlers(...): a configuration handler is a
        // NotificationPluginApi now, published like any other service.
        registerService(NotificationPluginApi.class,
                        new PluginConfigurationEventHandler(paymentRetriesConfigurationHandler));
    }

}

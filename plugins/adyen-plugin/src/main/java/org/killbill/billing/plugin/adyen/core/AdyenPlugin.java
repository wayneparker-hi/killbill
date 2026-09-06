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

package org.killbill.billing.plugin.adyen.core;

import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServlet;
import org.killbill.billing.notification.plugin.api.NotificationPluginApi;
import org.killbill.billing.payment.plugin.api.PaymentPluginApi;
import org.killbill.billing.plugin.adyen.api.AdyenPaymentPluginApi;
import org.killbill.billing.plugin.adyen.core.resources.AdyenCheckoutService;
import org.killbill.billing.plugin.adyen.core.resources.AdyenCheckoutServlet;
import org.killbill.billing.plugin.adyen.core.resources.AdyenHealthcheckServlet;
import org.killbill.billing.plugin.adyen.core.resources.AdyenNotificationServlet;
import org.killbill.billing.plugin.adyen.dao.AdyenDao;
import org.killbill.billing.plugin.api.notification.PluginConfigurationEventHandler;
import org.killbill.billing.plugin.core.config.PluginEnvironmentConfig;
import org.killbill.billing.plugin.core.resources.jooby.PluginApp;
import org.killbill.billing.plugin.core.resources.jooby.PluginAppBuilder;
import org.killbill.billing.plugin.runtime.PluginBase;
import org.killbill.billing.runtime.api.Healthcheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Migrated from AdyenPlugin.
 * <p>
 * The framework handle, the {@code Hashtable} of registration properties and the per-type
 * {@code registerXxx} helpers are gone; what remains is the plugin's own startup order.
 */
public class AdyenPlugin extends PluginBase {

    private static final Logger logger = LoggerFactory.getLogger(AdyenPlugin.class);
    public static final String PLUGIN_NAME = "adyen-plugin";
    private AdyenConfigurationHandler adyenConfigurationHandler;





    @Override
    protected void startPlugin() throws Exception {


        // Was dispatcher.registerEventHandlers(...): a configuration handler is a
        // NotificationPluginApi now, published like any other service.
        registerService(NotificationPluginApi.class,
                        new PluginConfigurationEventHandler(adyenConfigurationHandler));
    }
}

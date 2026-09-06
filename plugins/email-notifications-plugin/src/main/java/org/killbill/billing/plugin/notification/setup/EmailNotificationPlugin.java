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

package org.killbill.billing.plugin.notification.setup;

import org.killbill.billing.plugin.notification.api.InvoiceFormatterFactory;

import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServlet;
import org.killbill.billing.notification.plugin.api.NotificationPluginApi;
import org.killbill.billing.plugin.api.notification.PluginConfigurationEventHandler;
import org.killbill.billing.plugin.core.config.PluginEnvironmentConfig;
import org.killbill.billing.plugin.core.resources.jooby.PluginApp;
import org.killbill.billing.plugin.core.resources.jooby.PluginAppBuilder;
import org.killbill.billing.plugin.notification.dao.ConfigurationDao;
import org.killbill.billing.plugin.notification.http.EmailNotificationServlet;
import org.killbill.billing.plugin.runtime.PluginBase;

/**
 * Migrated from EmailNotificationPlugin.
 * <p>
 * The framework handle, the {@code Hashtable} of registration properties and the per-type
 * {@code registerXxx} helpers are gone; what remains is the plugin's own startup order.
 */
public class EmailNotificationPlugin extends PluginBase {

    public static final String PLUGIN_NAME = "killbill-email-notifications";
    public static final String PROPERTY_PREFIX = "org.killbill.billing.plugin.email-notifications.";

    private NotificationPluginApi emailNotificationListener;
    private EmailNotificationConfigurationHandler emailNotificationConfigurationHandler;

    @Override
    protected void startPlugin() throws Exception {
        

        final String region = PluginEnvironmentConfig.getRegion(configProperties.getProperties());
        
        // Register an event listener for plugin configuration (optional)
        emailNotificationConfigurationHandler = new EmailNotificationConfigurationHandler(region, PLUGIN_NAME, killbillApi, dataSource());
        final EmailNotificationConfiguration globalConfiguration = emailNotificationConfigurationHandler.createConfigurable(configProperties.getProperties());
        emailNotificationConfigurationHandler.setDefaultConfigurable(globalConfiguration);

        // Whatever plugin publishes an InvoiceFormatterFactory, or nothing. Was a ServiceTracker
        // kept open for the lifetime of the plugin; the registry answers the same question.
        final InvoiceFormatterFactory invoiceFormatterFactory =
                context.services().getService(InvoiceFormatterFactory.class, PLUGIN_NAME).orElse(null);

        // Register an event listener (optional)
        emailNotificationListener = new EmailNotificationListener(clock, killbillApi, configProperties, dataSource(), emailNotificationConfigurationHandler, invoiceFormatterFactory);

        final ConfigurationDao configurationDao = new ConfigurationDao(dataSource());

        // Register a servlet (optional)
        final PluginApp pluginApp = new PluginAppBuilder(PLUGIN_NAME,
                                                         killbillApi,
                                                         dataSource(),
                                                         clock,
                                                         configProperties).withRouteClass(EmailNotificationServlet.class)
                                                                          .withService(configurationDao)
                                                                          .build();
        final HttpServlet httpServlet = PluginApp.createServlet(pluginApp);
        registerService(Servlet.class, httpServlet);

        // Was dispatcher.registerEventHandlers(...): a configuration handler is a
        // NotificationPluginApi now, published like any other service.
        registerService(NotificationPluginApi.class,
                        new PluginConfigurationEventHandler(emailNotificationConfigurationHandler),
                        PLUGIN_NAME + "-config");

        // Was registered from an OSGIFrameworkEventHandler callback, so that it only happened once
        // the framework had finished starting. The runtime starts plugins after the registry
        // exists, so the callback has nothing left to wait for.
        registerService(NotificationPluginApi.class, emailNotificationListener);
    }

}

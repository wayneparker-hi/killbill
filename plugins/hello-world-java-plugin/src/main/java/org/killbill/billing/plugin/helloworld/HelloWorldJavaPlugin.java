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

package org.killbill.billing.plugin.helloworld;

import java.util.Properties;

import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServlet;

import org.killbill.billing.invoice.plugin.api.InvoiceFormatterFactory;
import org.killbill.billing.invoice.plugin.api.InvoicePluginApi;
import org.killbill.billing.notification.plugin.api.NotificationPluginApi;
import org.killbill.billing.payment.plugin.api.PaymentPluginApi;
import org.killbill.billing.plugin.api.notification.PluginConfigurationEventHandler;
import org.killbill.billing.plugin.core.config.PluginEnvironmentConfig;
import org.killbill.billing.plugin.core.resources.jooby.PluginApp;
import org.killbill.billing.plugin.core.resources.jooby.PluginAppBuilder;
import org.killbill.billing.plugin.runtime.PluginBase;
import org.killbill.billing.runtime.api.Healthcheck;

/**
 * The reference plugin, migrated from OSGi to LPR on top of the plugin framework.
 * <p>
 * Worth reading beside the {@code HelloWorldActivator} it replaces, because the diff is the whole
 * argument for the migration:
 * <ul>
 *   <li>{@code start(BundleContext)} / {@code stop(BundleContext)} become {@code startPlugin()} /
 *       {@code stopPlugin()}, with no framework handle to pass around and no {@code super} call to
 *       forget.</li>
 *   <li>Five {@code registerXxx} helpers, each building a {@code Hashtable} of properties and
 *       calling {@code registrar.registerService(context, ...)}, collapse into
 *       {@code registerService(Type.class, impl)}. The registration name defaults to the plugin id
 *       rather than having to be set on every call.</li>
 *   <li>The {@code ServiceTracker} for {@code InvoiceFormatterFactory} -- opened here, closed in
 *       {@code stop}, and consulted on each use because the service could vanish -- becomes an
 *       ordinary lookup in the service registry.</li>
 *   <li>{@code dispatcher.registerEventHandlers(...)}, wrapped in an
 *       {@code OSGIFrameworkEventHandler} callback so that registration happened only once the
 *       framework had finished starting, becomes registering a {@code NotificationPluginApi} like
 *       any other service. That indirection existed because OSGi had no registry for this one API.</li>
 * </ul>
 * The nine classes doing the actual work -- payment, invoice, servlets, healthcheck, metrics --
 * compile unchanged apart from {@code javax.servlet} becoming {@code jakarta.servlet}.
 */
public class HelloWorldJavaPlugin extends PluginBase {

    /**
     * The name Kill Bill looks this plugin up by.
     * <p>
     * Kept as a constant because the payment and invoice code references it, but it no longer has to
     * be passed to every registration: {@code registerService} defaults to the plugin id, and this
     * plugin's descriptor declares the same value.
     */
    public static final String PLUGIN_NAME = "hello-world-plugin";

    private MetricsGeneratorExample metricsGenerator;

    @Override
    protected void startPlugin() throws Exception {
        final String region = PluginEnvironmentConfig.getRegion(configProperties.getProperties());

        final HelloWorldConfigurationHandler configurationHandler =
                new HelloWorldConfigurationHandler(region, PLUGIN_NAME, killbillApi);
        final Properties globalConfiguration =
                configurationHandler.createConfigurable(configProperties.getProperties());
        configurationHandler.setDefaultConfigurable(globalConfiguration);

        // Whatever plugin is publishing an InvoiceFormatterFactory right now, or nothing. The OSGi
        // version kept a ServiceTracker open for this; the registry answers the same question.
        final InvoiceFormatterFactory invoiceFormatter =
                context.services().getService(InvoiceFormatterFactory.class, PLUGIN_NAME).orElse(null);

        registerService(PaymentPluginApi.class, new HelloWorldPaymentPluginApi());

        final Healthcheck healthcheck = new HelloWorldHealthcheck();
        registerService(Healthcheck.class, healthcheck);

        registerService(InvoicePluginApi.class,
                        new HelloWorldInvoicePluginApi(killbillApi, configProperties, clock));

        final PluginApp pluginApp = new PluginAppBuilder(PLUGIN_NAME, killbillApi, dataSource(), clock, configProperties)
                .withRouteClass(HelloWorldServlet.class)
                .withRouteClass(HelloWorldHealthcheckServlet.class)
                .withService(healthcheck)
                .build();
        final HttpServlet httpServlet = PluginApp.createServlet(pluginApp);
        registerService(Servlet.class, httpServlet);

        // Both handlers are NotificationPluginApi implementations now, so they are published like
        // any other service rather than handed to a dispatcher. Registering under distinct names
        // because a registry holds one implementation per name.
        registerService(NotificationPluginApi.class,
                        new PluginConfigurationEventHandler(configurationHandler),
                        PLUGIN_NAME + "-config");
        registerService(NotificationPluginApi.class,
                        new HelloWorldListener(killbillApi, invoiceFormatter,
                                               configProperties.getProperties()),
                        PLUGIN_NAME);

        metricsGenerator = new MetricsGeneratorExample(metricRegistry());
        metricsGenerator.start();
    }

    @Override
    protected void stopPlugin() throws Exception {
        if (metricsGenerator != null) {
            metricsGenerator.stop();
        }
    }
}

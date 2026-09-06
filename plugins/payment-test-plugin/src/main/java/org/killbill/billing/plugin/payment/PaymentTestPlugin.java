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

package org.killbill.billing.plugin.payment;

import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServlet;
import org.killbill.billing.payment.plugin.api.PaymentPluginApi;
import org.killbill.billing.plugin.core.resources.jooby.PluginApp;
import org.killbill.billing.plugin.core.resources.jooby.PluginAppBuilder;
import org.killbill.billing.plugin.payment.dao.PaymentTestDao;
import org.killbill.billing.plugin.payment.resources.PaymentTestResource;
import org.killbill.billing.plugin.runtime.PluginBase;

/**
 * Migrated from PaymentTestPlugin.
 * <p>
 * The framework handle, the {@code Hashtable} of registration properties and the per-type
 * {@code registerXxx} helpers are gone; what remains is the plugin's own startup order.
 */
public class PaymentTestPlugin extends PluginBase {

    public static final String PLUGIN_NAME = "killbill-payment-test";



    @Override
    protected void startPlugin() throws Exception {
        

        final PaymentTestDao paymentTestDao = new PaymentTestDao(dataSource());
        final TestingStates testingStates = new TestingStates();


        final PaymentTestPluginApi pluginApi = new PaymentTestPluginApi(killbillApi,
                                                                        configProperties,
                                                                        clock,
                                                                        paymentTestDao,
                                                                        testingStates);
        registerService(PaymentPluginApi.class, pluginApi);

        final PluginApp pluginApp = new PluginAppBuilder(PLUGIN_NAME,
                                                         killbillApi,
                                                         dataSource(),
                                                         clock,
                                                         configProperties).withRouteClass(PaymentTestResource.class)
                                                                               .withService(testingStates)
                                                                               .withService(killbillApi)
                                                                               .withService(killbillApi)
                                                                               .withService(clock)
                                                                               .build();

        final HttpServlet httpServlet = PluginApp.createServlet(pluginApp);
        registerService(Servlet.class, httpServlet);
    }
}

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

package org.killbill.billing.plugin.entitlement.coupon.demo;

import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServlet;
import org.killbill.billing.entitlement.plugin.api.EntitlementPluginApi;
import org.killbill.billing.plugin.runtime.PluginBase;

/**
 * Migrated from CouponPlugin.
 * <p>
 * The framework handle, the {@code Hashtable} of registration properties and the per-type
 * {@code registerXxx} helpers are gone; what remains is the plugin's own startup order.
 */
public class CouponPlugin extends PluginBase {

    public static final String PLUGIN_NAME = "killbill-coupon-demo";



    @Override
    protected void startPlugin() throws Exception {
        

        logService.log(LogService.LOG_INFO, "Starting " + PLUGIN_NAME);

        final EntitlementPluginApi entitlementPluginApi = new CouponDemoEntitlementPluginApi(clock, killbillApi);
        registerService(EntitlementPluginApi.class, entitlementPluginApi);

        // Register a servlet (optional)
        final CouponDemoServlet analyticsServlet = new CouponDemoServlet(logService);
        registerService(Servlet.class, analyticsServlet);
    }

    @Override
    protected void stopPlugin() throws Exception {
        // Do additional work on shutdown (optional)
    }
}

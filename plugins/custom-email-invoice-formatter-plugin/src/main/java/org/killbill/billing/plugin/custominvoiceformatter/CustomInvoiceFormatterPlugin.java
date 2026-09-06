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

package org.killbill.billing.plugin.custominvoiceformatter;

import org.killbill.billing.plugin.notification.api.InvoiceFormatterFactory;
import org.killbill.billing.plugin.runtime.PluginBase;

/**
 * Supplies a custom {@code InvoiceFormatterFactory} for the email-notifications plugin to render
 * invoices with.
 * <p>
 * Note the interface: it is the one declared by the email-notifications plugin, not Kill Bill's own
 * {@code InvoiceFormatterFactory}. Both sides therefore load it from the same place -- the plugin
 * that declares it -- which is why that jar has to be a compile dependency here rather than
 * something each plugin ships its own copy of.
 * <p>
 * The smallest migration in this repository, and a clean illustration of the shape change: the OSGi
 * version called {@code context.registerService(...)} directly and kept the returned
 * {@code ServiceRegistration} in a field so that {@code stop()} could unregister it. Registrations
 * are scoped to the plugin now, so the runtime withdraws them when it stops -- there is nothing
 * left to hold or to undo.
 */
public class CustomInvoiceFormatterPlugin extends PluginBase {

    public static final String PLUGIN_NAME = "custom-email-invoice-formatter-plugin";

    @Override
    protected void startPlugin() throws Exception {
        registerService(InvoiceFormatterFactory.class, new CustomInvoiceFormatterFactory());
    }
}

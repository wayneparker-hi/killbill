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

package org.killbill.billing.plugin.notification.generator;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import org.killbill.billing.account.api.AccountData;
import org.killbill.billing.invoice.api.Invoice;
import org.killbill.billing.invoice.api.formatters.InvoiceFormatter;
import org.killbill.billing.plugin.notification.generator.formatters.DefaultInvoiceFormatter;
import org.killbill.billing.runtime.api.NotificationInvoiceFormatterFactory;
import org.killbill.billing.util.callcontext.TenantContext;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class TestDynamicInvoiceFormatter {
    @Test(groups = "fast")
    public void lateRegistrationAndRemovalAreObservedPerRender() throws Exception {
        final AtomicReference<NotificationInvoiceFormatterFactory> selected = new AtomicReference<>();
        final TemplateRenderer renderer = new TemplateRenderer((text, data) -> data.get("invoice").getClass().getName(),
                new ResourceBundleFactory(null), null);
        renderer.setInvoiceFormatterSupplier(selected::get);
        final AccountData account = (AccountData) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {AccountData.class},
                (proxy, method, args) -> method.getName().equals("getLocale") ? "en_US" : null);
        final TenantContext context = emptyProxy(TenantContext.class);
        final Invoice invoice = emptyProxy(Invoice.class);
        assertEquals(renderer.generateEmailForInvoiceCreation(account, invoice, context).getBody(), DefaultInvoiceFormatter.class.getName());
        final InvoiceFormatter custom = emptyProxy(InvoiceFormatter.class);
        selected.set((translations, value, locale, tenant) -> custom);
        assertEquals(renderer.generateEmailForInvoiceCreation(account, invoice, context).getBody(), custom.getClass().getName());
        selected.set(null);
        assertEquals(renderer.generateEmailForInvoiceCreation(account, invoice, context).getBody(), DefaultInvoiceFormatter.class.getName());
    }

    private <T> T emptyProxy(final Class<T> type) {
        return type.cast(Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> null));
    }
}

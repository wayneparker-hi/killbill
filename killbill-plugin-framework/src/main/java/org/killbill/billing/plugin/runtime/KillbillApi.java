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

package org.killbill.billing.plugin.runtime;

import java.util.Objects;

import org.killbill.billing.lpr.api.PluginContext;
import org.killbill.billing.account.api.AccountUserApi;
import org.killbill.billing.catalog.api.CatalogUserApi;
import org.killbill.billing.currency.api.CurrencyConversionApi;
import org.killbill.billing.entitlement.api.EntitlementApi;
import org.killbill.billing.entitlement.api.SubscriptionApi;
import org.killbill.billing.invoice.api.InvoiceUserApi;
import org.killbill.billing.overdue.api.OverdueApi;
import org.killbill.billing.payment.api.AdminPaymentApi;
import org.killbill.billing.payment.api.InvoicePaymentApi;
import org.killbill.billing.payment.api.PaymentApi;
import org.killbill.billing.runtime.api.PluginsInfoApi;
import org.killbill.billing.security.api.SecurityApi;
import org.killbill.billing.tenant.api.TenantUserApi;
import org.killbill.billing.usage.api.UsageUserApi;
import org.killbill.billing.util.api.AuditUserApi;
import org.killbill.billing.util.api.CustomFieldUserApi;
import org.killbill.billing.util.api.ExportUserApi;
import org.killbill.billing.util.api.RecordIdApi;
import org.killbill.billing.util.api.TagUserApi;
import org.killbill.billing.util.nodes.KillbillNodesApi;

/**
 * The Kill Bill APIs, as a plugin sees them.
 * <p>
 * Replaces {@code KillbillApi}. The shape is deliberately identical -- one getter per API --
 * so that plugin code written against the OSGi SDK compiles after changing an import, but there is
 * nothing behind it any more: the OSGi version wrapped every call in a {@code ServiceTracker}
 * lookup with a retry loop, because a service could vanish between calls. The plugin runtime hands
 * the APIs over once, at start, so a getter here is a map lookup.
 * <p>
 * <b>Not every deployment publishes every API.</b> The payment-only profile has no
 * {@code OverdueApi}. Asking for one that is absent throws immediately with the list of what is
 * available, rather than returning null for the caller to trip over later -- which is what
 * {@code PlatformServiceUnavailableException} existed to signal.
 */
public class KillbillApi {

    private final PluginContext context;

    public KillbillApi(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public AccountUserApi getAccountUserApi() {
        return require(AccountUserApi.class);
    }

    public AdminPaymentApi getAdminPaymentApi() {
        return require(AdminPaymentApi.class);
    }

    public AuditUserApi getAuditUserApi() {
        return require(AuditUserApi.class);
    }

    public CatalogUserApi getCatalogUserApi() {
        return require(CatalogUserApi.class);
    }

    public CurrencyConversionApi getCurrencyConversionApi() {
        return require(CurrencyConversionApi.class);
    }

    public CustomFieldUserApi getCustomFieldUserApi() {
        return require(CustomFieldUserApi.class);
    }

    public EntitlementApi getEntitlementApi() {
        return require(EntitlementApi.class);
    }

    public ExportUserApi getExportUserApi() {
        return require(ExportUserApi.class);
    }

    public InvoicePaymentApi getInvoicePaymentApi() {
        return require(InvoicePaymentApi.class);
    }

    public InvoiceUserApi getInvoiceUserApi() {
        return require(InvoiceUserApi.class);
    }

    public KillbillNodesApi getKillbillNodesApi() {
        return require(KillbillNodesApi.class);
    }

    public OverdueApi getOverdueApi() {
        return require(OverdueApi.class);
    }

    public PaymentApi getPaymentApi() {
        return require(PaymentApi.class);
    }

    public PluginsInfoApi getPluginsInfoApi() {
        return require(PluginsInfoApi.class);
    }

    public RecordIdApi getRecordIdApi() {
        return require(RecordIdApi.class);
    }

    public SecurityApi getSecurityApi() {
        return require(SecurityApi.class);
    }

    public SubscriptionApi getSubscriptionApi() {
        return require(SubscriptionApi.class);
    }

    public TagUserApi getTagUserApi() {
        return require(TagUserApi.class);
    }

    public TenantUserApi getTenantUserApi() {
        return require(TenantUserApi.class);
    }

    public UsageUserApi getUsageUserApi() {
        return require(UsageUserApi.class);
    }

    /**
     * Any platform service, including ones without a getter above.
     * <p>
     * The escape hatch that keeps this class from having to grow every time the platform publishes
     * something new.
     *
     * @param type the service interface
     * @param <T>  its type
     * @return the implementation
     * @throws IllegalArgumentException if this deployment does not publish it
     */
    public <T> T get(final Class<T> type) {
        return require(type);
    }

    private <T> T require(final Class<T> type) {
        try {
            return context.getPlatformService(type);
        } catch (final IllegalArgumentException e) {
            // Translated so that plugin code can catch one exception type for "this deployment does
            // not have that API", rather than an IllegalArgumentException that could mean anything.
            throw new PlatformServiceUnavailableException(e.getMessage(), e);
        }
    }
}

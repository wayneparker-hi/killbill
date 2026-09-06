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

package org.killbill.billing.platform.plugin.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

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
import org.killbill.billing.platform.api.KillbillConfigSource;
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
import org.killbill.clock.Clock;
import org.killbill.commons.metrics.api.MetricRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Injector;
import com.google.inject.Key;

/**
 * What the platform offers plugins, reachable through {@code PluginContext.getPlatformService}.
 * <p>
 * The OSGi layer published the same set, but as one {@code OSGIKillbill} facade with twenty-one
 * getters. Publishing the APIs individually means a plugin depends on the two or three interfaces it
 * actually calls rather than on a type that transitively names every API in Kill Bill -- and adding
 * an API later does not change a type that every existing plugin was compiled against.
 * <p>
 * <b>Why bindings are looked up rather than injected.</b> Not every deployment binds every API: the
 * payment-only profile has no {@code OverdueApi}, and a platform-level test has almost none.
 * Injecting them as constructor parameters would make this class -- and therefore the whole plugin
 * runtime -- refuse to start in those deployments, which is exactly the coupling the OSGi module had
 * (installing it obliged you to bind all twenty-one). Asking the injector for bindings that already
 * exist keeps the list declarative while letting a deployment publish only what it has.
 */
final class PlatformServices {

    private static final Logger log = LoggerFactory.getLogger(PlatformServices.class);

    /**
     * The published set, in the order it is reported.
     * <p>
     * Adding a capability for plugins means adding a line here and nothing else. Types absent from
     * a given deployment are skipped, so a line is safe to add before every profile binds it.
     */
    private static final List<Class<?>> PUBLISHED = List.of(
            // Business APIs -- the OSGIKillbill facade, unbundled.
            AccountUserApi.class,
            AdminPaymentApi.class,
            AuditUserApi.class,
            CatalogUserApi.class,
            CurrencyConversionApi.class,
            CustomFieldUserApi.class,
            EntitlementApi.class,
            ExportUserApi.class,
            InvoicePaymentApi.class,
            InvoiceUserApi.class,
            KillbillNodesApi.class,
            OverdueApi.class,
            PaymentApi.class,
            PluginsInfoApi.class,
            RecordIdApi.class,
            SecurityApi.class,
            SubscriptionApi.class,
            TagUserApi.class,
            TenantUserApi.class,
            UsageUserApi.class,

            // Platform capabilities. OSGi published these too, alongside the facade.
            Clock.class,
            DataSource.class,
            KillbillConfigSource.class,
            MetricRegistry.class);

    private PlatformServices() {
    }

    /**
     * Resolves the published set against an injector.
     * <p>
     * Uses {@code getExistingBinding} rather than {@code getInstance}: it returns null for an
     * unbound type instead of creating a just-in-time binding, so asking about an API a deployment
     * does not have stays a question rather than becoming a side effect.
     *
     * @param injector the platform injector
     * @return the services to publish, keyed by the interface plugins ask for
     */
    static Map<Class<?>, Object> resolve(final Injector injector) {
        final Map<Class<?>, Object> resolved = new LinkedHashMap<>();
        for (final Class<?> type : PUBLISHED) {
            final var binding = injector.getExistingBinding(Key.get(type));
            if (binding == null) {
                continue;
            }
            try {
                final Object instance = binding.getProvider().get();
                if (instance != null) {
                    resolved.put(type, instance);
                }
            } catch (final RuntimeException e) {
                // A binding that exists but cannot be instantiated must not take the whole runtime
                // down: plugins that do not ask for this API are unaffected, and the one that does
                // gets a clear error from getPlatformService instead.
                log.warn("Not publishing {} to plugins: its binding could not be resolved", type.getName(), e);
            }
        }
        log.info("Publishing {} platform services to plugins: {}",
                 resolved.size(),
                 resolved.keySet().stream().map(Class::getSimpleName).sorted().toList());
        return Map.copyOf(resolved);
    }
}

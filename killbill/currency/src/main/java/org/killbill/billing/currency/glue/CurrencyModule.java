/*
 * Copyright 2010-2013 Ning, Inc.
 * Copyright 2014 Groupon, Inc
 * Copyright 2014 The Billing Project, LLC
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

package org.killbill.billing.currency.glue;

import org.killbill.billing.currency.DefaultCurrencyService;
import org.killbill.billing.currency.api.CurrencyConversionApi;
import org.killbill.billing.currency.api.CurrencyService;
import org.killbill.billing.currency.api.DefaultCurrencyConversionApi;
import org.killbill.billing.currency.plugin.api.CurrencyPluginApi;
import org.killbill.billing.platform.plugin.api.PluginServiceRegistry;
import org.killbill.billing.platform.api.KillbillConfigSource;
import org.killbill.billing.util.config.definition.CurrencyConfig;
import org.killbill.billing.util.glue.KillBillModule;
import org.skife.config.AugmentedConfigurationObjectFactory;

import com.google.inject.TypeLiteral;
import com.google.inject.multibindings.Multibinder;

public class CurrencyModule extends KillBillModule {

    public CurrencyModule(final KillbillConfigSource configSource) {
        super(configSource);
    }

    @Override
    protected void configure() {
        final AugmentedConfigurationObjectFactory factory = new AugmentedConfigurationObjectFactory(skifeConfigSource);
        final CurrencyConfig currencyConfig = factory.build(CurrencyConfig.class);
        bind(CurrencyConfig.class).toInstance(currencyConfig);

        bind(new TypeLiteral<PluginServiceRegistry<CurrencyPluginApi>>() {}).toProvider(DefaultCurrencyProviderPluginRegistryProvider.class).asEagerSingleton();

        // Contribute to the set the plugin runtime's service bridge consumes, so it can route a
        // plugin's registration to the right typed registry. Guice matches TypeLiterals
        // structurally, so this literal and the runtime's refer to the same binding without a
        // shared constant -- which is what keeps the business modules independent of the runtime.
        Multibinder.newSetBinder(binder(), new TypeLiteral<PluginServiceRegistry<?>>() {})
                   .addBinding().to(new TypeLiteral<PluginServiceRegistry<CurrencyPluginApi>>() {});

        bind(CurrencyConversionApi.class).to(DefaultCurrencyConversionApi.class).asEagerSingleton();
        bind(CurrencyService.class).to(DefaultCurrencyService.class).asEagerSingleton();
    }
}

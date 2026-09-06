/*
 * Copyright 2010-2014 Ning, Inc.
 * Copyright 2014-2020 Groupon, Inc
 * Copyright 2020-2023 Equinix, Inc
 * Copyright 2014-2023 The Billing Project, LLC
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

package org.killbill.billing.plugin.currency;

import java.util.Properties;

import org.killbill.billing.plugin.runtime.KillbillApi;
import org.killbill.billing.plugin.api.notification.PluginTenantConfigurableConfigurationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CurrencyConfigurationHandler extends PluginTenantConfigurableConfigurationHandler<Properties> {

    private static final Logger logger = LoggerFactory.getLogger(CurrencyConfigurationHandler.class);

    private final String region;

    public CurrencyConfigurationHandler(final String region,
                                        final String pluginName,
                                        final KillbillApi killbillApi) {
        super(pluginName, killbillApi);
        this.region = region;
    }

    @Override
    protected Properties createConfigurable(final Properties properties) {
        logger.info("New properties for region {}: {}", region, properties);
        return properties;
    }
}

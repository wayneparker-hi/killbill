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

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.joda.time.DateTime;
import org.jooby.MediaType;
import org.jooby.Result;
import org.jooby.Results;
import org.jooby.Status;
import org.jooby.mvc.Body;
import org.jooby.mvc.GET;
import org.jooby.mvc.Local;
import org.jooby.mvc.POST;
import org.jooby.mvc.Path;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.currency.api.Rate;
import org.killbill.clock.Clock;
import org.killbill.billing.plugin.currency.dao.CurrencyDao;
import org.killbill.billing.tenant.api.Tenant;
import org.killbill.billing.util.entity.Entity;
import org.killbill.clock.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

@Singleton
@Path("/rates")
public class CurrencyServlet {

    private static final Logger logger = LoggerFactory.getLogger(CurrencyServlet.class);

    private final CurrencyDao dao;
    private final StaticCurrencyPluginApi api;
    private final Clock clock;

    @Inject
    public CurrencyServlet(final CurrencyDao dao,
                           final StaticCurrencyPluginApi api,
                           final Clock clock) {
        this.dao = dao;
        this.api = api;
        this.clock = clock;
    }

    @POST
    public Result addCurrencyRates(@Body final CurrencyRatesJson currencyRatesJson,
                                   @Local @Named("killbill_tenant") final Optional<Tenant> tenant) throws SQLException {
        dao.addCurrencyRates(currencyRatesJson.baseCurrency,
                             currencyRatesJson.conversionDate,
                             currencyRatesJson.rates,
                             clock.getUTCNow(),
                             tenant.map(Entity::getId).orElse(null));
        return Results.with(Status.CREATED).header("location", "/plugins/killbill-currency/rates?baseCurrency=" + currencyRatesJson.baseCurrency);
    }

    @GET
    public Result getCurrencyRates(@Named("baseCurrency") final Currency baseCurrency,
                                   @Named("conversionDate") final Optional<DateTime> conversionDate,
                                   @Local @Named("killbill_tenant") final Optional<Tenant> tenant) throws SQLException {
        final Set<Rate> currencyRates;
        if (conversionDate.isPresent()) {
            currencyRates = api.getRates(baseCurrency, conversionDate.get());
        } else {
            currencyRates = api.getCurrentRates(baseCurrency);
        }
        return Results.ok(currencyRates).type(MediaType.json);
    }

    private static final class CurrencyRatesJson {

        public String baseCurrency;
        public DateTime conversionDate;
        public Map<String, BigDecimal> rates;

        @JsonCreator
        public CurrencyRatesJson(@JsonProperty("baseCurrency") final String baseCurrency,
                                 @JsonProperty("conversionDate") final DateTime conversionDate,
                                 @JsonProperty("rates") final Map<String, BigDecimal> rates) {
            this.baseCurrency = baseCurrency;
            this.conversionDate = conversionDate;
            this.rates = rates;
        }

        @Override
        public String toString() {
            return "CurrencyRatesJson{" +
                   "baseCurrency='" + baseCurrency + '\'' +
                   ", conversionDate=" + conversionDate +
                   ", rates=" + rates +
                   '}';
        }
    }
}

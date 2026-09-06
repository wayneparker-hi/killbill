/*
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

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.SortedSet;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.jooq.Result;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.currency.api.Rate;
import org.killbill.billing.currency.api.boilerplate.RateImp;
import org.killbill.billing.currency.plugin.api.CurrencyPluginApi;
import org.killbill.billing.plugin.currency.dao.CurrencyDao;
import org.killbill.billing.plugin.currency.dao.gen.tables.records.CurrencyRatesRecord;
import org.killbill.billing.plugin.currency.dao.gen.tables.records.CurrencyUpdatesRecord;

public class StaticCurrencyPluginApi implements CurrencyPluginApi {

    private final CurrencyDao currencyDao;

    public StaticCurrencyPluginApi(final CurrencyDao currencyDao) {
        this.currencyDao = currencyDao;
    }

    @Override
    public Set<Currency> getBaseCurrencies() {
        try {
            return currencyDao.getDistinctBaseCurrencies(null)
                              .stream()
                              .map(Currency::valueOf)
                              .collect(Collectors.toUnmodifiableSet());
        } catch (final SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public DateTime getLatestConversionDate(final Currency baseCurrency) {
        try {
            final CurrencyUpdatesRecord latestUpdateForBaseCurrency = currencyDao.getLatestUpdateForBaseCurrency(String.valueOf(baseCurrency), null);
            if (latestUpdateForBaseCurrency == null) {
                return null;
            }

            return toDateTime(latestUpdateForBaseCurrency.getConversionDate());
        } catch (final SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public SortedSet<DateTime> getConversionDates(final Currency baseCurrency) {
        try {
            return currencyDao.getHistoricalUpdatesForBaseCurrency(String.valueOf(baseCurrency), null)
                              .stream()
                              .map(currencyUpdatesRecord -> toDateTime(currencyUpdatesRecord.getConversionDate()))
                              .collect(Collectors.toCollection(TreeSet::new));
        } catch (final SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Set<Rate> getCurrentRates(final Currency baseCurrency) {
        try {
            final CurrencyUpdatesRecord latestUpdateForBaseCurrency = currencyDao.getLatestUpdateForBaseCurrency(String.valueOf(baseCurrency), null);
            if (latestUpdateForBaseCurrency == null) {
                return Set.of();
            }

            return getRatesForCurrencyUpdate(baseCurrency, latestUpdateForBaseCurrency);
        } catch (final SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Set<Rate> getRates(final Currency baseCurrency, final DateTime conversionDate) {
        try {
            final Result<CurrencyUpdatesRecord> historicalUpdatesForBaseCurrency = currencyDao.getHistoricalUpdatesForBaseCurrency(String.valueOf(baseCurrency), null);
            if (historicalUpdatesForBaseCurrency == null) {
                return Set.of();
            }

            for (final CurrencyUpdatesRecord currencyUpdatesRecord : historicalUpdatesForBaseCurrency) {
                // Ordered desc
                if (!toDateTime(currencyUpdatesRecord.getConversionDate()).isAfter(conversionDate)) {
                    return getRatesForCurrencyUpdate(baseCurrency, currencyUpdatesRecord);
                }
            }

            return Set.of();
        } catch (final SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Set<Rate> getRatesForCurrencyUpdate(final Currency baseCurrency, final CurrencyUpdatesRecord currencyUpdatesRecord) throws SQLException {
        return currencyDao.getRatesForCurrencyUpdate(currencyUpdatesRecord.getRecordId().intValue(), null)
                          .stream()
                          .map(new Function<CurrencyRatesRecord, Rate>() {
                              @Override
                              public Rate apply(final CurrencyRatesRecord currencyRatesRecord) {
                                  return new RateImp.Builder().withBaseCurrency(baseCurrency)
                                                              .withCurrency(Currency.valueOf(currencyRatesRecord.getTargetCurrency()))
                                                              .withValue(currencyRatesRecord.getRate())
                                                              .withConversionDate(toDateTime(currencyUpdatesRecord.getConversionDate()))
                                                              .build();
                              }
                          })
                          .collect(Collectors.toUnmodifiableSet());
    }

    private static DateTime toDateTime(final LocalDateTime conversionDate) {
        final ZonedDateTime zonedDateTime = conversionDate.atZone(ZoneOffset.UTC);
        return new DateTime(zonedDateTime.toInstant().toEpochMilli(), DateTimeZone.forTimeZone(TimeZone.getTimeZone(zonedDateTime.getZone())));
    }
}

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

package org.killbill.billing.plugin.currency.dao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;
import javax.sql.DataSource;

import org.joda.time.DateTime;
import org.jooq.Configuration;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.Result;
import org.jooq.SelectConditionStep;
import org.jooq.TransactionalRunnable;
import org.jooq.impl.DSL;
import org.killbill.billing.plugin.currency.dao.gen.tables.CurrencyRates;
import org.killbill.billing.plugin.currency.dao.gen.tables.CurrencyUpdates;
import org.killbill.billing.plugin.currency.dao.gen.tables.records.CurrencyRatesRecord;
import org.killbill.billing.plugin.currency.dao.gen.tables.records.CurrencyUpdatesRecord;
import org.killbill.billing.plugin.dao.PluginDao;

public class CurrencyDao extends PluginDao {

    public CurrencyDao(final DataSource dataSource) throws SQLException {
        super(dataSource);
    }

    public void addCurrencyRates(final String baseCurrency,
                                 final DateTime conversionDate,
                                 final Map<String, BigDecimal> targetCurrencyToRate,
                                 final DateTime utcNow,
                                 final UUID kbTenantId) throws SQLException {
        execute(dataSource.getConnection(),
                new WithConnectionCallback<Void>() {
                    @Override
                    public Void withConnection(final Connection conn) {
                        DSL.using(conn, dialect, settings)
                           .transaction(new TransactionalRunnable() {
                               @Override
                               public void run(final Configuration configuration) {
                                   final DSLContext dslContext = DSL.using(configuration);

                                   dslContext.insertInto(CurrencyUpdates.CURRENCY_UPDATES,
                                                         CurrencyUpdates.CURRENCY_UPDATES.BASE_CURRENCY,
                                                         CurrencyUpdates.CURRENCY_UPDATES.CONVERSION_DATE,
                                                         CurrencyUpdates.CURRENCY_UPDATES.CREATED_AT,
                                                         CurrencyUpdates.CURRENCY_UPDATES.UPDATED_AT,
                                                         CurrencyUpdates.CURRENCY_UPDATES.KB_TENANT_ID)
                                             .values(baseCurrency,
                                                     toLocalDateTime(conversionDate),
                                                     toLocalDateTime(utcNow),
                                                     toLocalDateTime(utcNow),
                                                     kbTenantId == null ? null : kbTenantId.toString())
                                             .execute();
                                   final Integer currencyUpdateId = dslContext.lastID().intValue();
                                   targetCurrencyToRate.forEach((key, value) -> dslContext.insertInto(CurrencyRates.CURRENCY_RATES,
                                                                                                      CurrencyRates.CURRENCY_RATES.TARGET_CURRENCY,
                                                                                                      CurrencyRates.CURRENCY_RATES.RATE,
                                                                                                      CurrencyRates.CURRENCY_RATES.CURRENCY_UPDATE_RECORD_ID,
                                                                                                      CurrencyRates.CURRENCY_RATES.CREATED_AT,
                                                                                                      CurrencyRates.CURRENCY_RATES.UPDATED_AT,
                                                                                                      CurrencyRates.CURRENCY_RATES.KB_TENANT_ID)
                                                                                          .values(key,
                                                                                                  value,
                                                                                                  currencyUpdateId,
                                                                                                  toLocalDateTime(utcNow),
                                                                                                  toLocalDateTime(utcNow),
                                                                                                  kbTenantId == null ? null : kbTenantId.toString())
                                                                                          .execute());
                               }
                           });

                        return null;
                    }
                });
    }

    public Set<String> getDistinctBaseCurrencies(@Nullable final UUID kbTenantId) throws SQLException {
        return execute(dataSource.getConnection(),
                       new WithConnectionCallback<>() {
                           @Override
                           public Set<String> withConnection(final Connection conn) {
                               SelectConditionStep<Record1<String>> step = DSL.using(conn, dialect, settings)
                                                                              .selectDistinct(CurrencyUpdates.CURRENCY_UPDATES.BASE_CURRENCY)
                                                                              .from(CurrencyUpdates.CURRENCY_UPDATES)
                                                                              .where();
                               if (kbTenantId != null) {
                                   step = step.and(CurrencyUpdates.CURRENCY_UPDATES.KB_TENANT_ID.equal(kbTenantId.toString()));

                               }
                               return step.orderBy(CurrencyUpdates.CURRENCY_UPDATES.BASE_CURRENCY.asc())
                                          .fetchSet(CurrencyUpdates.CURRENCY_UPDATES.BASE_CURRENCY);
                           }
                       });
    }

    public CurrencyUpdatesRecord getLatestUpdateForBaseCurrency(final String baseCurrency, @Nullable final UUID kbTenantId) throws SQLException {
        // Nit: could be optimized with a limit(1) below
        return getHistoricalUpdatesForBaseCurrency(baseCurrency, kbTenantId).stream().findFirst().orElse(null);
    }

    public Result<CurrencyUpdatesRecord> getHistoricalUpdatesForBaseCurrency(final String baseCurrency, @Nullable final UUID kbTenantId) throws SQLException {
        return execute(dataSource.getConnection(),
                       new WithConnectionCallback<>() {
                           @Override
                           public Result<CurrencyUpdatesRecord> withConnection(final Connection conn) {
                               SelectConditionStep<CurrencyUpdatesRecord> step = DSL.using(conn, dialect, settings)
                                                                                    .selectFrom(CurrencyUpdates.CURRENCY_UPDATES)
                                                                                    .where(CurrencyUpdates.CURRENCY_UPDATES.BASE_CURRENCY.equal(baseCurrency));
                               if (kbTenantId != null) {
                                   step = step.and(CurrencyUpdates.CURRENCY_UPDATES.KB_TENANT_ID.equal(kbTenantId.toString()));
                               }
                               return step.orderBy(CurrencyUpdates.CURRENCY_UPDATES.CONVERSION_DATE.desc())
                                          .fetch();
                           }
                       });
    }

    public Result<CurrencyRatesRecord> getRatesForCurrencyUpdate(final int currencyUpdateId, @Nullable final UUID kbTenantId) throws SQLException {
        return execute(dataSource.getConnection(),
                       new WithConnectionCallback<>() {
                           @Override
                           public Result<CurrencyRatesRecord> withConnection(final Connection conn) {
                               SelectConditionStep<CurrencyRatesRecord> step = DSL.using(conn, dialect, settings)
                                                                                  .selectFrom(CurrencyRates.CURRENCY_RATES)
                                                                                  .where(CurrencyRates.CURRENCY_RATES.CURRENCY_UPDATE_RECORD_ID.equal(currencyUpdateId));
                               if (kbTenantId != null) {
                                   step = step.and(CurrencyRates.CURRENCY_RATES.KB_TENANT_ID.equal(kbTenantId.toString()));
                               }
                               return step.orderBy(CurrencyRates.CURRENCY_RATES.TARGET_CURRENCY.asc())
                                          .fetch();
                           }
                       });
    }
}

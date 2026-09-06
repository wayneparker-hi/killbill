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

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.joda.time.DateTime;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.currency.api.Rate;
import org.killbill.billing.plugin.currency.dao.CurrencyDao;
import org.killbill.clock.ClockMock;
import org.testng.Assert;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Test;

public class TestStaticCurrencyPluginApi {

    private final UUID kbTenantId = null;

    private CurrencyDao dao;
    private StaticCurrencyPluginApi api;
    private ClockMock clock;

    @BeforeMethod(groups = "slow")
    public void setUp() throws Exception {
        EmbeddedDbHelper.instance().resetDB();
        dao = EmbeddedDbHelper.instance().getCurrencyDao();
        api = new StaticCurrencyPluginApi(dao);

        clock = new ClockMock();
    }

    @BeforeSuite(groups = "slow")
    public void setUpBeforeSuite() throws Exception {
        EmbeddedDbHelper.instance().startDb();
    }

    @AfterSuite(groups = "slow")
    public void tearDownAfterSuite() throws Exception {
        EmbeddedDbHelper.instance().stopDB();
    }

    @Test(groups = "slow")
    public void testBasic() throws SQLException {
        final DateTime d1 = new DateTime("2013-10-10T20:41:09Z");
        final DateTime d2 = new DateTime("2013-10-11T20:41:09Z");

        Assert.assertEquals(api.getBaseCurrencies().size(), 0);
        Assert.assertNull(api.getLatestConversionDate(Currency.USD));
        Assert.assertEquals(api.getConversionDates(Currency.USD).size(), 0);
        Assert.assertEquals(api.getCurrentRates(Currency.USD).size(), 0);
        Assert.assertEquals(api.getRates(Currency.USD, d1).size(), 0);
        Assert.assertEquals(api.getRates(Currency.USD, d2).size(), 0);

        dao.addCurrencyRates("USD",
                             d1,
                             Map.of("BRL", new BigDecimal("0.45721"),
                                    "EUR", new BigDecimal("1.38045"),
                                    "GBP", new BigDecimal("1.61650")),
                             clock.getUTCNow(),
                             kbTenantId);

        Assert.assertEquals(api.getBaseCurrencies().size(), 1);
        Assert.assertEquals(api.getBaseCurrencies().stream().findFirst().get(), Currency.USD);
        Assert.assertEquals(api.getLatestConversionDate(Currency.USD).compareTo(d1), 0);
        Assert.assertEquals(api.getConversionDates(Currency.USD).size(), 1);
        Assert.assertEquals(api.getConversionDates(Currency.USD).stream().findFirst().get().compareTo(d1), 0);
        Assert.assertEquals(api.getCurrentRates(Currency.USD).size(), 3);
        final Set<Rate> d1Rates = api.getRates(Currency.USD, d1);
        Assert.assertEquals(d1Rates.size(), 3);
        for (final Rate rate : d1Rates) {
            Assert.assertEquals(rate.getBaseCurrency(), Currency.USD);
            Assert.assertEquals(rate.getConversionDate().compareTo(d1), 0);
            switch (rate.getCurrency()) {
                case BRL:
                    Assert.assertEquals(rate.getValue().compareTo(new BigDecimal("0.45721")), 0);
                    break;
                case EUR:
                    Assert.assertEquals(rate.getValue().compareTo(new BigDecimal("1.38045")), 0);
                    break;
                case GBP:
                    Assert.assertEquals(rate.getValue().compareTo(new BigDecimal("1.61650")), 0);
                    break;
                default:
                    Assert.fail(rate.getCurrency().toString());
            }
        }
        Assert.assertEquals(api.getRates(Currency.USD, d2), d1Rates);

        dao.addCurrencyRates("USD",
                             d2,
                             Map.of("BRL", new BigDecimal("0.45731"),
                                    "EUR", new BigDecimal("1.38055"),
                                    "GBP", new BigDecimal("1.61660")),
                             clock.getUTCNow(),
                             kbTenantId);

        Assert.assertEquals(api.getBaseCurrencies().size(), 1);
        Assert.assertEquals(api.getBaseCurrencies().stream().findFirst().get(), Currency.USD);
        Assert.assertEquals(api.getLatestConversionDate(Currency.USD).compareTo(d2), 0);
        final List<DateTime> conversionDates = new ArrayList<>(api.getConversionDates(Currency.USD));
        Assert.assertEquals(conversionDates.size(), 2);
        Assert.assertEquals(conversionDates.get(0).compareTo(d1), 0);
        Assert.assertEquals(conversionDates.get(1).compareTo(d2), 0);
        Assert.assertEquals(api.getCurrentRates(Currency.USD).size(), 3);
        Assert.assertEquals(api.getRates(Currency.USD, d1), d1Rates);
        final Set<Rate> d2Rates = api.getRates(Currency.USD, d2);
        Assert.assertEquals(d2Rates.size(), 3);
        for (final Rate rate : d2Rates) {
            Assert.assertEquals(rate.getBaseCurrency(), Currency.USD);
            Assert.assertEquals(rate.getConversionDate().compareTo(d2), 0);
            switch (rate.getCurrency()) {
                case BRL:
                    Assert.assertEquals(rate.getValue().compareTo(new BigDecimal("0.45731")), 0);
                    break;
                case EUR:
                    Assert.assertEquals(rate.getValue().compareTo(new BigDecimal("1.38055")), 0);
                    break;
                case GBP:
                    Assert.assertEquals(rate.getValue().compareTo(new BigDecimal("1.61660")), 0);
                    break;
                default:
                    Assert.fail(rate.getCurrency().toString());
            }
        }
    }
}
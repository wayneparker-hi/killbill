/*
 * Copyright 2010-2014 Ning, Inc.
 * Copyright 2014-2020 Groupon, Inc
 * Copyright 2020-2022 Equinix, Inc
 * Copyright 2014-2022 The Billing Project, LLC
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

package org.killbill.billing.plugin.analytics;

import java.io.IOException;
import java.util.List;

import javax.sql.DataSource;

import javax.sql.DataSource;
import org.killbill.billing.platform.test.PlatformDBTestingHelper;
import org.killbill.billing.plugin.analytics.api.user.AnalyticsUserApi;
import org.killbill.billing.plugin.analytics.dao.BusinessAnalyticsSqlDao;
import org.killbill.billing.plugin.analytics.dao.BusinessDBIProvider;
import org.killbill.billing.plugin.analytics.dao.model.CurrencyConversionModelDao;
import org.killbill.billing.plugin.analytics.utils.CurrencyConverter;
import org.killbill.bus.dao.BusEventModelDao;
import org.killbill.clock.Clock;
import org.killbill.clock.DefaultClock;
import org.killbill.commons.embeddeddb.EmbeddedDB;
import org.killbill.commons.jdbi.mapper.LowerToCamelBeanMapperFactory;
import org.killbill.notificationq.DefaultNotificationQueueService;
import org.killbill.notificationq.api.NotificationQueueConfig;
import org.killbill.notificationq.dao.NotificationEventModelDao;
import org.mockito.Mockito;
import org.skife.config.AugmentedConfigurationObjectFactory;
import org.skife.jdbi.v2.DBI;
import org.testng.Assert;
import org.testng.ITestContext;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeSuite;

import com.google.common.collect.ImmutableMap;

public abstract class AnalyticsTestSuiteWithEmbeddedDB extends AnalyticsTestSuiteNoDB {

    static {
        System.setProperty("log4jdbc.spylogdelegator.name", "net.sf.log4jdbc.log.slf4j.Slf4jSpyLogDelegator");
    }

    protected final Clock clock = new DefaultClock();
    protected final CurrencyConverter currencyConverter = new CurrencyConverter(clock, "USD", ImmutableMap.<String, List<CurrencyConversionModelDao>>of());

    protected EmbeddedDB embeddedDB;
    protected DBI dbi;
    protected BusinessAnalyticsSqlDao analyticsSqlDao;
    protected AnalyticsUserApi analyticsUserApi;

    @BeforeSuite(groups = "slow")
    public void setUpSuite(final ITestContext context) throws Exception {
        final AnalyticsPlatformDBTestingHelper analyticsPlatformDBTestingHelper = new AnalyticsPlatformDBTestingHelper();
        analyticsPlatformDBTestingHelper.start();

        context.setAttribute("DBTestingHelper", analyticsPlatformDBTestingHelper);
    }

    @BeforeMethod(groups = "slow")
    public void setUp(final ITestContext context) throws Exception {
        embeddedDB = ((PlatformDBTestingHelper) context.getAttribute("DBTestingHelper")).getInstance();

        killbillDataSource = analyticsDataSource();

        embeddedDB.cleanupAllTables();

        dbi = BusinessDBIProvider.get(embeddedDB.getDataSource(), metricRegistry);
        dbi.registerMapper(new LowerToCamelBeanMapperFactory(BusEventModelDao.class));
        dbi.registerMapper(new LowerToCamelBeanMapperFactory(NotificationEventModelDao.class));

        analyticsSqlDao = dbi.onDemand(BusinessAnalyticsSqlDao.class);

        final NotificationQueueConfig config = new AugmentedConfigurationObjectFactory(osgiConfigPropertiesService.getProperties()).buildWithReplacements(NotificationQueueConfig.class,
                                                                                                                                                 ImmutableMap.<String, String>of("instanceName", "analytics"));
        notificationQueueService = new DefaultNotificationQueueService(dbi, clock, config, metricRegistry);

        analyticsUserApi = new AnalyticsUserApi(killbillApi,
                                                killbillDataSource,
                                                metricRegistry,
                                                osgiConfigPropertiesService,
                                                executor,
                                                clock,
                                                analyticsConfigurationHandler,
                                                null);
    }

    @AfterSuite(groups = "slow")
    public void tearDown() throws Exception {
        if (embeddedDB != null) {
            embeddedDB.stop();
        }
    }

    /**
     * Was a subclass of the OSGi DataSource wrapper, built from a mocked BundleContext. The plugin
     * runtime publishes the platform's DataSource directly, so the embedded database is now handed
     * over as-is.
     */
    protected DataSource analyticsDataSource() throws java.io.IOException {
        return embeddedDB.getDataSource();
    }


    private static final class AnalyticsPlatformDBTestingHelper extends PlatformDBTestingHelper {

        public AnalyticsPlatformDBTestingHelper() {
            super();
        }

        @Override
        protected synchronized void executePostStartupScripts() throws IOException {
            this.instance.executeScript("/*! SET GLOBAL SQL_MODE=(SELECT CONCAT(@@SQL_MODE,',ANSI_QUOTES')) */;");
            final String baseResource = "org/killbill/billing/plugin/analytics/";
            executePostStartupScripts(baseResource);
        }
    }
}

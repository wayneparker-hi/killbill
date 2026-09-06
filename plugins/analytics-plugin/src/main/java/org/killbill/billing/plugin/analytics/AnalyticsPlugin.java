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

package org.killbill.billing.plugin.analytics;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.joda.JodaModule;
import com.google.common.collect.ImmutableMap;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServlet;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.killbill.billing.notification.plugin.api.NotificationPluginApi;
import org.killbill.billing.plugin.analytics.api.core.AnalyticsConfiguration;
import org.killbill.billing.plugin.analytics.api.core.AnalyticsConfigurationHandler;
import org.killbill.billing.plugin.analytics.api.user.AnalyticsUserApi;
import org.killbill.billing.plugin.analytics.core.AnalyticsHealthcheck;
import org.killbill.billing.plugin.analytics.dao.BusinessDBIProvider;
import org.killbill.billing.plugin.analytics.http.AnalyticsAccountResource;
import org.killbill.billing.plugin.analytics.http.AnalyticsHealthcheckResource;
import org.killbill.billing.plugin.analytics.http.ReportsResource;
import org.killbill.billing.plugin.analytics.reports.ReportsConfiguration;
import org.killbill.billing.plugin.analytics.reports.ReportsUserApi;
import org.killbill.billing.plugin.analytics.reports.scheduler.JobsScheduler;
import org.killbill.billing.plugin.api.notification.PluginConfigurationEventHandler;
import org.killbill.billing.plugin.core.config.PluginEnvironmentConfig;
import org.killbill.billing.plugin.core.resources.jooby.PluginApp;
import org.killbill.billing.plugin.core.resources.jooby.PluginAppBuilder;
import org.killbill.billing.plugin.dao.PluginDao.DBEngine;
import org.killbill.billing.plugin.dao.PluginDao;
import org.killbill.billing.plugin.runtime.PluginBase;
import org.killbill.billing.runtime.api.Healthcheck;
import org.killbill.bus.dao.BusEventModelDao;
import org.killbill.clock.Clock;
import org.killbill.commons.jdbi.mapper.LowerToCamelBeanMapperFactory;
import org.killbill.commons.locker.GlobalLocker;
import org.killbill.commons.locker.memory.MemoryGlobalLocker;
import org.killbill.commons.locker.mysql.MySqlGlobalLocker;
import org.killbill.commons.locker.postgresql.PostgreSQLGlobalLocker;
import org.killbill.notificationq.DefaultNotificationQueueService;
import org.killbill.notificationq.api.NotificationQueueConfig;
import org.killbill.notificationq.dao.NotificationEventModelDao;
import org.skife.config.AugmentedConfigurationObjectFactory;
import org.skife.jdbi.v2.DBI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Migrated from AnalyticsPlugin.
 * <p>
 * The framework handle, the {@code Hashtable} of registration properties and the per-type
 * {@code registerXxx} helpers are gone; what remains is the plugin's own startup order.
 */
public class AnalyticsPlugin extends PluginBase {

    public static final String PLUGIN_NAME = "killbill-analytics";
    public static final String ANALYTICS_QUEUE_SERVICE = "AnalyticsService";
    public static final String PROPERTY_PREFIX = "org.killbill.billing.plugin.analytics.";
    private static final Logger logger = LoggerFactory.getLogger(AnalyticsPlugin.class);

    private AnalyticsConfigurationHandler analyticsConfigurationHandler;
    private AnalyticsListener analyticsListener;
    private JobsScheduler jobsScheduler;
    private ReportsUserApi reportsUserApi;
    private Clock killbillClock;

    @Override
    protected void startPlugin() throws Exception {
        

        killbillClock = clock;

        final Executor executor = BusinessExecutor.newCachedThreadPool(configProperties);

        final NotificationQueueConfig config = new AugmentedConfigurationObjectFactory(configProperties.getProperties()).buildWithReplacements(NotificationQueueConfig.class,
                                                                                                                                      ImmutableMap.<String, String>of("instanceName", "analytics"));
        if ("notifications".equals(config.getTableName())) {
            logger.warn("Analytics plugin mis-configured: you are probably missing the property org.killbill.notificationq.analytics.tableName=analytics_notifications");
        }
        if ("notifications_history".equals(config.getHistoryTableName())) {
            logger.warn("Analytics plugin mis-configured: you are probably missing the property org.killbill.notificationq.analytics.historyTableName=analytics_notifications_history");
        }

        final DBI dbi = BusinessDBIProvider.get(dataSource(), metricRegistry());
        dbi.registerMapper(new LowerToCamelBeanMapperFactory(BusEventModelDao.class));
        dbi.registerMapper(new LowerToCamelBeanMapperFactory(NotificationEventModelDao.class));

        final DefaultNotificationQueueService notificationQueueService = new DefaultNotificationQueueService(dbi, killbillClock, config, metricRegistry());

        final String region = PluginEnvironmentConfig.getRegion(configProperties.getProperties());

        analyticsConfigurationHandler = new AnalyticsConfigurationHandler(region, PLUGIN_NAME, killbillApi);
        analyticsConfigurationHandler.setDefaultConfigurable(new AnalyticsConfiguration(configProperties.getProperties()));

        // Timeout defines how long to sleep between retries to get the lock
        final long lockSleepMilliSeconds = Long.parseLong(configProperties.getProperties().getProperty("org.killbill.analytics.lockSleepMilliSeconds", "100"));

        final DBEngine dbEngine = PluginDao.getDBEngine(dataSource());
        final GlobalLocker locker;
        switch (dbEngine) {
            case MYSQL:
                locker = new MySqlGlobalLocker(dataSource(), lockSleepMilliSeconds, TimeUnit.MILLISECONDS);
                break;
            case POSTGRESQL:
                locker = new PostgreSQLGlobalLocker(dataSource(), lockSleepMilliSeconds, TimeUnit.MILLISECONDS);
                break;
            case GENERIC:
            case H2:
            default:
                locker = new MemoryGlobalLocker();
                break;
        }
        analyticsListener = new AnalyticsListener(killbillApi,
                                                  dataSource(),
                                                  metricRegistry(),
                                                  configProperties,
                                                  executor,
                                                  locker,
                                                  killbillClock,
                                                  analyticsConfigurationHandler,
                                                  notificationQueueService);

        jobsScheduler = new JobsScheduler(dataSource(), metricRegistry(), killbillClock, notificationQueueService);

        final ReportsConfiguration reportsConfiguration = new ReportsConfiguration(dataSource(), metricRegistry(), jobsScheduler);

        final AnalyticsUserApi analyticsUserApi = new AnalyticsUserApi(killbillApi, dataSource(), metricRegistry(), configProperties, executor, killbillClock, analyticsConfigurationHandler, analyticsListener);
        reportsUserApi = new ReportsUserApi(killbillApi, dataSource(), metricRegistry(), configProperties, dbEngine, reportsConfiguration, jobsScheduler, analyticsConfigurationHandler);

        final AnalyticsHealthcheck healthcheck = new AnalyticsHealthcheck(analyticsListener, jobsScheduler);
        registerService(Healthcheck.class, healthcheck);

        final ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JodaModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);

        final PluginApp pluginApp = new PluginAppBuilder(PLUGIN_NAME,
                                                         killbillApi,
                                                         dataSource(),
                                                         clock,
                                                         configProperties).withRouteClass(AnalyticsHealthcheckResource.class)
                                                                          .withRouteClass(ReportsResource.class)
                                                                          .withRouteClass(AnalyticsAccountResource.class) // Needs to be last (to avoid matching /healthcheck or /reports)!
                                                                          .withService(analyticsUserApi)
                                                                          .withService(reportsUserApi)
                                                                          .withService(clock)
                                                                          .withService(healthcheck)
                                                                          .withObjectMapper(objectMapper)
                                                                          .build();
        final HttpServlet httpServlet = PluginApp.createServlet(pluginApp);
        registerService(Servlet.class, httpServlet);

        // Was dispatcher.registerEventHandlers(...): a configuration handler is a
        // NotificationPluginApi now, published like any other service.
        registerService(NotificationPluginApi.class,
                        new PluginConfigurationEventHandler(analyticsConfigurationHandler),
                        PLUGIN_NAME + "-config");

        // The OSGi version deferred these to an OSGIFrameworkEventHandler callback, so they only
        // ran once the framework had finished starting. The plugin runtime starts plugins after the
        // registry exists, so there is nothing left to wait for.
        analyticsListener.start();
        registerService(NotificationPluginApi.class, analyticsListener);
        jobsScheduler.start();
    }

    @Override
    protected void stopPlugin() throws Exception {
        if (jobsScheduler != null) {
            jobsScheduler.shutdownNow();
        }
        if (analyticsListener != null) {
            // Little bit of subtlety here, which is queue implementation dependent: only the second time
            // the queue is asked to stop that it will actually go through the shutdown sequence
            if (!analyticsListener.shutdownNow()) {
                logger.warn("Timed out while shutting down Analytics notifications queue: IN_PROCESSING entries might be left behind");
            }
        }
        if (reportsUserApi != null) {
            reportsUserApi.shutdownNow();
        }
    }
}

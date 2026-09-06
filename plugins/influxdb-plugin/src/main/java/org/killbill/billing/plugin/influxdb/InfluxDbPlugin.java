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

package org.killbill.billing.plugin.influxdb;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.killbill.billing.lpr.api.Plugin;
import org.killbill.billing.lpr.api.PluginContext;
import org.killbill.billing.platform.api.KillbillConfigSource;
import org.killbill.commons.metrics.api.MetricRegistry;
import org.killbill.commons.metrics.dropwizard.KillBillCodahaleMetricRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.ScheduledReporter;
import com.izettle.metrics.dw.SenderType;
import io.dropwizard.util.Duration;

/**
 * Reports Kill Bill's metrics to InfluxDB.
 * <p>
 * Ported from the OSGi influxdb bundle, keeping the property names so existing deployments do not
 * have to be reconfigured. Off unless {@code org.killbill.metrics.influxDb} is {@code true}.
 * <p>
 * As with the Graphite plugin, the reporter owns a thread and therefore goes through the resource
 * registry: an unmanaged reporter keeps writing after the plugin stops, from a ClassLoader that can
 * never be collected.
 */
public class InfluxDbPlugin implements Plugin {

    private static final Logger log = LoggerFactory.getLogger(InfluxDbPlugin.class);

    private static final String NAMESPACE = "org.killbill.metrics.influxDb";

    @Override
    public void start(final PluginContext context) {
        if (!Boolean.parseBoolean(property(context, NAMESPACE, "false"))) {
            log.info("Reporting metrics to InfluxDB is disabled ({} is not true)", NAMESPACE);
            return;
        }

        final int intervalSeconds = Integer.parseInt(property(context, NAMESPACE + ".interval", "30"));

        final CustomInfluxDbReporterFactory factory = new CustomInfluxDbReporterFactory();
        factory.setRateUnit(TimeUnit.SECONDS);
        factory.setDurationUnit(TimeUnit.NANOSECONDS);
        factory.setHost(property(context, NAMESPACE + ".host", "localhost"));
        factory.setPort(Integer.parseInt(property(context, NAMESPACE + ".port", "8086")));
        factory.setReadTimeout(Integer.parseInt(property(context, NAMESPACE + ".socketTimeout", "1000")));
        factory.setDatabase(property(context, NAMESPACE + ".database", "killbill"));
        factory.setPrefix(property(context, NAMESPACE + ".prefix", ""));
        factory.setSenderType(SenderType.valueOf(property(context, NAMESPACE + ".senderType", "HTTP")));
        factory.setOrganization(property(context, NAMESPACE + ".organization", "killbill"));
        factory.setBucket(property(context, NAMESPACE + ".bucket", "killbill"));
        factory.setToken(property(context, NAMESPACE + ".token", ""));
        factory.setFrequency(Optional.of(Duration.seconds(intervalSeconds)));

        final MetricRegistry killbillRegistry = context.getPlatformService(MetricRegistry.class);
        final ScheduledReporter reporter = factory.build(codahaleViewOf(killbillRegistry));
        reporter.start(intervalSeconds, TimeUnit.SECONDS);
        context.resources().manage(reporter);

        log.info("Reporting metrics to InfluxDB at {}:{} every {}s",
                 factory.getHost(), factory.getPort(), intervalSeconds);
    }

    @Override
    public void stop() {
        // The reporter is closed by the resource registry.
    }

    /**
     * Reads a setting from the plugin's own descriptor, falling back to {@code killbill.properties}.
     * <p>
     * The descriptor wins because it is scoped to this plugin and cannot collide with another's
     * key. The fallback exists so a deployment carried over from the OSGi bundle, where these were
     * global properties, keeps working without being reconfigured.
     */
    private static String property(final PluginContext context, final String key, final String fallback) {
        return context.config()
                      .find(key)
                      .or(() -> Optional.ofNullable(
                              context.getPlatformService(KillbillConfigSource.class).getString(key)))
                      .orElse(fallback);
    }

    private static com.codahale.metrics.MetricRegistry codahaleViewOf(final MetricRegistry registry) {
        if (registry instanceof KillBillCodahaleMetricRegistry codahale) {
            return codahale.getMetricRegistry();
        }
        throw new IllegalStateException(
                "InfluxDB reporting needs Dropwizard-backed metrics, but this deployment's "
                + "MetricRegistry is a " + registry.getClass().getName()
                + ". Install the metrics plugin, which publishes a Dropwizard-backed registry.");
    }
}

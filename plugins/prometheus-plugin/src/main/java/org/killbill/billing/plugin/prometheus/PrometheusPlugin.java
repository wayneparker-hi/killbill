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

package org.killbill.billing.plugin.prometheus;

import java.util.Map;

import jakarta.servlet.Servlet;

import org.killbill.billing.lpr.api.Plugin;
import org.killbill.billing.lpr.api.PluginContext;
import org.killbill.commons.metrics.api.MetricRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exposes Kill Bill's metrics in Prometheus text format at {@code /plugins/killbill-prometheus}.
 * <p>
 * Ported from the OSGi prometheus bundle. The interesting part of the port is where the metrics come
 * from: the OSGi bundle reached for the {@code MetricRegistry} its base class had tracked out of the
 * service registry, whereas here it is a platform service, asked for by type.
 * <p>
 * The collector reads Kill Bill's own {@code MetricRegistry} interface rather than a Dropwizard
 * registry, so this plugin never touches {@code com.codahale.metrics} at all.
 * <p>
 * The collector registers itself into Prometheus's process-wide {@code CollectorRegistry}, which is
 * global state outside this plugin's ClassLoader. It is therefore unregistered on stop through the
 * resource registry -- otherwise reloading the plugin would leave the old collector behind, holding
 * a reference to the old ClassLoader and double-reporting every metric.
 */
public class PrometheusPlugin implements Plugin {

    private static final Logger log = LoggerFactory.getLogger(PrometheusPlugin.class);

    public static final String REGISTRATION_NAME = "killbill-prometheus";

    @Override
    public void start(final PluginContext context) {
        final MetricRegistry killbillRegistry = context.getPlatformService(MetricRegistry.class);
        final KillBillCollector collector = new KillBillCollector(killbillRegistry);
        collector.register();
        context.resources().onClose(() -> {
            io.prometheus.client.CollectorRegistry.defaultRegistry.unregister(collector);
            log.info("Unregistered the Kill Bill Prometheus collector");
        });

        context.services().register(Servlet.class,
                                    new KillBillMetricsServlet(),
                                    Map.of("killbill.pluginName", REGISTRATION_NAME));
        log.info("Serving Prometheus metrics at /plugins/{}", REGISTRATION_NAME);
    }

    @Override
    public void stop() {
        // The collector is unregistered by the resource registry, the servlet by the service
        // registry. Nothing else is held.
    }

}

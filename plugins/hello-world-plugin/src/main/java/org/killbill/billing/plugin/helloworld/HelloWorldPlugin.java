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

package org.killbill.billing.plugin.helloworld;

import org.killbill.billing.lpr.api.Plugin;
import org.killbill.billing.lpr.api.PluginContext;
import org.killbill.billing.payment.plugin.api.PaymentPluginApi;
import org.killbill.billing.platform.plugin.api.PluginServiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The plugin's entry point, ported from {@code HelloWorldActivator}.
 * <p>
 * Worth comparing against the original, because the difference is the point of the migration. The
 * OSGi version extended {@code KillbillActivatorBase} and, in about 120 lines, opened a
 * {@code ServiceTracker} for each Kill Bill service it needed, built a {@code Hashtable} of
 * properties for each service it published, and called {@code registrar.registerService} once per
 * service with a {@code BundleContext} threaded through all of it. Its entry point was not named
 * anywhere: the build inferred it by scanning the jar for a subclass of
 * {@code KillbillActivatorBase}.
 * <p>
 * Here the runtime hands over everything through {@link PluginContext}, registration is one call,
 * and the entry point is named in {@code plugin.yaml}. What did not change is the part that was
 * always this plugin's own business -- {@link HelloWorldPaymentPluginApi} compiles untouched.
 * <p>
 * Note what {@code start} does <b>not</b> do: call Kill Bill's business APIs. Plugins start during
 * the {@code STARTUP_PRE} sequence, before core services are serving, so work that needs the core
 * belongs in an event handler.
 */
public class HelloWorldPlugin implements Plugin {

    private static final Logger log = LoggerFactory.getLogger(HelloWorldPlugin.class);

    /**
     * The name Kill Bill knows this plugin by -- what a payment method row stores and what
     * configuration refers to.
     * <p>
     * Declared explicitly because it differs from the plugin id: the id is {@code hello-world},
     * while payment methods created against the OSGi version were stored under
     * {@code hello-world-plugin}. Dropping this would leave those rows pointing at a plugin name
     * nothing resolves.
     */
    public static final String REGISTRATION_NAME = "hello-world-plugin";

    private HelloWorldPaymentPluginApi paymentPluginApi;

    @Override
    public void start(final PluginContext context) {
        final String greeting = context.config().find("helloworld.greeting").orElse("Hello, world");
        log.info("Starting {} version {}: {}", context.pluginId(), context.version(), greeting);

        paymentPluginApi = new HelloWorldPaymentPluginApi(greeting);
        context.services().register(PaymentPluginApi.class,
                                    paymentPluginApi,
                                    java.util.Map.of(PluginServiceProperties.REGISTRATION_NAME, REGISTRATION_NAME));

        // Every subscription goes through the resource registry. An orphaned one keeps the handler,
        // and therefore this plugin's ClassLoader, reachable for the life of the JVM.
        context.resources().manage(
                context.eventBus().subscribe(Object.class, event -> log.debug("Observed event {}", event)));
    }

    @Override
    public void stop() {
        // Services and subscriptions are withdrawn by the runtime; only state this plugin manages
        // itself needs releasing here.
        if (paymentPluginApi != null) {
            log.info("Stopping hello-world plugin after {} transaction(s)", paymentPluginApi.transactionCount());
            paymentPluginApi = null;
        }
    }
}

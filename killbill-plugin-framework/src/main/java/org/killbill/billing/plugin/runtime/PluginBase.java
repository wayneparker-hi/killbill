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

package org.killbill.billing.plugin.runtime;

import java.util.Map;
import java.util.Objects;

import javax.sql.DataSource;

import org.killbill.billing.lpr.api.Plugin;
import org.killbill.billing.lpr.api.PluginContext;
import org.killbill.clock.Clock;
import org.killbill.commons.metrics.api.MetricRegistry;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * The base class a Kill Bill plugin extends.
 * <p>
 * Replaces {@code KillbillActivatorBase}, and is a good measure of what the migration bought: the
 * OSGi base class opened five {@code ServiceTracker}s in {@code start(BundleContext)}, closed them
 * in {@code stop}, held a {@code BundleContext} field, and required each subclass to remember to
 * call {@code super}. The runtime now hands all of that over in one object, so what is left here is
 * bookkeeping around it.
 *
 * <h2>What subclasses do</h2>
 * Override {@link #startPlugin()} to register services, and {@link #stopPlugin()} only if there is
 * something to release that {@code ResourceRegistry} cannot hold. Both are optional.
 *
 * <h2>Registering services</h2>
 * {@link #registerService(Class, Object)} publishes under the plugin's own id, which is the name
 * Kill Bill will look it up by. That default is the change from OSGi, where a plugin that forgot to
 * set {@code killbill.pluginName} registered successfully and was then never found.
 *
 * <h2>What start() may and may not do</h2>
 * Plugins start before Kill Bill's own services do -- {@code START_PLUGIN} is in the
 * {@code STARTUP_PRE} sequence. The APIs on {@link #killbillApi} are wired but not yet serving, so
 * a plugin may hold references to them and must not call them for business work until the
 * {@code .../lifecycle/STARTED} event arrives.
 */
@SuppressFBWarnings(
        value = "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD",
        justification = "These fields exist to be read by subclasses in other modules -- that is the "
                        + "whole point of a base class. Nothing in this module reads them, which is "
                        + "exactly what the detector reports.")
public abstract class PluginBase implements Plugin {

    /** The Kill Bill APIs. Available from {@link #startPlugin()} onwards. */
    protected KillbillApi killbillApi;

    /** Configuration: this plugin's descriptor first, then {@code killbill.properties}. */
    protected PluginConfigProperties configProperties;

    /** The runtime handle: services, events, resources, and this plugin's own identity. */
    protected PluginContext context;

    protected Clock clock;

    @Override
    public final void start(final PluginContext pluginContext) throws Exception {
        this.context = Objects.requireNonNull(pluginContext, "pluginContext");
        this.killbillApi = new KillbillApi(pluginContext);
        this.configProperties = new PluginConfigProperties(pluginContext);
        this.clock = pluginContext.getPlatformService(Clock.class);
        startPlugin();
    }

    @Override
    public final void stop() throws Exception {
        try {
            stopPlugin();
        } finally {
            // Dropped so that a plugin instance held by mistake does not keep the APIs, and through
            // them half the platform, reachable.
            context = null;
            killbillApi = null;
            configProperties = null;
            clock = null;
        }
    }

    /**
     * Register services and start work here.
     *
     * @throws Exception to fail the plugin; the runtime marks it FAILED and leaves the rest running
     */
    protected void startPlugin() throws Exception {
    }

    /**
     * Release anything the {@code ResourceRegistry} is not holding.
     * <p>
     * Usually empty: executors, subscriptions and connections belong in
     * {@code context.resources()}, which unwinds them whether or not this method runs.
     *
     * @throws Exception logged; teardown continues regardless
     */
    protected void stopPlugin() throws Exception {
    }

    /**
     * Publishes a service under this plugin's id.
     *
     * @param type    the service interface Kill Bill looks up
     * @param service the implementation
     * @param <T>     the service type
     */
    protected <T> void registerService(final Class<T> type, final T service) {
        registerService(type, service, context.pluginId());
    }

    /**
     * Publishes a service under a name of its own.
     * <p>
     * Only needed when the name Kill Bill must use differs from the plugin id -- a plugin whose
     * artifact was renamed, say, but whose registered name has to stay put.
     *
     * @param type             the service interface
     * @param service          the implementation
     * @param registrationName the name to publish under
     * @param <T>              the service type
     */
    protected <T> void registerService(final Class<T> type, final T service, final String registrationName) {
        context.services().register(type, service, Map.of("killbill.pluginName", registrationName));
    }

    /**
     * @return the shared connection pool, for plugins that keep their own tables
     */
    protected DataSource dataSource() {
        return context.getPlatformService(DataSource.class);
    }

    /**
     * @return the registry every Kill Bill timer feeds; plugin metrics belong here too
     */
    protected MetricRegistry metricRegistry() {
        return context.getPlatformService(MetricRegistry.class);
    }

    /**
     * @return this plugin's id, which is also its default service registration name
     */
    protected String pluginName() {
        return context.pluginId();
    }
}

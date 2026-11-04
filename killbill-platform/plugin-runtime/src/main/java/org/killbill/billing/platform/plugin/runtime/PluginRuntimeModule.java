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

package org.killbill.billing.platform.plugin.runtime;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.killbill.billing.lpr.classloader.DefaultPluginClassLoaderFactory;
import org.killbill.billing.lpr.api.EventBus;
import org.killbill.billing.lpr.event.DefaultEventBus;
import org.killbill.billing.lpr.core.DefaultPluginLifecycleManager;
import org.killbill.billing.lpr.lifecycle.DefaultPluginStopExecutor;
import org.killbill.billing.lpr.spi.PluginStopExecutor;
import org.killbill.billing.lpr.core.DefaultPluginManager;
import org.killbill.billing.lpr.core.DefaultServiceRegistry;
import org.killbill.billing.lpr.spi.PluginRepository;
import org.killbill.billing.lpr.repository.filesystem.FileSystemPluginRepository;
import org.killbill.billing.lpr.repository.filesystem.SharedFileSystemPluginRepository;
import org.killbill.billing.lpr.descriptor.yaml.YamlDescriptorParser;
import org.killbill.billing.lpr.management.PluginInstaller;
import org.killbill.billing.lpr.core.LprMetricsCollector;
import org.killbill.billing.lpr.core.NoOpLprMetrics;
import org.killbill.billing.lpr.spi.ClassLoaderPolicy;
import org.killbill.billing.lpr.spi.LprMetrics;
import org.killbill.billing.platform.plugin.runtime.http.PluginServlet;
import org.killbill.billing.platform.plugin.runtime.http.PluginServletRouter;
import org.killbill.billing.platform.api.KillbillConfigSource;
import org.killbill.billing.platform.glue.KillBillPlatformModuleBase;
import org.killbill.billing.notification.plugin.api.NotificationPluginApi;
import org.killbill.billing.platform.plugin.api.PluginServiceProperties;
import org.killbill.billing.platform.plugin.api.PluginServiceRegistry;
import org.killbill.billing.platform.plugin.api.SinglePluginServiceRegistry;
import org.killbill.billing.runtime.api.Healthcheck;
import org.killbill.billing.runtime.api.PluginsInfoApi;
import org.skife.config.AugmentedConfigurationObjectFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Provides;
import com.google.inject.TypeLiteral;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;

/**
 * Assembles the plugin runtime and connects it to Kill Bill.
 * <p>
 * Replaces {@code DefaultOSGIModule}. The shape of the wiring is the interesting part: everything
 * Kill Bill-specific is on this side of the boundary, and the runtime below it is handed only
 * general-purpose collaborators -- a ClassLoader factory, a descriptor parser, a directory. It has
 * no idea what a payment is.
 * <p>
 * The typed registries reach the bridge through a {@link Multibinder} that each business module
 * contributes to. That inversion matters: the OSGi activator had to name all eleven plugin service
 * types in eleven injected fields, so adding a service type meant editing the platform. Here the
 * platform never learns what types exist, and a deployment without an invoice plugin registry
 * simply contributes one fewer binding.
 */
public class PluginRuntimeModule extends KillBillPlatformModuleBase {

    private static final Logger log = LoggerFactory.getLogger(PluginRuntimeModule.class);

    /**
     * The multibinding business modules contribute their registries to. A wildcard rather than a
     * raw type, so that {@code PluginServiceRegistry<PaymentPluginApi>} links to it without an
     * unchecked cast on either side.
     */
    public static final TypeLiteral<PluginServiceRegistry<?>> REGISTRY_TYPE =
            new TypeLiteral<PluginServiceRegistry<?>>() { };

    /** The same, for service types of which there can be only one implementation. */
    public static final TypeLiteral<SinglePluginServiceRegistry<?>> SINGLE_REGISTRY_TYPE =
            new TypeLiteral<SinglePluginServiceRegistry<?>>() { };

    public PluginRuntimeModule(final KillbillConfigSource configSource) {
        super(configSource);
    }

    @Override
    protected void configure() {
        bind(PluginRuntimeConfig.class).toInstance(
                new AugmentedConfigurationObjectFactory(skifeConfigSource).build(PluginRuntimeConfig.class));

        // Bound so that PlatformServices can publish it to plugins. The platform passes the config
        // source into module constructors rather than binding it, which is fine for modules but
        // leaves plugins -- which can only ask by type -- with no way to read killbill.properties.
        // The OSGi layer published the equivalent as OSGIConfigProperties.
        bind(KillbillConfigSource.class).toInstance(configSource);

        installPluginServlet();
        bind(PluginRuntimeService.class).asEagerSingleton();
        bind(PluginRuntimeLifecycleService.class).to(PluginRuntimeService.class);
        bind(PluginUpgradeMonitor.class).asEagerSingleton();
        bind(PluginClusterCoordinator.class).asEagerSingleton();
        bind(PluginClusterStatusService.class).asEagerSingleton();
        bind(PluginsInfoApi.class).to(DefaultPluginsInfoApi.class).asEagerSingleton();
        installNotificationPlugins();

        // Declared even when empty, so a deployment that contributes no registries still injects
        // cleanly rather than failing on a missing binding.
        Multibinder.newSetBinder(binder(), REGISTRY_TYPE);
        Multibinder.newSetBinder(binder(), SINGLE_REGISTRY_TYPE);
    }

    /**
     * The registry notification plugins land in, plus the dispatcher that feeds it from the bus.
     * <p>
     * Registered here rather than in a business module because there is no "notification" module to
     * put it in -- the events come from the external bus, which the platform owns. This is also what
     * makes {@code NotificationPluginApi} an extension point like any other: under OSGi it was the
     * one API delivered by an observer callback instead of through a registry.
     */
    private void installNotificationPlugins() {
        final MapBackedPluginServiceRegistry<NotificationPluginApi> registry =
                new MapBackedPluginServiceRegistry<>(NotificationPluginApi.class);
        bind(new TypeLiteral<PluginServiceRegistry<NotificationPluginApi>>() { }).toInstance(registry);
        Multibinder.newSetBinder(binder(), REGISTRY_TYPE).addBinding().toInstance(registry);

        bind(PluginEventDispatcher.class).asEagerSingleton();
        bind(PluginEventDispatcherService.class).to(PluginEventDispatcher.class);
        bind(PluginNodeCommandListener.class).asEagerSingleton();
    }

    /**
     * Wires up HTTP: plugins register a {@code Servlet}, the router keeps a prefix table, and one
     * servlet at the front dispatches {@code /plugins/...} into it.
     */
    private void installPluginServlet() {
        final PluginServletRouter router = new PluginServletRouter();
        bind(PluginServletRouter.class).toInstance(router);
        bind(new TypeLiteral<PluginServiceRegistry<jakarta.servlet.Servlet>>() { }).toInstance(router);
        Multibinder.newSetBinder(binder(), REGISTRY_TYPE).addBinding().toInstance(router);

        bind(jakarta.servlet.http.HttpServlet.class)
                .annotatedWith(com.google.inject.name.Names.named(PluginServiceProperties.PLUGIN_SERVLET))
                .to(PluginServlet.class)
                .asEagerSingleton();
    }

    /**
     * The runtime's only ClassLoader policy decision: which packages plugins must take from the
     * platform. The defaults cover the JDK and the Kill Bill plugin contract; a deployment adds to
     * them only to share some other library's types across the boundary, at the cost of plugins no
     * longer choosing their own version of it.
     */
    @Provides
    @Singleton
    LprMetrics provideLprMetrics(final com.google.inject.Injector injector) {
        final com.google.inject.Binding<org.killbill.commons.metrics.api.MetricRegistry> binding =
                injector.getExistingBinding(com.google.inject.Key.get(org.killbill.commons.metrics.api.MetricRegistry.class));
        if (binding != null) {
            return new KillBillLprMetrics(binding.getProvider().get());
        }
        return NoOpLprMetrics.INSTANCE;
    }

    @Provides
    @Singleton
    LprMetricsCollector provideLprMetricsCollector(final LprMetrics metrics) {
        return new LprMetricsCollector(metrics);
    }

    @Provides
    @Singleton
    DefaultServiceRegistry provideServiceRegistry(final LprMetricsCollector metrics) {
        return new DefaultServiceRegistry(metrics);
    }

    @Provides
    @Singleton
    PluginInvocationGuard providePluginInvocationGuard(final PluginRuntimeConfig config,
                                                       final LprMetricsCollector metrics) {
        return new PluginInvocationGuard(config, metrics);
    }

    @Provides
    @Singleton
    PluginHealthCheckManager providePluginHealthCheckManager(
            final com.google.inject.Provider<DefaultPluginManager> pluginManagerProvider,
            final PluginRuntimeConfig config,
            final LprMetricsCollector metrics) {
        return new PluginHealthCheckManager(() -> pluginManagerProvider.get().runningPlugins(), config, metrics);
    }

    @Provides
    @Singleton
    EventBus provideEventBus(final LprMetricsCollector metrics) {
        return new DefaultEventBus(metrics);
    }

    @Provides
    @Singleton
    ClassLoaderPolicy provideClassLoaderPolicy(final PluginRuntimeConfig config) {
        final String extra = config.getExtraParentFirstPackages();
        if (extra == null || extra.isBlank()) {
            return ClassLoaderPolicy.defaultPolicy();
        }
        final List<String> prefixes = Arrays.stream(extra.split(","))
                                            .map(String::trim)
                                            .filter(s -> !s.isEmpty())
                                            .toList();
        log.info("Plugins will resolve these extra package prefixes from the platform: {}", prefixes);
        return ClassLoaderPolicy.defaultPolicy().withParentFirst(prefixes);
    }

    @Provides
    @Singleton
    PluginRepository providePluginRepository(final PluginRuntimeConfig config) {
        final Path local = Path.of(config.getPluginInstallDir());
        return switch (config.getPluginRepositoryMode()) {
            case "local" -> new FileSystemPluginRepository(local, new YamlDescriptorParser(), config.getPluginRepositoryLockTimeout());
            case "shared" -> {
                final String shared = config.getPluginSharedRepositoryDir();
                if (shared == null || shared.isBlank()) {
                    throw new IllegalArgumentException("Shared repository directory is required in shared mode");
                }
                yield new SharedFileSystemPluginRepository(Path.of(shared), local, new YamlDescriptorParser(),
                                                            config.getPluginRepositoryLockTimeout());
            }
            default -> throw new IllegalArgumentException("Unknown plugin repository mode: " + config.getPluginRepositoryMode());
        };
    }

    /**
     * What replaces KPM: fetching a plugin artifact and laying it out where the repository will find
     * it. Reached through the {@code INSTALL_PLUGIN} node command rather than an API of its own.
     */
    @Provides
    @Singleton
    PluginInstaller providePluginInstaller(final PluginRepository repository) {
        return new PluginInstaller(repository);
    }

    @Provides
    @Singleton
    PluginStopExecutor providePluginStopExecutor(final PluginRuntimeConfig config) {
        return new DefaultPluginStopExecutor(config.getPluginStopMaxThreads(), config.getPluginStopTimeout());
    }

    /**
     * Wires the bridge in as a listener before anything can register, and returns the manager.
     * <p>
     * Attaching the listener here rather than in {@code configure()} is what guarantees the
     * ordering: the manager cannot exist without the lifecycle, the lifecycle cannot register
     * anything before this method returns, so no registration can be missed.
     */
    @Provides
    @Singleton
    DefaultPluginManager providePluginManager(final PluginRepository repository,
                                              final DefaultServiceRegistry serviceRegistry,
                                              final EventBus eventBus,
                                              final ClassLoaderPolicy policy,
                                              final LprMetricsCollector metrics,
                                              final PluginInvocationGuard invocationGuard,
                                              final PluginStopExecutor stopExecutor,
                                              final PluginHealthCheckManager healthCheckManager,
                                              final com.google.inject.Injector injector,
                                              final Set<PluginServiceRegistry<?>> typedRegistries,
                                              final Set<SinglePluginServiceRegistry<?>> singleRegistries) {
        final PluginServiceRegistry<Healthcheck> healthcheckRegistry = resolveHealthcheckRegistry(injector);

        final DefaultPluginLifecycleManager lifecycle = new DefaultPluginLifecycleManager(
                new DefaultPluginClassLoaderFactory(),
                policy,
                PluginRuntimeModule.class.getClassLoader(),
                serviceRegistry,
                eventBus,
                PlatformServices.resolve(injector),
                metrics, stopExecutor);
        final DefaultPluginManager pluginManager = new DefaultPluginManager(repository, lifecycle, serviceRegistry, metrics);

        serviceRegistry.setHealthPredicate(healthCheckManager::isHealthy);
        healthCheckManager.addListener(serviceRegistry::refreshVisibility);

        serviceRegistry.addListener(new KillbillPluginServiceBridge(List.copyOf(typedRegistries),
                                                                    List.copyOf(singleRegistries),
                                                                    healthcheckRegistry,
                                                                    healthCheckManager,
                                                                    invocationGuard,
                                                                    serviceRegistry::isVisible));
        return pluginManager;
    }

    private static PluginServiceRegistry<Healthcheck> resolveHealthcheckRegistry(final com.google.inject.Injector injector) {
        final com.google.inject.Binding<PluginServiceRegistry<Healthcheck>> binding =
                injector.getExistingBinding(com.google.inject.Key.get(new TypeLiteral<PluginServiceRegistry<Healthcheck>>() { }));
        return binding != null ? binding.getProvider().get() : null;
    }
}

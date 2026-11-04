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

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.killbill.billing.lpr.core.DefaultPluginManager;
import org.killbill.billing.lpr.spi.PluginRepository;
import org.killbill.billing.runtime.api.PluginsInfoApi;
import org.killbill.billing.util.nodes.KillbillNodesApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Repairs missed broadcasts from durable intent and refreshes node observations. */
public final class PluginClusterCoordinator {
    private static final Logger log = LoggerFactory.getLogger(PluginClusterCoordinator.class);
    private final DefaultPluginManager manager;
    private final PluginRepository repository;
    private final PluginRuntimeConfig config;
    private final Provider<KillbillNodesApi> nodes;
    private final Provider<PluginsInfoApi> info;
    private final AtomicBoolean started = new AtomicBoolean();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        final Thread thread = new Thread(task, "plugin-cluster-coordinator");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public PluginClusterCoordinator(final DefaultPluginManager manager, final PluginRepository repository,
                                    final PluginRuntimeConfig config, final Provider<KillbillNodesApi> nodes,
                                    final Provider<PluginsInfoApi> info) {
        this.manager = manager;
        this.repository = repository;
        this.config = config;
        this.nodes = nodes;
        this.info = info;
        if (config.getPluginRepositoryReconcileInterval() <= 0) {
            throw new IllegalArgumentException("Repository reconcile interval must be positive");
        }
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            final long interval = config.getPluginRepositoryReconcileInterval();
            scheduler.scheduleWithFixedDelay(this::runOnce, interval, interval, TimeUnit.MILLISECONDS);
        }
    }

    public void runOnce() {
        try {
            if (repository.shared() && config.isPluginStartEnabled()) { manager.reconcile(); }
            nodes.get().notifyPluginChanged(null, info.get().getPluginsInfo());
        } catch (final RuntimeException e) {
            log.warn("Plugin cluster reconciliation/report failed; will retry", e);
        }
    }

    public void stop() {
        scheduler.shutdownNow();
        try { scheduler.awaitTermination(5, TimeUnit.SECONDS); }
        catch (final InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}

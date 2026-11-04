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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.killbill.billing.lpr.spi.LprMetrics;
import org.killbill.commons.metrics.api.MetricRegistry;

/**
 * Bridges LPR's metrics port to Kill Bill's {@link MetricRegistry}, which is itself an abstraction
 * over Dropwizard/Codahale metrics. This lives in the platform adapter so that {@code lpr-core}
 * stays free of any concrete metrics library (CLAUDE.md, A1).
 */
public class KillBillLprMetrics implements LprMetrics {

    private final MetricRegistry metricRegistry;
    private final ConcurrentHashMap<String, CounterAdapter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TimerAdapter> timers = new ConcurrentHashMap<>();

    public KillBillLprMetrics(final MetricRegistry metricRegistry) {
        this.metricRegistry = metricRegistry;
    }

    @Override
    public Counter counter(final String name) {
        return counters.computeIfAbsent(name, k -> new CounterAdapter(metricRegistry.counter(name)));
    }

    @Override
    public Timer timer(final String name) {
        return timers.computeIfAbsent(name, k -> new TimerAdapter(metricRegistry.timer(name)));
    }

    @Override
    public <T extends Number> Gauge gauge(final String name, final Supplier<T> value) {
        metricRegistry.gauge(name, new org.killbill.commons.metrics.api.Gauge<T>() {
            @Override
            public T getValue() {
                return value.get();
            }
        });
        return new Gauge() {
        };
    }

    private static final class CounterAdapter implements Counter {

        private final org.killbill.commons.metrics.api.Counter delegate;

        CounterAdapter(final org.killbill.commons.metrics.api.Counter delegate) {
            this.delegate = delegate;
        }

        @Override
        public void increment() {
            delegate.inc(1);
        }

        @Override
        public void increment(final long delta) {
            delegate.inc(delta);
        }
    }

    private static final class TimerAdapter implements Timer {

        private final org.killbill.commons.metrics.api.Timer delegate;

        TimerAdapter(final org.killbill.commons.metrics.api.Timer delegate) {
            this.delegate = delegate;
        }

        @Override
        public void record(final long duration, final TimeUnit unit) {
            delegate.update(duration, unit);
        }
    }
}

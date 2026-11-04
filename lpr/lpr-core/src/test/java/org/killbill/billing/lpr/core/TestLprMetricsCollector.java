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

package org.killbill.billing.lpr.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.killbill.billing.lpr.api.PluginState;
import org.killbill.billing.lpr.spi.LprMetrics;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Verifies that {@link LprMetricsCollector} emits the expected metric names and values to the
 * underlying {@link LprMetrics} port.
 */
public class TestLprMetricsCollector {

    @Test(groups = "fast")
    public void testClassLoaderAndResourceCounters() {
        final RecordingMetrics recording = new RecordingMetrics();
        final LprMetricsCollector collector = new LprMetricsCollector(recording);

        collector.classLoaderCreated();
        collector.classLoaderCreated();
        collector.classLoaderDestroyed();

        collector.resourceRegistered();
        collector.resourceReleased();

        assertEquals(recording.gaugeValue("killbill.lpr.classloaders.active"), 1L);
        assertEquals(recording.gaugeValue("killbill.lpr.resources.registered"), 0L);
    }

    @Test(groups = "fast")
    public void testEventCounters() {
        final RecordingMetrics recording = new RecordingMetrics();
        final LprMetricsCollector collector = new LprMetricsCollector(recording);

        collector.eventPublished();
        collector.eventPublished();
        collector.eventFailed();

        assertEquals(recording.counterValue("killbill.lpr.events.published"), 2L);
        assertEquals(recording.counterValue("killbill.lpr.events.failed"), 1L);
    }

    @Test(groups = "fast")
    public void testPluginTimers() {
        final RecordingMetrics recording = new RecordingMetrics();
        final LprMetricsCollector collector = new LprMetricsCollector(recording);

        collector.recordPluginStart(TimeUnit.MILLISECONDS.toNanos(150));
        collector.recordPluginStop(TimeUnit.MILLISECONDS.toNanos(75));

        assertEquals(recording.timings("killbill.lpr.plugin.start.duration").size(), 1);
        assertEquals(recording.timings("killbill.lpr.plugin.stop.duration").size(), 1);
        assertEquals(recording.timings("killbill.lpr.plugin.start.duration").get(0).longValue(),
                     TimeUnit.MILLISECONDS.toNanos(150));
    }

    @Test(groups = "fast")
    public void testServiceProviderCounts() {
        final RecordingMetrics recording = new RecordingMetrics();
        final LprMetricsCollector collector = new LprMetricsCollector(recording);

        collector.providerRegistered(Runnable.class);
        collector.providerRegistered(Runnable.class);
        collector.providerUnregistered(Runnable.class);

        assertEquals(recording.gaugeValue("killbill.lpr.registry.providers.java_lang_Runnable"), 1L);
    }

    @Test(groups = "fast")
    public void testPluginStateGaugeReadsFromSupplier() {
        final RecordingMetrics recording = new RecordingMetrics();
        final LprMetricsCollector collector = new LprMetricsCollector(recording);

        final List<PluginState> states = new ArrayList<>(List.of(PluginState.ACTIVE, PluginState.INSTALLED));
        collector.trackPluginStates(() -> states);

        assertEquals(recording.gaugeValue("killbill.lpr.plugins.state.active"), 1L);
        assertEquals(recording.gaugeValue("killbill.lpr.plugins.state.installed"), 1L);
        assertEquals(recording.gaugeValue("killbill.lpr.plugins.state.failed"), 0L);

        states.add(PluginState.ACTIVE);
        assertEquals(recording.gaugeValue("killbill.lpr.plugins.state.active"), 2L);
    }

    static final class RecordingMetrics implements LprMetrics {

        private final List<Recording> recordings = new ArrayList<>();

        @Override
        public Counter counter(final String name) {
            return new Counter() {
                @Override
                public void increment() {
                    increment(1);
                }

                @Override
                public void increment(final long delta) {
                    recordings.add(new Recording(name, "counter", delta));
                }
            };
        }

        @Override
        public Timer timer(final String name) {
            return (duration, unit) -> recordings.add(new Recording(name, "timer", unit.toNanos(duration)));
        }

        @Override
        public <T extends Number> Gauge gauge(final String name, final Supplier<T> value) {
            recordings.add(new Recording(name, "gauge", value));
            return new Gauge() {
            };
        }

        long counterValue(final String name) {
            return recordings.stream()
                             .filter(r -> r.name.equals(name) && "counter".equals(r.type))
                             .mapToLong(r -> (Long) r.value)
                             .sum();
        }

        @SuppressWarnings("unchecked")
        List<Long> timings(final String name) {
            return recordings.stream()
                             .filter(r -> r.name.equals(name) && "timer".equals(r.type))
                             .map(r -> (Long) r.value)
                             .toList();
        }

        @SuppressWarnings("unchecked")
        long gaugeValue(final String name) {
            return recordings.stream()
                             .filter(r -> r.name.equals(name) && "gauge".equals(r.type))
                             .map(r -> ((Supplier<Number>) r.value).get())
                             .findFirst()
                             .orElseThrow(() -> new AssertionError("No gauge named " + name))
                             .longValue();
        }
    }

    private static final class Recording {
        private final String name;
        private final String type;
        private final Object value;

        Recording(final String name, final String type, final Object value) {
            this.name = name;
            this.type = type;
            this.value = value;
        }
    }
}

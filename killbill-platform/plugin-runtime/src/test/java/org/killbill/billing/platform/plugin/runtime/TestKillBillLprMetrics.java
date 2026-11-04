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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.killbill.billing.lpr.spi.LprMetrics;
import org.killbill.commons.metrics.api.MetricRegistry;
import org.killbill.commons.metrics.impl.NoOpMetricRegistry;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

/**
 * Verifies that {@link KillBillLprMetrics} delegates to Kill Bill's {@link MetricRegistry}.
 */
public class TestKillBillLprMetrics {

    @Test(groups = "fast")
    public void testCounterDelegation() {
        final MetricRegistry registry = new NoOpMetricRegistry();
        final LprMetrics metrics = new KillBillLprMetrics(registry);

        final LprMetrics.Counter counter = metrics.counter("lpr.test.counter");
        counter.increment();
        counter.increment(4);

        assertEquals(registry.getCounters().get("lpr.test.counter").getCount(), 5L);
    }

    @Test(groups = "fast")
    public void testTimerDelegation() {
        final MetricRegistry registry = new NoOpMetricRegistry();
        final LprMetrics metrics = new KillBillLprMetrics(registry);

        final LprMetrics.Timer timer = metrics.timer("lpr.test.timer");
        timer.record(100, TimeUnit.MILLISECONDS);

        assertEquals(registry.getTimers().get("lpr.test.timer").getCount(), 1L);
    }

    @Test(groups = "fast")
    public void testGaugeDelegation() {
        final MetricRegistry registry = new NoOpMetricRegistry();
        final LprMetrics metrics = new KillBillLprMetrics(registry);
        final AtomicLong value = new AtomicLong(42);

        metrics.gauge("lpr.test.gauge", value::get);

        assertEquals(registry.getGauges().get("lpr.test.gauge").getValue(), 42L);
        value.set(7);
        assertEquals(registry.getGauges().get("lpr.test.gauge").getValue(), 7L);
    }
}

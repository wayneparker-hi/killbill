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

package org.killbill.billing.lpr.spi;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Port for runtime observability. LPR core uses this to record counts, timings and gauges without
 * pulling in a concrete metrics library, preserving constraint A1.
 * <p>
 * The port deliberately does not expose tags: Kill Bill's {@code MetricRegistry} abstraction
 * (Dropwizard/Codahale based) uses dotted metric names rather than tagged dimensions. Implementers
 * that need tags can encode them into the name.
 * <p>
 * Implementations must be thread-safe and cheap enough to call on hot paths.
 */
public interface LprMetrics {

    /**
     * Creates or reuses a counter with the given logical name.
     */
    Counter counter(String name);

    /**
     * Creates or reuses a timer with the given logical name.
     */
    Timer timer(String name);

    /**
     * Registers a gauge with the given logical name.
     */
    <T extends Number> Gauge gauge(String name, Supplier<T> value);

    /**
     * A counter that only goes up.
     */
    interface Counter {
        void increment();

        void increment(long delta);
    }

    /**
     * A timer for recording durations.
     */
    interface Timer {
        void record(long duration, TimeUnit unit);

        default void record(final Runnable runnable) {
            final long start = System.nanoTime();
            try {
                runnable.run();
            } finally {
                record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            }
        }
    }

    /**
     * A gauge that reads the current value from the supplied function on every scrape.
     */
    interface Gauge {
        // Registration is the only operation; the value is pulled by the backend.
    }
}

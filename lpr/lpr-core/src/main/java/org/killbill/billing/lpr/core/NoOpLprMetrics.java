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

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.killbill.billing.lpr.spi.LprMetrics;

/**
 * Default no-op metrics implementation used when no metrics backend is wired. Keeps LPR core free
 * of concrete metrics dependencies while still allowing instrumentation points to be unconditional.
 */
public final class NoOpLprMetrics implements LprMetrics {

    public static final LprMetrics INSTANCE = new NoOpLprMetrics();

    private static final Counter NO_OP_COUNTER = new Counter() {
        @Override
        public void increment() {
        }

        @Override
        public void increment(final long delta) {
        }
    };

    private static final Timer NO_OP_TIMER = new Timer() {
        @Override
        public void record(final long duration, final TimeUnit unit) {
        }
    };

    private static final Gauge NO_OP_GAUGE = new Gauge() {
    };

    private NoOpLprMetrics() {
    }

    @Override
    public Counter counter(final String name) {
        return NO_OP_COUNTER;
    }

    @Override
    public Timer timer(final String name) {
        return NO_OP_TIMER;
    }

    @Override
    public <T extends Number> Gauge gauge(final String name, final Supplier<T> value) {
        return NO_OP_GAUGE;
    }
}

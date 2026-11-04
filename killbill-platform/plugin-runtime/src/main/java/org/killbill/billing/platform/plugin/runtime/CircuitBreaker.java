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
import java.util.concurrent.atomic.AtomicReference;

/** A circuit breaker whose results can only update the generation that admitted the call. */
final class CircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    record Permit(long generation) { }

    private record Snapshot(State state, int failures, long generation, long openedAt) { }

    private final int failureThreshold;
    private final long openNanos;
    private final Runnable onOpen;
    private final AtomicReference<Snapshot> snapshot =
            new AtomicReference<>(new Snapshot(State.CLOSED, 0, 0, 0));

    CircuitBreaker(final int failureThreshold, final long openDuration) {
        this(failureThreshold, openDuration, () -> { });
    }

    CircuitBreaker(final int failureThreshold, final long openDuration, final Runnable onOpen) {
        if (failureThreshold <= 0 || openDuration <= 0) {
            throw new IllegalArgumentException("Circuit breaker thresholds must be positive");
        }
        this.failureThreshold = failureThreshold;
        this.openNanos = TimeUnit.MILLISECONDS.toNanos(openDuration);
        this.onOpen = onOpen;
    }

    Permit acquire() {
        while (true) {
            final Snapshot current = snapshot.get();
            if (current.state() == State.CLOSED) {
                return new Permit(current.generation());
            }
            if (current.state() == State.HALF_OPEN || System.nanoTime() - current.openedAt() < openNanos) {
                return null;
            }
            final Snapshot probe = new Snapshot(State.HALF_OPEN, 0, current.generation() + 1, current.openedAt());
            if (snapshot.compareAndSet(current, probe)) {
                return new Permit(probe.generation());
            }
        }
    }

    void recordSuccess(final Permit permit) {
        while (true) {
            final Snapshot current = snapshot.get();
            if (current.generation() != permit.generation() || current.state() == State.OPEN) {
                return;
            }
            if (snapshot.compareAndSet(current, new Snapshot(State.CLOSED, 0, current.generation(), 0))) {
                return;
            }
        }
    }

    void recordFailure(final Permit permit) {
        while (true) {
            final Snapshot current = snapshot.get();
            if (current.generation() != permit.generation() || current.state() == State.OPEN) {
                return;
            }
            final boolean open = current.state() == State.HALF_OPEN || current.failures() + 1 >= failureThreshold;
            final Snapshot next = open
                    ? new Snapshot(State.OPEN, 0, current.generation() + 1, System.nanoTime())
                    : new Snapshot(State.CLOSED, current.failures() + 1, current.generation(), 0);
            if (snapshot.compareAndSet(current, next)) {
                if (open) {
                    onOpen.run();
                }
                return;
            }
        }
    }

    State state() {
        return snapshot.get().state();
    }
}

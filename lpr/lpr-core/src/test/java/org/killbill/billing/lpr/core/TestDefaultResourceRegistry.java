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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

public class TestDefaultResourceRegistry {

    /**
     * Newest first, so that something registered against an earlier resource is torn down before
     * the resource it depends on -- a subscription before the executor it dispatches on.
     */
    @Test(groups = "fast")
    public void testResourcesAreReleasedNewestFirst() {
        final List<String> released = new ArrayList<>();
        final DefaultResourceRegistry registry = new DefaultResourceRegistry("plugin-lifo");

        registry.onClose(() -> released.add("executor"));
        registry.onClose(() -> released.add("subscription"));
        registry.onClose(() -> released.add("http-client"));

        registry.closeAll();

        assertEquals(released, List.of("http-client", "subscription", "executor"));
    }

    /**
     * closeAll runs on the shutdown path. One resource blowing up must not strand the others, and
     * must not propagate -- a half-finished shutdown leaks more than a failed close does.
     */
    @Test(groups = "fast")
    public void testOneFailingResourceDoesNotStrandTheOthers() {
        final List<String> released = new ArrayList<>();
        final DefaultResourceRegistry registry = new DefaultResourceRegistry("plugin-failing");

        registry.onClose(() -> released.add("first"));
        registry.manage(() -> {
            throw new IllegalStateException("connection already broken");
        });
        registry.onClose(() -> released.add("third"));

        registry.closeAll();

        assertEquals(released, List.of("third", "first"));
        assertEquals(registry.size(), 0);
    }

    /**
     * The registry must not keep resources alive after releasing them. Holding on "for
     * diagnostics" would pin the plugin's ClassLoader and make unloading a no-op (CLAUDE.md, A4).
     */
    @Test(groups = "fast")
    public void testReferencesAreDroppedNotJustClosed() {
        final DefaultResourceRegistry registry = new DefaultResourceRegistry("plugin-drop");
        final WeakReference<Object> ref = registerDisposable(registry);

        registry.closeAll();
        assertEquals(registry.size(), 0);

        assertTrue(awaitCollected(ref),
                   "Registry still references a resource it already released; that is enough to pin "
                   + "the plugin ClassLoader and defeat unloading");
    }

    /**
     * A plugin can allocate during its own shutdown -- a close handler that opens a connection to
     * flush state, say. Such a resource has missed the sweep, so it is released on the spot rather
     * than silently outliving the plugin.
     */
    @Test(groups = "fast")
    public void testResourceRegisteredAfterShutdownIsReleasedImmediately() {
        final List<String> released = new ArrayList<>();
        final DefaultResourceRegistry registry = new DefaultResourceRegistry("plugin-late");

        registry.closeAll();
        registry.onClose(() -> released.add("late"));

        assertEquals(released, List.of("late"));
        assertEquals(registry.size(), 0);
    }

    @Test(groups = "fast")
    public void testCloseAllIsIdempotent() {
        final List<String> released = new ArrayList<>();
        final DefaultResourceRegistry registry = new DefaultResourceRegistry("plugin-idempotent");
        registry.onClose(() -> released.add("once"));

        registry.closeAll();
        registry.closeAll();

        assertEquals(released, List.of("once"));
    }

    @Test(groups = "fast")
    public void testManageReturnsTheSameInstanceSoItCanWrapAnExpression() {
        final DefaultResourceRegistry registry = new DefaultResourceRegistry("plugin-wrap");
        final AutoCloseable resource = () -> { };

        assertSame(registry.manage(resource), resource);
    }

    /** Keeps the strong reference inside this frame so only the weak one escapes. */
    private WeakReference<Object> registerDisposable(final DefaultResourceRegistry registry) {
        final Object payload = new Object();
        registry.manage(payload::toString);
        return new WeakReference<>(payload);
    }

    private static volatile Object gcPressureSink;

    private boolean awaitCollected(final WeakReference<?> ref) {
        final long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline && ref.get() != null) {
            System.gc();
            for (int i = 0; i < 16; i++) {
                gcPressureSink = new byte[256 * 1024];
            }
            gcPressureSink = null;
            try {
                Thread.sleep(50L);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return ref.get() == null;
    }
}

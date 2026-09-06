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

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.killbill.billing.ObjectType;
import org.killbill.billing.notification.plugin.api.ExtBusEvent;
import org.killbill.billing.notification.plugin.api.ExtBusEventType;
import org.killbill.billing.notification.plugin.api.NotificationPluginApi;
import org.killbill.billing.notification.plugin.api.NotificationPluginApiRetryException;
import org.killbill.billing.platform.plugin.api.DefaultPluginServiceDescriptor;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * How one bus event reaches many plugins, and what happens when one of them misbehaves.
 * <p>
 * The dispatch rule is not obvious and is not enforced anywhere else, so it is asserted here: an
 * ordinary exception takes out only the plugin that threw it, while a retry request is a deliberate
 * signal and must reach the retryable subscriber. Getting this backwards is easy and the symptom is
 * subtle -- either one broken plugin silently starves the others, or a plugin's retry request is
 * swallowed and the event is lost.
 * <p>
 * The dispatcher's own wiring (bus subscription, notification queue) is exercised by
 * {@link TestPluginRuntimeModule}; this test drives the fan-out directly so it can observe order and
 * failure handling without a database.
 */
public class TestPluginEventDispatcher {

    private MapBackedPluginServiceRegistry<NotificationPluginApi> registry;

    @BeforeMethod(groups = "fast")
    public void beforeMethod() {
        registry = new MapBackedPluginServiceRegistry<>(NotificationPluginApi.class);
    }

    @Test(groups = "fast")
    public void testEveryRegisteredPluginSeesTheEvent() {
        final Recorder first = register("first");
        final Recorder second = register("second");

        final ExtBusEvent event = event();
        dispatch(event);

        assertEquals(first.seen, List.of(event));
        assertEquals(second.seen, List.of(event));
    }

    /**
     * The contract the invoice and payment dispatchers already follow: a broken plugin is skipped,
     * not allowed to become everyone else's problem.
     */
    @Test(groups = "fast")
    public void testAPluginThatThrowsDoesNotStopTheOthers() {
        register("exploding", new IllegalStateException("boom"));
        final Recorder healthy = register("healthy");

        dispatch(event());

        assertEquals(healthy.seen.size(), 1,
                     "A plugin that threw must not prevent delivery to the rest");
    }

    /**
     * The exception to that rule. {@code NotificationPluginApiRetryException} is how a plugin asks
     * for redelivery; swallowing it the way an ordinary failure is swallowed would silently drop the
     * event the plugin asked to see again.
     */
    @Test(groups = "fast")
    public void testARetryRequestPropagatesRatherThanBeingSkipped() {
        register("wants-retry", new NotificationPluginApiRetryException());

        final NotificationPluginApiRetryException thrown =
                expectThrows(NotificationPluginApiRetryException.class, () -> dispatch(event()));

        assertTrue(thrown instanceof NotificationPluginApiRetryException);
    }

    /**
     * The registry is read per event rather than cached at construction, so a plugin that starts
     * after the dispatcher was built still receives events -- which is every plugin, since the
     * dispatcher is created during injector construction and plugins start later.
     */
    @Test(groups = "fast")
    public void testAPluginRegisteredLaterStillReceivesEvents() {
        dispatch(event());

        final Recorder late = register("late");
        final ExtBusEvent second = event();
        dispatch(second);

        assertEquals(late.seen, List.of(second));
    }

    @Test(groups = "fast")
    public void testAPluginThatUnregisteredStopsReceivingEvents() {
        final Recorder leaving = register("leaving");
        dispatch(event());
        registry.unregisterService("leaving");

        dispatch(event());

        assertEquals(leaving.seen.size(), 1, "An unregistered plugin must stop receiving events");
    }

    /**
     * Calls the same fan-out the dispatcher performs on each event.
     * <p>
     * Reflection on the private method rather than a visibility change: the alternative is widening
     * the dispatcher's API for a test, which would suggest the fan-out is something callers may
     * drive. It is not -- only the retryable subscriber invokes it.
     */
    private void dispatch(final ExtBusEvent event) {
        try {
            final Method method = PluginEventDispatcher.class.getDeclaredMethod("dispatch", ExtBusEvent.class);
            method.setAccessible(true);
            method.invoke(dispatcher(), event);
        } catch (final java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private PluginEventDispatcher dispatcher() {
        return new PluginEventDispatcher(stub(org.killbill.bus.api.PersistentBus.class),
                                         registry,
                                         stub(org.killbill.notificationq.api.NotificationQueueService.class),
                                         new org.killbill.clock.DefaultClock());
    }

    private Recorder register(final String name) {
        return register(name, null);
    }

    private Recorder register(final String name, final RuntimeException failure) {
        final Recorder recorder = new Recorder(failure);
        registry.registerService(new DefaultPluginServiceDescriptor(name, "1.0.0", name), recorder);
        return recorder;
    }

    private static ExtBusEvent event() {
        final UUID token = UUID.randomUUID();
        return (ExtBusEvent) Proxy.newProxyInstance(
                TestPluginEventDispatcher.class.getClassLoader(),
                new Class<?>[]{ExtBusEvent.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getEventType" -> ExtBusEventType.INVOICE_CREATION;
                    case "getObjectType" -> ObjectType.INVOICE;
                    case "getUserToken" -> token;
                    case "toString" -> "ExtBusEvent(" + token + ')';
                    case "hashCode" -> token.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(final Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(),
                                          new Class<?>[]{type},
                                          (proxy, method, args) -> null);
    }

    private static final class Recorder implements NotificationPluginApi {

        private final List<ExtBusEvent> seen = new CopyOnWriteArrayList<>();
        private final RuntimeException failure;

        Recorder(final RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public void onEvent(final ExtBusEvent event) {
            seen.add(event);
            if (failure != null) {
                throw failure;
            }
        }
    }
}

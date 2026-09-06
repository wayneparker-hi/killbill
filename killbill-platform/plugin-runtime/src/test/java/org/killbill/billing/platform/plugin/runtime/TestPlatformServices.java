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

import java.lang.reflect.Proxy;
import java.util.Map;

import org.killbill.billing.account.api.AccountUserApi;
import org.killbill.billing.overdue.api.OverdueApi;
import org.killbill.billing.payment.api.PaymentApi;
import org.killbill.clock.Clock;
import org.killbill.clock.DefaultClock;
import org.testng.annotations.Test;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Stage;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/**
 * What plugins can reach, and -- just as importantly -- what happens when a deployment does not have
 * something on the list.
 * <p>
 * The failure this guards against is silent: drop a type from {@code PUBLISHED} and no build breaks,
 * no test fails, and the only symptom is a plugin throwing at {@code getPlatformService} in
 * production. The OSGi layer had the mirror-image problem, failing to start a deployment that was
 * missing any one of twenty-one APIs.
 */
public class TestPlatformServices {

    /**
     * A deployment binding everything gets everything. Asserting the instance identity rather than
     * just presence, because publishing a <em>different</em> instance than the platform uses would
     * be worse than publishing nothing: the plugin would meter into a registry nobody reads.
     */
    @Test(groups = "fast")
    public void testPublishesWhatTheDeploymentBinds() {
        final AccountUserApi accountApi = stub(AccountUserApi.class);
        final Clock clock = new DefaultClock();

        final Map<Class<?>, Object> published = PlatformServices.resolve(injectorBinding(binder -> {
            binder.bind(AccountUserApi.class).toInstance(accountApi);
            binder.bind(Clock.class).toInstance(clock);
        }));

        assertSame(published.get(AccountUserApi.class), accountApi);
        assertSame(published.get(Clock.class), clock);
    }

    /**
     * The property that lets one plugin runtime serve every profile: a payment-only deployment has
     * no {@code OverdueApi}, and asking for bindings that do not exist must not fail the boot.
     */
    @Test(groups = "fast")
    public void testSkipsTypesTheDeploymentDoesNotBind() {
        final Map<Class<?>, Object> published = PlatformServices.resolve(injectorBinding(binder ->
                binder.bind(PaymentApi.class).toInstance(stub(PaymentApi.class))));

        assertTrue(published.containsKey(PaymentApi.class));
        assertFalse(published.containsKey(OverdueApi.class),
                    "An unbound API must be skipped, not published as null");
        assertFalse(published.containsKey(AccountUserApi.class));
    }

    /**
     * {@code getExistingBinding} is used rather than {@code getInstance} precisely so that asking
     * about an absent type stays a question. If it ever became a just-in-time binding, an empty
     * deployment would start publishing implementations nobody configured.
     */
    @Test(groups = "fast")
    public void testAskingDoesNotCreateBindings() {
        final Injector injector = injectorBinding(binder -> { });

        assertEquals(PlatformServices.resolve(injector), Map.of());
        assertEquals(injector.getExistingBinding(com.google.inject.Key.get(AccountUserApi.class)), null,
                     "Resolving must not leave a just-in-time binding behind");
    }

    /**
     * A binding that exists but blows up when provisioned takes only itself out, not the runtime.
     * <p>
     * Without this, one misconfigured API would stop every plugin from starting -- including the
     * ones that never asked for it.
     */
    @Test(groups = "fast")
    public void testABindingThatCannotBeProvisionedIsSkippedRatherThanFatal() {
        final Map<Class<?>, Object> published = PlatformServices.resolve(injectorBinding(binder -> {
            binder.bind(AccountUserApi.class).toProvider(() -> {
                throw new IllegalStateException("misconfigured");
            });
            binder.bind(Clock.class).toInstance(new DefaultClock());
        }));

        assertFalse(published.containsKey(AccountUserApi.class));
        assertTrue(published.containsKey(Clock.class),
                   "A broken binding must not take the healthy ones down with it");
    }

    private static Injector injectorBinding(final java.util.function.Consumer<com.google.inject.Binder> bindings) {
        return Guice.createInjector(Stage.PRODUCTION, new AbstractModule() {
            @Override
            protected void configure() {
                bindings.accept(binder());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(final Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(),
                                          new Class<?>[]{type},
                                          (proxy, method, args) -> null);
    }
}

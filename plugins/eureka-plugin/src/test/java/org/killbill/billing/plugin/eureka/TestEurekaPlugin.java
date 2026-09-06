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

package org.killbill.billing.plugin.eureka;

import java.nio.file.Path;
import java.util.Map;

import org.killbill.billing.lpr.api.PluginState;
import org.killbill.billing.lpr.testkit.PluginTestRuntime;
import org.killbill.billing.platform.api.KillbillConfigSource;
import org.killbill.billing.runtime.api.ServiceDiscoveryRegistry;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Registers this node with a Eureka service-discovery server and publishes a
 * {@link ServiceDiscoveryRegistry}.
 * <p>
 * Only the disabled path is exercised, deliberately. Unlike the metrics reporters -- whose senders
 * connect lazily, so they can be started against a port with nothing behind it -- Eureka's
 * {@code DiscoveryClient} contacts the server during construction and retries on a schedule. A test
 * that enabled it would either need a Eureka server or would spend its time timing out, and would be
 * testing Netflix's client rather than this plugin.
 * <p>
 * That leaves one property worth pinning down here, and it is the one that actually breaks
 * deployments: <b>disabled must mean "starts and does nothing", not "fails to start"</b>. A
 * deployment ships every optional plugin and enables the few it wants; a plugin that threw when
 * disabled would sit in {@code FAILED}, and were it listed as mandatory it would take the boot down.
 */
public class TestEurekaPlugin {

    private static final String PLUGIN_ID = "killbill-eureka";
    private static final String ENABLED = "org.killbill.eureka";

    private PluginTestRuntime runtime;

    @AfterMethod(groups = "fast", alwaysRun = true)
    public void afterMethod() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test(groups = "fast")
    public void testItStartsCleanlyWhenDisabled() {
        start(Map.of());

        assertEquals(runtime.state(PLUGIN_ID).orElseThrow(), PluginState.ACTIVE,
                     "A disabled plugin must still reach ACTIVE, not FAILED");
    }

    /**
     * A disabled plugin publishes nothing. Registering a {@code ServiceDiscoveryRegistry} that is
     * not backed by a live Eureka client would be worse than registering none: callers would find
     * one and get silence.
     */
    @Test(groups = "fast")
    public void testItPublishesNothingWhenDisabled() {
        start(Map.of());

        assertTrue(runtime.services().getPluginIds(ServiceDiscoveryRegistry.class).isEmpty());
    }

    /**
     * Stopping a plugin that never started anything still has to unwind cleanly -- the lifecycle
     * runs the same teardown either way.
     */
    @Test(groups = "fast")
    public void testStoppingADisabledPluginIsClean() {
        start(Map.of());

        runtime.manager().stopAll();

        assertEquals(runtime.state(PLUGIN_ID).orElseThrow(), PluginState.STOPPED);
    }

    private void start(final Map<String, String> platformProperties) {
        runtime = PluginTestRuntime.create(
                Map.of(KillbillConfigSource.class, (KillbillConfigSource) platformProperties::get));
        runtime.installCompiled(Path.of("target", "classes"));
        runtime.discover();
        runtime.startAll();
    }
}

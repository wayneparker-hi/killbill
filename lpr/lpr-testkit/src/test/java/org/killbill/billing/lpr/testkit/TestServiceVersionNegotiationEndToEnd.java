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

package org.killbill.billing.lpr.testkit;

import org.killbill.billing.lpr.api.ServiceApiVersion;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class TestServiceVersionNegotiationEndToEnd {
    public interface Gateway { String value(); }
    public interface Query { String value(); }

    @Test(groups = "fast")
    public void testConsumerNegotiatesThroughRealClassLoadersAcrossUpgradeAndRollback() {
        try (PluginTestRuntime runtime = PluginTestRuntime.create()) {
            runtime.install(provider("9.0.0", "1.3.0"));
            runtime.install(PluginFixture.plugin("consumer", "1.0.0").entrypoint("example.Consumer", consumer()));
            runtime.startAll();
            assertEquals(runtime.service(Query.class, "consumer").value(), "9.0.0");
            assertTrue(runtime.services().getService(Gateway.class, "provider", ServiceApiVersion.parse("1.4.0")).isEmpty());
            runtime.install(provider("10.0.0", "2.0.0")); runtime.discover();
            runtime.manager().activateVersion("provider", "10.0.0");
            assertEquals(runtime.service(Query.class, "consumer").value(), "unavailable");
            assertEquals(runtime.services().getService(Gateway.class, "provider", ServiceApiVersion.parse("2.0.0")).orElseThrow().value(), "10.0.0");
            runtime.manager().rollback("provider");
            assertEquals(runtime.service(Query.class, "consumer").value(), "9.0.0");
            runtime.manager().stop("provider");
            assertEquals(runtime.service(Query.class, "consumer").value(), "unavailable");
        }
    }

    private static PluginFixture provider(final String version, final String api) {
        final String code = """
                package example;
                import org.killbill.billing.lpr.api.*;
                public class Provider implements Plugin {
                    public void start(PluginContext context) {
                        context.services().register(%s.class, () -> "%s", ServiceApiVersion.parse("%s"), java.util.Map.of());
                    }
                    public void stop() { }
                }
                """.formatted(Gateway.class.getCanonicalName(), version, api);
        return PluginFixture.plugin("provider", version).entrypoint("example.Provider", code);
    }

    private static String consumer() {
        return """
                package example;
                import org.killbill.billing.lpr.api.*;
                public class Consumer implements Plugin {
                    public void start(PluginContext context) {
                        context.services().register(%s.class, () -> context.services()
                            .getService(%s.class, "provider", ServiceApiVersion.parse("1.2.0"))
                            .map(gateway -> gateway.value()).orElse("unavailable"));
                    }
                    public void stop() { }
                }
                """.formatted(Query.class.getCanonicalName(), Gateway.class.getCanonicalName());
    }
}

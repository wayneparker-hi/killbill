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

package org.killbill.billing.platform.plugin.runtime.http;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.servlet.http.HttpServlet;

import org.killbill.billing.platform.plugin.api.DefaultPluginServiceDescriptor;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

/** Requests and lifecycle changes share this object, so reads must remain safe during mutation. */
public class TestPluginServletRouter {

    @Test(groups = "fast")
    public void testRequestsCanReadWhilePluginsRegisterAndUnregister() throws Exception {
        final PluginServletRouter router = new PluginServletRouter();
        final HttpServlet servlet = new HttpServlet() { };
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService executor = Executors.newFixedThreadPool(5);
        try {
            final List<Callable<Void>> readers = new ArrayList<>();
            for (int reader = 0; reader < 4; reader++) {
                readers.add(() -> {
                    start.await();
                    for (int i = 0; i < 2_000; i++) {
                        router.getServiceForPath("/plugin-" + i % 32 + "/health");
                        router.getAllServices().size();
                    }
                    return null;
                });
            }

            final Future<Void> writer = executor.submit(() -> {
                start.await();
                for (int i = 0; i < 2_000; i++) {
                    final String name = "plugin-" + i % 32;
                    router.registerService(new DefaultPluginServiceDescriptor(name, "1.0.0", name), servlet);
                    router.unregisterService(name);
                }
                return null;
            });
            final List<Future<Void>> readFutures = new ArrayList<>();
            for (final Callable<Void> reader : readers) {
                readFutures.add(executor.submit(reader));
            }

            start.countDown();
            writer.get();
            for (final Future<Void> reader : readFutures) {
                reader.get();
            }
            assertEquals(router.getAllServices().size(), 0);
        } finally {
            executor.shutdownNow();
        }
    }
}

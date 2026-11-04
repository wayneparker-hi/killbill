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

package org.killbill.billing.plugin.custominvoiceformatter;

import java.net.URL;
import java.util.Locale;
import java.util.Map;

import org.killbill.billing.lpr.classloader.DefaultPluginClassLoader;
import org.killbill.billing.lpr.spi.ClassLoaderPolicy;
import org.killbill.billing.plugin.notification.generator.TemplateRenderer;
import org.killbill.billing.runtime.api.NotificationInvoiceFormatterFactory;
import org.testng.annotations.Test;

import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;

public class TestSharedFormatterContract {
    @Test(groups = "fast")
    public void factoryAndConsumerShareOnlyTheParentContract() throws Exception {
        final URL custom = CustomInvoiceFormatterFactory.class.getProtectionDomain().getCodeSource().getLocation();
        final URL email = TemplateRenderer.class.getProtectionDomain().getCodeSource().getLocation();
        final ClassLoader parent = getClass().getClassLoader();
        try (DefaultPluginClassLoader provider = new DefaultPluginClassLoader("custom", new URL[] {custom, email}, parent, ClassLoaderPolicy.defaultPolicy());
             DefaultPluginClassLoader consumer = new DefaultPluginClassLoader("email", new URL[] {email}, parent, ClassLoaderPolicy.defaultPolicy())) {
            final Class<?> contract = consumer.loadClass(NotificationInvoiceFormatterFactory.class.getName());
            assertSame(contract, NotificationInvoiceFormatterFactory.class);
            final Class<?> implementation = provider.loadClass(CustomInvoiceFormatterFactory.class.getName());
            assertSame(implementation.getClassLoader(), provider);
            assertNotSame(implementation, CustomInvoiceFormatterFactory.class);
            final NotificationInvoiceFormatterFactory factory = (NotificationInvoiceFormatterFactory) implementation.getConstructor().newInstance();
            final var formatter = factory.createInvoiceFormatter(Map.of(), null, Locale.US, null);
            assertSame(formatter.getClass().getClassLoader(), provider);
            final Class<?> renderer = consumer.loadClass(TemplateRenderer.class.getName());
            renderer.getMethod("setInvoiceFormatterTracker", contract).invoke(renderer.getConstructors()[0].newInstance(null, null, null), factory);
        }
    }
}

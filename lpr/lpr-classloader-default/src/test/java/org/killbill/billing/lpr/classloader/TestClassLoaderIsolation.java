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

package org.killbill.billing.lpr.classloader;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import org.killbill.billing.lpr.api.Plugin;
import org.killbill.billing.lpr.spi.ClassLoaderPolicy;
import org.killbill.billing.lpr.spi.PluginArtifact;
import org.killbill.billing.lpr.spi.PluginClassLoaderFactory;
import org.killbill.billing.lpr.spi.PluginClassLoaderHandle;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * The correctness test for the whole migration.
 * <p>
 * Replacing OSGi is only defensible if a plain ClassLoader can hold both halves of a contradiction
 * at once: two plugins must see <em>different</em> copies of a library they disagree about, while
 * seeing the <em>same</em> copy of the API types they talk to the core through. Get the first wrong
 * and plugins cannot ship their own dependencies; get the second wrong and every call across the
 * boundary dies with {@code ClassCastException}.
 * <p>
 * Every backend implementing {@code PluginClassLoaderFactory} has to pass this.
 */
public class TestClassLoaderIsolation {

    private static final String GREETER = "com.example.lib.Greeter";
    private static final String PLUGIN_A = "com.example.plugina.APlugin";
    private static final String PLUGIN_B = "com.example.pluginb.BPlugin";

    private final PluginClassLoaderFactory factory = new DefaultPluginClassLoaderFactory();

    private Path workDir;
    private PluginClassLoaderHandle handleA;
    private PluginClassLoaderHandle handleB;

    @BeforeClass(groups = "fast")
    public void beforeClass() throws Exception {
        workDir = Files.createTempDirectory("lpr-isolation-");

        // Two shaded jars, each carrying its own com.example.lib.Greeter. This is how Kill Bill
        // plugins are actually built: the OSGi bundle plugin inlined every dependency, so a
        // plugin's libraries are private to it by construction.
        final Path jarA = TestPluginJarBuilder.newJar()
                                              .withClass(GREETER, greeterSource("v1"))
                                              .withClass(PLUGIN_A, pluginSource("com.example.plugina", "APlugin"))
                                              .buildInto(workDir, "plugin-a.jar");

        final Path jarB = TestPluginJarBuilder.newJar()
                                              .withClass(GREETER, greeterSource("v2"))
                                              .withClass(PLUGIN_B, pluginSource("com.example.pluginb", "BPlugin"))
                                              .buildInto(workDir, "plugin-b.jar");

        final ClassLoaderPolicy policy = ClassLoaderPolicy.defaultPolicy();
        final ClassLoader parent = getClass().getClassLoader();

        handleA = factory.create(PluginArtifact.of("plugin-a", "1.0.0", jarA), policy, parent);
        handleB = factory.create(PluginArtifact.of("plugin-b", "1.0.0", jarB), policy, parent);
    }

    @AfterClass(groups = "fast", alwaysRun = true)
    public void afterClass() {
        if (handleA != null) {
            handleA.close();
        }
        if (handleB != null) {
            handleB.close();
        }
    }

    /**
     * Two plugins depending on incompatible versions of the same library must both work. This is
     * the capability OSGi was carrying for Kill Bill, and the reason plain parent-first delegation
     * is not an option.
     */
    @Test(groups = "fast")
    public void testConflictingLibraryVersionsCoexist() throws Exception {
        final Class<?> greeterA = handleA.classLoader().loadClass(GREETER);
        final Class<?> greeterB = handleB.classLoader().loadClass(GREETER);

        assertNotSame(greeterA, greeterB,
                      "Same class name resolved to one Class object: the plugins are sharing a classpath, not isolated");
        assertSame(greeterA.getClassLoader(), handleA.classLoader());
        assertSame(greeterB.getClassLoader(), handleB.classLoader());

        assertEquals(invokeGreet(greeterA), "v1");
        assertEquals(invokeGreet(greeterB), "v2");
    }

    /**
     * The other half. The core hands a plugin a {@code PluginContext} and calls it back through
     * {@code Plugin}; if those interfaces resolved to the plugin's own copies, the cast below would
     * throw and nothing would ever cross the boundary.
     */
    @Test(groups = "fast")
    public void testApiTypesAreSharedWithTheCore() throws Exception {
        final Class<?> apiFromPluginA = handleA.classLoader().loadClass(Plugin.class.getName());
        final Class<?> apiFromPluginB = handleB.classLoader().loadClass(Plugin.class.getName());

        assertSame(apiFromPluginA, Plugin.class, "Plugin API resolved to a plugin-local copy");
        assertSame(apiFromPluginB, Plugin.class, "Plugin API resolved to a plugin-local copy");

        // The assignment, not the assertion, is the real test: it is the exact operation the
        // runtime performs when it instantiates a plugin's entrypoint.
        final Plugin instanceA = (Plugin) handleA.classLoader()
                                                 .loadClass(PLUGIN_A)
                                                 .getDeclaredConstructor()
                                                 .newInstance();
        assertTrue(Plugin.class.isInstance(instanceA));
    }

    /**
     * A plugin shipping its own copy of an API class must not be able to shadow the runtime's.
     * Without this, one badly built plugin -- an over-eager shade configuration is enough -- would
     * silently get incompatible types and fail at the first call rather than at load time.
     */
    @Test(groups = "fast")
    public void testPluginCannotShadowApiTypes() throws Exception {
        final Path jar = TestPluginJarBuilder.newJar()
                                             .withClass(Plugin.class.getName(), forgedPluginApiSource())
                                             .buildInto(workDir, "plugin-shadowing.jar");

        final PluginClassLoaderHandle handle =
                factory.create(PluginArtifact.of("plugin-shadowing", "1.0.0", jar),
                               ClassLoaderPolicy.defaultPolicy(),
                               getClass().getClassLoader());
        try {
            assertSame(handle.classLoader().loadClass(Plugin.class.getName()), Plugin.class,
                       "A plugin-supplied copy of an API type won over the runtime's");
        } finally {
            handle.close();
        }
    }

    /**
     * Isolation has to hold in both directions: a plugin's own classes stay invisible to the core
     * and to other plugins, so nothing can accidentally couple to a plugin's internals.
     */
    @Test(groups = "fast")
    public void testPluginClassesAreNotVisibleOutsideTheirPlugin() {
        assertNotLoadable(getClass().getClassLoader(), PLUGIN_A, "the core");
        assertNotLoadable(handleB.classLoader(), PLUGIN_A, "another plugin");
        assertNotLoadable(handleA.classLoader(), PLUGIN_B, "another plugin");
    }

    /**
     * Each call produces an independent ClassLoader, which is what lets the runtime start a new
     * version alongside the running one and switch atomically instead of stopping first and
     * leaving a window with no provider.
     */
    @Test(groups = "fast")
    public void testSameArtifactLoadedTwiceYieldsIndependentClassLoaders() throws Exception {
        final Path jar = TestPluginJarBuilder.newJar()
                                             .withClass(GREETER, greeterSource("side-by-side"))
                                             .buildInto(workDir, "plugin-sxs.jar");
        final PluginArtifact artifact = PluginArtifact.of("plugin-sxs", "1.0.0", jar);
        final ClassLoaderPolicy policy = ClassLoaderPolicy.defaultPolicy();

        final PluginClassLoaderHandle first = factory.create(artifact, policy, getClass().getClassLoader());
        final PluginClassLoaderHandle second = factory.create(artifact, policy, getClass().getClassLoader());
        try {
            assertNotSame(first.classLoader(), second.classLoader());
            assertNotSame(first.classLoader().loadClass(GREETER), second.classLoader().loadClass(GREETER));
        } finally {
            first.close();
            second.close();
        }
    }

    private void assertNotLoadable(final ClassLoader classLoader, final String className, final String who) {
        try {
            classLoader.loadClass(className);
            fail(className + " should not be reachable from " + who);
        } catch (final ClassNotFoundException expected) {
            // This is the isolation working.
        }
    }

    private String invokeGreet(final Class<?> greeterClass) throws Exception {
        final Object greeter = greeterClass.getDeclaredConstructor().newInstance();
        final Method greet = greeterClass.getMethod("greet");
        return (String) greet.invoke(greeter);
    }

    private static String greeterSource(final String version) {
        return "package com.example.lib;\n"
               + "public class Greeter {\n"
               + "    public String greet() { return \"" + version + "\"; }\n"
               + "}\n";
    }

    private static String pluginSource(final String packageName, final String simpleName) {
        return "package " + packageName + ";\n"
               + "import org.killbill.billing.lpr.api.Plugin;\n"
               + "import org.killbill.billing.lpr.api.PluginContext;\n"
               + "import com.example.lib.Greeter;\n"
               + "public class " + simpleName + " implements Plugin {\n"
               + "    @Override public void start(PluginContext context) { }\n"
               + "    @Override public void stop() { }\n"
               + "    public String greet() { return new Greeter().greet(); }\n"
               + "}\n";
    }

    /** A plugin-local class squatting on the API's fully qualified name. */
    private static String forgedPluginApiSource() {
        return "package org.killbill.billing.lpr.api;\n"
               + "public interface Plugin {\n"
               + "    void somethingElseEntirely();\n"
               + "}\n";
    }
}

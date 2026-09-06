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

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Builds throwaway plugin jars from Java source held in the test itself.
 * <p>
 * The alternative -- real Maven modules producing fixture jars -- would put the thing under test
 * (which classes end up in which jar) into build configuration, where it is invisible from the test
 * and easy to break by accident. Compiling here keeps each scenario readable in one file, and lets
 * two fixtures declare the same class with different behaviour, which is exactly what the isolation
 * test needs and what a shared Maven module cannot express.
 */
final class TestPluginJarBuilder {

    private final Map<String, String> sourcesByClassName = new LinkedHashMap<>();

    private TestPluginJarBuilder() {
    }

    static TestPluginJarBuilder newJar() {
        return new TestPluginJarBuilder();
    }

    /**
     * @param className fully qualified name, matching the declaration in {@code source}
     * @param source    complete compilation unit
     */
    TestPluginJarBuilder withClass(final String className, final String source) {
        sourcesByClassName.put(className, source);
        return this;
    }

    /**
     * Compiles everything added so far and packages it into a jar.
     * <p>
     * The compile classpath is the test's own, so fixture classes can implement the runtime's API
     * types. That is a compile-time convenience only: at run time the jar is reached through a
     * plugin ClassLoader, which is what decides whether those types are shared or shadowed.
     *
     * @param targetDir directory to write the jar into
     * @param jarName   file name of the jar
     * @return the jar path
     */
    Path buildInto(final Path targetDir, final String jarName) {
        try {
            final Path classesDir = Files.createTempDirectory(targetDir, "classes-");
            compile(classesDir);
            return packageJar(classesDir, targetDir.resolve(jarName));
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to build test jar " + jarName, e);
        }
    }

    private void compile(final Path classesDir) throws IOException {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No JDK compiler available; tests must run on a JDK, not a JRE");
        }

        final List<JavaFileObject> units = new ArrayList<>(sourcesByClassName.size());
        sourcesByClassName.forEach((className, source) -> units.add(new InMemorySource(className, source)));

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            final List<String> options = List.of(
                    "-classpath", System.getProperty("java.class.path"),
                    "-d", classesDir.toString()
            );
            final boolean ok = compiler.getTask(null, fileManager, null, options, null, units).call();
            if (!ok) {
                throw new IllegalStateException("Failed to compile test plugin sources: "
                                                + sourcesByClassName.keySet());
            }
        }
    }

    private Path packageJar(final Path classesDir, final Path jarPath) throws IOException {
        try (OutputStream out = Files.newOutputStream(jarPath);
             JarOutputStream jar = new JarOutputStream(out)) {

            Files.walkFileTree(classesDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    final String entryName = classesDir.relativize(file).toString().replace('\\', '/');
                    jar.putNextEntry(new JarEntry(entryName));
                    Files.copy(file, jar);
                    jar.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return jarPath;
    }

    private static final class InMemorySource extends SimpleJavaFileObject {

        private final String source;

        private InMemorySource(final String className, final String source) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return source;
        }
    }
}

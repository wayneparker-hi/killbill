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
 * Compiles Java source into a plugin jar.
 * <p>
 * Produces a shaded jar: everything the fixture declares goes into one archive, matching how Kill
 * Bill plugins are actually built and therefore how the ClassLoader will actually see them.
 */
public final class PluginJarBuilder {

    /**
     * Packages an already-compiled class directory, typically a Maven module's
     * {@code target/classes}.
     * <p>
     * Lets a real plugin be tested as the artifact it ships as, rather than as classes that happen
     * to sit on the test classpath. That distinction is the whole point here: on the classpath a
     * plugin's classes are loaded by the application ClassLoader and everything trivially works;
     * inside a jar they go through the plugin ClassLoader, which is what production does and what
     * can actually fail.
     *
     * @param classesDir directory of compiled classes
     * @param jarPath    where to write the jar; its parent must exist
     * @return {@code jarPath}
     */
    public Path packageDirectory(final Path classesDir, final Path jarPath) {
        if (!Files.isDirectory(classesDir)) {
            throw new IllegalArgumentException("Not a directory of compiled classes: " + classesDir
                                               + " (has the module been compiled?)");
        }
        try {
            return packageJar(classesDir, jarPath);
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot package " + classesDir + " into " + jarPath, e);
        }
    }

    /**
     * @param sourcesByClassName fully qualified class name to compilation unit
     * @param jarPath            where to write the jar; its parent must exist
     * @return {@code jarPath}
     */
    public Path build(final Map<String, String> sourcesByClassName, final Path jarPath) {
        if (sourcesByClassName.isEmpty()) {
            throw new IllegalArgumentException("A plugin jar needs at least one class");
        }
        try {
            final Path classesDir = Files.createTempDirectory("lpr-classes-");
            try {
                compile(sourcesByClassName, classesDir);
                return packageJar(classesDir, jarPath);
            } finally {
                deleteRecursively(classesDir);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot build plugin jar " + jarPath, e);
        }
    }

    /**
     * Compiles against the caller's classpath, so fixtures can implement the runtime's API types.
     * That is a compile-time convenience only: at run time the jar is reached through a plugin
     * ClassLoader, which decides for itself whether those types are shared or shadowed.
     */
    private void compile(final Map<String, String> sourcesByClassName, final Path classesDir) throws IOException {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No JDK compiler available; the testkit must run on a JDK, not a JRE");
        }

        final List<JavaFileObject> units = new ArrayList<>(sourcesByClassName.size());
        sourcesByClassName.forEach((className, source) -> units.add(new InMemorySource(className, source)));

        final StringWriterCollector diagnostics = new StringWriterCollector();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            final List<String> options = List.of("-classpath", System.getProperty("java.class.path"),
                                                 "-d", classesDir.toString());
            final boolean ok = compiler.getTask(diagnostics, fileManager, null, options, null, units).call();
            if (!ok) {
                throw new IllegalStateException("Cannot compile plugin sources "
                                                + sourcesByClassName.keySet() + ":\n" + diagnostics);
            }
        }
    }

    private Path packageJar(final Path classesDir, final Path jarPath) throws IOException {
        try (OutputStream out = Files.newOutputStream(jarPath);
             JarOutputStream jar = new JarOutputStream(out)) {

            Files.walkFileTree(classesDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    jar.putNextEntry(new JarEntry(classesDir.relativize(file).toString().replace('\\', '/')));
                    Files.copy(file, jar);
                    jar.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return jarPath;
    }

    private static void deleteRecursively(final Path directory) {
        try (var entries = Files.walk(directory)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (final IOException ignored) {
                    path.toFile().deleteOnExit();
                }
            });
        } catch (final IOException ignored) {
            directory.toFile().deleteOnExit();
        }
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

    /** Collects compiler output so a failed fixture reports why, not just that. */
    private static final class StringWriterCollector extends java.io.Writer {

        private final StringBuilder text = new StringBuilder();

        @Override
        public void write(final char[] buffer, final int offset, final int length) {
            text.append(buffer, offset, length);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}

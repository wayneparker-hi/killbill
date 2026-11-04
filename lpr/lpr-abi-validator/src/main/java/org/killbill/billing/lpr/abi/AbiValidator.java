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
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.killbill.billing.lpr.abi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Static ABI check for a plugin jar.
 * <p>
 * Two rules, both about what crosses the plugin boundary:
 * <ol>
 *   <li>A type in a service method signature must be supplied by the platform. If it comes from a
 *       dependency that is not {@code provided}, the plugin will either bundle a second copy (and
 *       get {@code ClassCastException} at the boundary) or fail to link at runtime.</li>
 *   <li>The same class must not appear in more than one dependency scope. A compile-scope jar that
 *       also ships a class the platform provides is the same failure with a longer fuse.</li>
 * </ol>
 * The tool is a plain {@code main} rather than a Maven plugin so a build can invoke it through any
 * runner, and has no third-party dependencies so it cannot itself introduce a classpath conflict.
 */
public final class AbiValidator {

    private static final List<String> DEFAULT_SERVICE_API_PREFIXES = List.of(
            "org.killbill.billing.payment.plugin.api.",
            "org.killbill.billing.invoice.plugin.api.",
            "org.killbill.billing.catalog.plugin.api.",
            "org.killbill.billing.entitlement.plugin.api.",
            "org.killbill.billing.usage.plugin.api.",
            "org.killbill.billing.overdue.plugin.api.",
            "org.killbill.billing.notification.plugin.api.",
            "org.killbill.billing.lpr.api.",
            "org.killbill.billing.runtime.api.",
            "org.killbill.billing.currency.plugin.api.",
            "org.killbill.billing.control.plugin.api.",
            "jakarta.servlet.",
            "javax.servlet."
    );

    private static final List<String> DEFAULT_DUPLICATE_IGNORES = List.of(
            "javax.annotation.",
            "jakarta.annotation.",
            "org.jetbrains.annotations.",
            "org.checkerframework.",
            "com.google.errorprone.annotations.",
            "com.google.j2objc.annotations."
    );

    private AbiValidator() {
    }

    public static void main(final String[] args) {
        final Options options;
        try {
            options = Options.parse(args);
        } catch (final IllegalArgumentException e) {
            System.err.println("ABI check: " + e.getMessage());
            System.exit(2);
            return;
        }

        if (!Files.isRegularFile(options.jar())) {
            System.err.println("Plugin ABI jar is missing: " + options.jar());
            System.exit(2);
            return;
        }

        try {
            final Result result = validate(options);
            result.print();
            if (!result.violations().isEmpty()) {
                System.exit(1);
            }
        } catch (final IOException e) {
            System.err.println("Plugin ABI check failed to read " + options.jar() + ": " + e.getMessage());
            System.exit(2);
        }
    }

    static Result validate(final Options options) throws IOException {
        final Map<String, Set<Path>> providedTypes = new TreeMap<>();
        final Map<String, Set<Path>> runtimeTypes = new TreeMap<>();
        final Set<Path> providedJars = new LinkedHashSet<>();
        final Set<Path> runtimeJars = new LinkedHashSet<>();

        indexClasspath(options.providedClasspathFile(), providedTypes, providedJars);
        indexClasspath(options.runtimeClasspathFile(), runtimeTypes, runtimeJars);

        final Set<Path> providedOnlyJars = difference(providedJars, runtimeJars);
        final Set<Path> runtimeOnlyJars = difference(runtimeJars, providedJars);

        final Set<String> pluginClasses = new LinkedHashSet<>();
        final List<ClassFileParser.ClassInfo> classes = new ArrayList<>();
        readPluginJar(options.jar(), pluginClasses, classes);

        final List<Violation> violations = new ArrayList<>();
        violations.addAll(checkServiceSignatures(classes, pluginClasses, providedTypes, runtimeTypes,
                                                 options.serviceApiPrefixes()));
        violations.addAll(checkDuplicates(options.jar(), pluginClasses, providedTypes, runtimeTypes,
                                          providedOnlyJars, runtimeOnlyJars,
                                          options.duplicateIgnores()));

        return new Result(options.jar(), classes.size(), violations);
    }

    private static List<Violation> checkServiceSignatures(final List<ClassFileParser.ClassInfo> classes,
                                                          final Set<String> pluginClasses,
                                                          final Map<String, Set<Path>> providedTypes,
                                                          final Map<String, Set<Path>> runtimeTypes,
                                                          final List<String> serviceApiPrefixes) throws IOException {
        return new ServiceAbiInspector(classes, providedTypes, runtimeTypes, serviceApiPrefixes).validate();
    }

    private static List<Violation> checkDuplicates(final Path pluginJar,
                                                   final Set<String> pluginClasses,
                                                   final Map<String, Set<Path>> providedTypes,
                                                   final Map<String, Set<Path>> runtimeTypes,
                                                   final Set<Path> providedOnlyJars,
                                                   final Set<Path> runtimeOnlyJars,
                                                   final List<String> duplicateIgnores) {
        final List<Violation> violations = new ArrayList<>();

        // A class bundled by both the plugin and the platform is the shaded-jar case: the plugin
        // will load its own copy and the two sides will not agree on the Class object.
        for (final String type : new TreeSet<>(pluginClasses)) {
            if (isIgnoredDuplicate(type, duplicateIgnores)) {
                continue;
            }
            final Set<Path> providedSources = providedTypes.get(type);
            if (providedSources != null && !providedSources.isEmpty()) {
                violations.add(Violation.shaded(pluginJar, type, providedSources));
            }
        }

        // Duplicates across dependency scopes: a runtime-only jar and a provided-only jar both
        // shipping the same class means the plugin can silently shadow the platform's copy.
        final Set<String> allTypes = new TreeSet<>();
        allTypes.addAll(providedTypes.keySet());
        allTypes.addAll(runtimeTypes.keySet());
        for (final String type : allTypes) {
            if (isIgnoredDuplicate(type, duplicateIgnores)) {
                continue;
            }
            final Set<Path> sources = new LinkedHashSet<>();
            sources.addAll(providedTypes.getOrDefault(type, Set.of()));
            sources.addAll(runtimeTypes.getOrDefault(type, Set.of()));

            final Set<Path> relevant = new LinkedHashSet<>();
            for (final Path source : sources) {
                if (providedOnlyJars.contains(source) || runtimeOnlyJars.contains(source)) {
                    relevant.add(source);
                }
            }
            if (relevant.size() > 1 && runtimeOnlyJars.stream().anyMatch(relevant::contains)) {
                violations.add(Violation.duplicate(type, relevant));
            }
        }
        return violations;
    }

    private static boolean isIgnoredDuplicate(final String type, final List<String> duplicateIgnores) {
        if (type.startsWith("META-INF.") || type.endsWith(".module-info") || type.endsWith(".package-info")) {
            return true;
        }
        return duplicateIgnores.stream().anyMatch(type::startsWith);
    }

    private static void indexClasspath(final Path classpathFile,
                                       final Map<String, Set<Path>> types,
                                       final Set<Path> jars) throws IOException {
        if (classpathFile == null || !Files.isRegularFile(classpathFile)) {
            throw new IOException("Required classpath file is missing: " + classpathFile);
        }
        final String content = Files.readString(classpathFile).trim();
        if (content.isEmpty()) {
            return;
        }
        for (final String entry : content.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (entry.isBlank()) {
                continue;
            }
            final Path path = Path.of(entry.trim());
            if (!Files.exists(path)) {
                throw new IOException("Classpath entry does not exist: " + path);
            }
            if (Files.isDirectory(path)) {
                jars.add(path.toAbsolutePath().normalize());
                indexDirectory(path.toAbsolutePath().normalize(), types);
            } else {
                jars.add(path.toAbsolutePath().normalize());
                indexJar(path, types);
            }
        }
    }

    private static void indexDirectory(final Path directory, final Map<String, Set<Path>> types) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            for (final Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                final String relative = directory.relativize(file).toString().replace('\\', '/');
                if (isIgnoredClassEntry(relative)) {
                    continue;
                }
                final String type = relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
                types.computeIfAbsent(type, ignored -> new LinkedHashSet<>()).add(directory);
            }
        }
    }

    private static void indexJar(final Path jar, final Map<String, Set<Path>> types) throws IOException {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            final var entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                final JarEntry entry = entries.nextElement();
                final String name = entry.getName();
                if (!name.endsWith(".class") || isIgnoredClassEntry(name)) {
                    continue;
                }
                final String type = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                types.computeIfAbsent(type, ignored -> new LinkedHashSet<>()).add(jar.toAbsolutePath().normalize());
            }
        }
    }

    private static boolean isIgnoredClassEntry(final String name) {
        return name.startsWith("META-INF/")
               || name.equals("module-info.class")
               || name.endsWith("/module-info.class")
               || name.endsWith("/package-info.class");
    }

    private static void readPluginJar(final Path jar,
                                      final Set<String> pluginClasses,
                                      final List<ClassFileParser.ClassInfo> classes) throws IOException {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            final var entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                final JarEntry entry = entries.nextElement();
                final String name = entry.getName();
                if (!name.endsWith(".class") || isIgnoredClassEntry(name)) {
                    continue;
                }
                pluginClasses.add(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
                try (InputStream in = jarFile.getInputStream(entry)) {
                    classes.add(ClassFileParser.parse(in));
                }
            }
        }
    }

    private static Set<Path> difference(final Set<Path> left, final Set<Path> right) {
        final Set<Path> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }

    static final class Options {

        private final Path jar;
        private final Path providedClasspathFile;
        private final Path runtimeClasspathFile;
        private final List<String> serviceApiPrefixes;
        private final List<String> duplicateIgnores;

        private Options(final Path jar,
                        final Path providedClasspathFile,
                        final Path runtimeClasspathFile,
                        final List<String> serviceApiPrefixes,
                        final List<String> duplicateIgnores) {
            this.jar = jar;
            this.providedClasspathFile = providedClasspathFile;
            this.runtimeClasspathFile = runtimeClasspathFile;
            this.serviceApiPrefixes = List.copyOf(serviceApiPrefixes);
            this.duplicateIgnores = List.copyOf(duplicateIgnores);
        }

        static Options parse(final String[] args) {
            Path jar = null;
            Path provided = null;
            Path runtime = null;
            final List<String> prefixes = new ArrayList<>();
            final List<String> ignores = new ArrayList<>();

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--jar" -> jar = Path.of(requireValue(args, ++i, "--jar"));
                    case "--provided-classpath-file" -> provided = Path.of(requireValue(args, ++i, "--provided-classpath-file"));
                    case "--runtime-classpath-file" -> runtime = Path.of(requireValue(args, ++i, "--runtime-classpath-file"));
                    case "--service-api-prefix" -> prefixes.add(requireValue(args, ++i, "--service-api-prefix"));
                    case "--ignore-duplicate-prefix" -> ignores.add(requireValue(args, ++i, "--ignore-duplicate-prefix"));
                    default -> throw new IllegalArgumentException("Unknown argument " + args[i]);
                }
            }
            if (jar == null || provided == null || runtime == null) {
                throw new IllegalArgumentException("--jar, --provided-classpath-file and --runtime-classpath-file are required");
            }
            return new Options(jar,
                               provided,
                               runtime,
                               prefixes.isEmpty() ? DEFAULT_SERVICE_API_PREFIXES : prefixes,
                               ignores.isEmpty() ? DEFAULT_DUPLICATE_IGNORES : ignores);
        }

        private static String requireValue(final String[] args, final int index, final String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }

        Path jar() {
            return jar;
        }

        Path providedClasspathFile() {
            return providedClasspathFile;
        }

        Path runtimeClasspathFile() {
            return runtimeClasspathFile;
        }

        List<String> serviceApiPrefixes() {
            return serviceApiPrefixes;
        }

        List<String> duplicateIgnores() {
            return duplicateIgnores;
        }
    }

    record Violation(Type type, String owner, String method, String descriptor, String className,
                     Set<Path> sources) {

        enum Type {
            ABI,
            SHADED,
            DUPLICATE
        }

        static Violation abi(final String owner,
                             final String method,
                             final String descriptor,
                             final String className,
                             final Set<Path> sources) {
            return new Violation(Type.ABI, owner, method, descriptor, className, Set.copyOf(sources));
        }

        static Violation shaded(final Path pluginJar, final String className, final Set<Path> sources) {
            return new Violation(Type.SHADED, pluginJar.toString(), null, null, className, Set.copyOf(sources));
        }

        static Violation duplicate(final String className, final Set<Path> sources) {
            return new Violation(Type.DUPLICATE, null, null, null, className, Set.copyOf(sources));
        }

        String describe() {
            return switch (type) {
                case ABI -> "service method " + owner + '#' + method + descriptor
                            + " references " + className + ", supplied by non-provided dependency "
                            + names(sources);
                case SHADED -> "plugin jar bundles " + className
                               + ", which the platform already supplies in " + names(sources);
                case DUPLICATE -> "class " + className + " is present in multiple dependency scopes: "
                                  + names(sources);
            };
        }

        private static String names(final Set<Path> sources) {
            return sources.stream().map(path -> path.getFileName().toString()).sorted().toList().toString();
        }
    }

    static final class Result {

        private final Path jar;
        private final int classCount;
        private final List<Violation> violations;

        Result(final Path jar, final int classCount, final List<Violation> violations) {
            this.jar = jar;
            this.classCount = classCount;
            this.violations = List.copyOf(violations);
        }

        List<Violation> violations() {
            return violations;
        }

        void print() {
            if (violations.isEmpty()) {
                System.out.println("Plugin ABI check passed: " + jar.getFileName()
                                   + " (" + classCount + " class(es))");
                return;
            }
            System.out.println("Plugin ABI check FAILED: " + jar.getFileName()
                               + " (" + violations.size() + " error(s))");
            for (final Violation violation : violations) {
                System.out.println("  [ERROR] " + violation.describe());
            }
        }
    }
}

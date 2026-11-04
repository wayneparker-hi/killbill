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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/** Resolves inherited service contracts without loading plugin classes or executing their code. */
final class ServiceAbiInspector {

    private final List<ClassFileParser.ClassInfo> classes;
    private final Map<String, Set<Path>> provided;
    private final Map<String, Set<Path>> runtime;
    private final List<String> prefixes;
    private final Map<String, ClassFileParser.ClassInfo> index = new HashMap<>();

    ServiceAbiInspector(final List<ClassFileParser.ClassInfo> classes,
                        final Map<String, Set<Path>> provided, final Map<String, Set<Path>> runtime,
                        final List<String> prefixes) {
        this.classes = classes;
        this.provided = provided;
        this.runtime = runtime;
        this.prefixes = prefixes;
        classes.forEach(info -> index.put(info.name(), info));
    }

    List<AbiValidator.Violation> validate() throws IOException {
        final List<AbiValidator.Violation> violations = new ArrayList<>();
        final Set<String> reported = new HashSet<>();
        for (final ClassFileParser.ClassInfo owner : classes) {
            final List<ResolvedType> hierarchy = new ArrayList<>();
            ancestors(owner, List.of(), Map.of(), hierarchy, new HashSet<>());
            final Set<String> boundary = new HashSet<>();
            for (final ResolvedType type : hierarchy) {
                if (prefixes.stream().anyMatch(type.info().name()::startsWith)) {
                    collectBoundary(type.info(), boundary, new HashSet<>());
                }
            }
            if (boundary.isEmpty()) { continue; }
            for (final ResolvedType type : hierarchy) {
                for (final ClassFileParser.MethodInfo method : type.info().methods()) {
                    if (visible(method) && boundary.contains(methodKey(method))) {
                        check(owner.name(), method, type.bindings(), violations, reported);
                    }
                }
            }
        }
        return violations;
    }

    private void collectBoundary(final ClassFileParser.ClassInfo type, final Set<String> methods,
                                 final Set<String> visited) throws IOException {
        if (!visited.add(type.name())) { return; }
        type.methods().stream().filter(this::visible).map(this::methodKey).forEach(methods::add);
        final List<String> parents = new ArrayList<>(type.interfaces());
        if (type.superName() != null && !type.superName().equals("java.lang.Object")) { parents.add(type.superName()); }
        for (final String parent : parents) {
            final ClassFileParser.ClassInfo info = resolve(parent);
            if (info != null) { collectBoundary(info, methods, visited); }
        }
    }

    private void check(final String owner, final ClassFileParser.MethodInfo method, final Map<String, Set<String>> bindings,
                       final List<AbiValidator.Violation> violations, final Set<String> reported) {
        final Set<String> types = new LinkedHashSet<>(ClassFileParser.typesInDescriptor(method.descriptor()));
        types.addAll(GenericHierarchy.types(method.signature(), bindings));
        types.addAll(method.exceptions());
        checkTypes(owner, method.name(), method.descriptor(), types, violations, reported);
    }

    private void checkTypes(final String owner, final String method, final String descriptor,
                            final Iterable<String> types, final List<AbiValidator.Violation> violations,
                            final Set<String> reported) {
        for (final String type : types) {
            if (type.startsWith("java.") || provided.containsKey(type)) { continue; }
            final Set<Path> sources = runtime.get(type);
            if (sources != null && reported.add(owner + method + descriptor + type)) {
                violations.add(AbiValidator.Violation.abi(owner, method, descriptor, type, sources));
            }
        }
    }

    private boolean visible(final ClassFileParser.MethodInfo method) {
        return (method.accessFlags() & 0x0001) != 0 && (method.accessFlags() & 0x0008) == 0
               && !method.name().startsWith("<");
    }

    private String methodKey(final ClassFileParser.MethodInfo method) {
        return method.name() + method.descriptor().substring(0, method.descriptor().indexOf(')') + 1);
    }

    private record ResolvedType(ClassFileParser.ClassInfo info, Map<String, Set<String>> bindings) { }

    private void ancestors(final ClassFileParser.ClassInfo type, final List<String> arguments,
                           final Map<String, Set<String>> inherited, final List<ResolvedType> result,
                           final Set<String> visited) throws IOException {
        if (!visited.add(type.name())) { return; }
        final GenericHierarchy.Signature signature = GenericHierarchy.parse(type.signature());
        final Map<String, Set<String>> bindings = GenericHierarchy.bindings(signature, arguments, inherited);
        result.add(new ResolvedType(type, bindings));
        final List<GenericHierarchy.Parent> parents = new ArrayList<>(signature.parents());
        if (parents.isEmpty()) {
            type.interfaces().forEach(name -> parents.add(new GenericHierarchy.Parent(name, List.of())));
            if (type.superName() != null) { parents.add(new GenericHierarchy.Parent(type.superName(), List.of())); }
        }
        for (final GenericHierarchy.Parent parent : parents) {
            final ClassFileParser.ClassInfo info = resolve(parent.name());
            if (info != null) { ancestors(info, parent.arguments(), bindings, result, visited); }
        }
    }

    private ClassFileParser.ClassInfo resolve(final String name) throws IOException {
        if (index.containsKey(name)) { return index.get(name); }
        final Set<Path> sources = provided.getOrDefault(name, runtime.getOrDefault(name, Set.of()));
        for (final Path source : sources) {
            final String entry = name.replace('.', '/') + ".class";
            final ClassFileParser.ClassInfo info;
            if (Files.isDirectory(source)) {
                try (InputStream input = Files.newInputStream(source.resolve(entry))) {
                    info = ClassFileParser.parse(input);
                }
            } else {
                try (JarFile jar = new JarFile(source.toFile()); InputStream input = jar.getInputStream(jar.getJarEntry(entry))) {
                    info = ClassFileParser.parse(input);
                }
            }
            index.put(name, info);
            return info;
        }
        index.put(name, null);
        return null;
    }
}

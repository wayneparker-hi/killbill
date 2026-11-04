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

package org.killbill.billing.lpr.abi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class TestAbiValidator {
    private Path root;
    private Path classes;
    private Path api;
    private Path dependency;

    @BeforeClass
    public void compileFixtures() throws Exception {
        root = Files.createTempDirectory("abi-test-");
        classes = Files.createDirectories(root.resolve("classes"));
        final Map<String, String> sources = Map.ofEntries(
                Map.entry("third.Dto", "public class Dto {}"),
                Map.entry("spi.Direct", "public interface Direct { third.Dto value(); }"),
                Map.entry("spi.Generic", "public interface Generic { java.util.List<third.Dto> value(); }"),
                Map.entry("spi.Bound", "public interface Bound { <T extends third.Dto> java.util.List<T> value(); }"),
                Map.entry("spi.Typed", "public interface Typed<T> { T value(); }"),
                Map.entry("foreign.Parent", "public interface Parent<T> { T value(); }"),
                Map.entry("spi.Indirect", "public interface Indirect extends foreign.Parent<third.Dto> {}"),
                Map.entry("impl.Indirect", "public abstract class Indirect implements spi.Indirect {}"),
                Map.entry("spi.Safe", "public interface Safe { String value(); }"),
                Map.entry("impl.Direct", "public class Direct implements spi.Direct { public third.Dto value() { return null; } }"),
                Map.entry("impl.Generic", "public class Generic implements spi.Generic { public java.util.List<third.Dto> value() { return null; } }"),
                Map.entry("impl.Bound", "public abstract class Bound implements spi.Bound {}"),
                Map.entry("impl.Typed", "public abstract class Typed implements spi.Typed<third.Dto> {}"),
                Map.entry("impl.Base", "public class Base { public third.Dto value() { return null; } }"),
                Map.entry("impl.Inherited", "public class Inherited extends Base implements spi.Direct {}"),
                Map.entry("impl.PrivateBase", "public class PrivateBase<T extends third.Dto> {}"),
                Map.entry("impl.Helper", "public class Helper extends PrivateBase<third.Dto> implements spi.Safe { public String value() { return null; } public third.Dto helper() { return null; } }"));
        final List<String> args = new ArrayList<>(List.of("-d", classes.toString()));
        for (final var source : sources.entrySet()) {
            final Path file = root.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "package " + source.getKey().substring(0, source.getKey().lastIndexOf('.')) + "; " + source.getValue());
            args.add(file.toString());
        }
        assertEquals(ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)), 0);
        api = jar("api.jar", sources.keySet().stream().filter(name -> name.startsWith("spi.") || name.startsWith("foreign.")).toArray(String[]::new));
        dependency = jar("dependency.jar", "third.Dto");
    }

    @AfterClass
    public void cleanup() throws Exception {
        if (root == null) { return; }
        try (var files = Files.walk(root)) {
            for (final Path file : files.sorted(Comparator.reverseOrder()).toList()) { Files.delete(file); }
        }
    }

    @DataProvider
    public Object[][] boundaries() {
        return new Object[][] {{"Direct"}, {"Generic"}, {"Bound"}, {"Typed"}, {"Inherited"}, {"Indirect"}};
    }

    @Test(groups = "fast", dataProvider = "boundaries")
    public void rejectsNonProvidedBoundaryTypes(final String implementation) throws Exception {
        final Path plugin = jar(implementation + ".jar", "impl." + implementation, "impl.Base");
        final var result = validate(plugin, api.toString(), dependency.toString());
        assertTrue(result.violations().stream().anyMatch(v -> v.type() == AbiValidator.Violation.Type.ABI
                                                             && v.className().equals("third.Dto")), result.violations().toString());
        assertTrue(validate(plugin, api + ":" + dependency, "").violations().isEmpty());
    }

    @Test(groups = "fast")
    public void ignoresPrivateImplementationHelpers() throws Exception {
        assertTrue(validate(jar("helper.jar", "impl.Helper", "impl.PrivateBase"), api.toString(), dependency.toString()).violations().isEmpty());
    }

    @Test(groups = "fast")
    public void rejectsShadedAndScopeDuplicates() throws Exception {
        assertTrue(validate(jar("shaded.jar", "impl.Direct", "third.Dto"), api + ":" + dependency, "")
                           .violations().stream().anyMatch(v -> v.type() == AbiValidator.Violation.Type.SHADED));
        final Path duplicate = jar("duplicate.jar", "third.Dto");
        // Duplicates confined to the platform do not create a second plugin ClassLoader copy.
        assertTrue(validate(jar("host-only.jar"), dependency + ":" + duplicate, "").violations().isEmpty());
        assertTrue(validate(jar("empty.jar"), dependency.toString(), duplicate.toString()).violations().stream()
                           .anyMatch(v -> v.type() == AbiValidator.Violation.Type.DUPLICATE));
    }

    @Test(groups = "fast")
    public void handlesDirectoryClasspathsAndNestedBounds() throws Exception {
        final Path dtoDirectory = Files.createDirectories(root.resolve("dto/third"));
        Files.copy(classes.resolve("third/Dto.class"), dtoDirectory.resolve("Dto.class"));
        assertTrue(validate(jar("dir.jar", "impl.Generic"), api.toString(), dtoDirectory.getParent().toString())
                           .violations().stream().anyMatch(v -> v.type() == AbiValidator.Violation.Type.ABI));
        final var types = ClassFileParser.typesInSignature("<LongName:Ljava/lang/Object;T::Ljava/util/List<Lthird/Dto;>;>(TT;)Ljava/util/Map<TLongName;Lthird/Outer<TT;>.Inner;>;");
        assertTrue(types.containsAll(List.of("third.Dto", "third.Outer$Inner", "java.util.Map")), types.toString());
    }

    @Test(groups = "fast")
    public void missingInputsFailClosed() throws Exception {
        expectThrows(IllegalArgumentException.class, () -> AbiValidator.Options.parse(new String[] {"--jar", "absent.jar"}));
        final Path plugin = jar("missing-input.jar");
        final var options = AbiValidator.Options.parse(new String[] {"--jar", plugin.toString(),
                "--provided-classpath-file", root.resolve("absent.cp").toString(),
                "--runtime-classpath-file", root.resolve("absent.cp").toString()});
        expectThrows(IOException.class, () -> AbiValidator.validate(options));
        expectThrows(IOException.class, () -> validate(plugin, root.resolve("absent.jar").toString(), ""));
    }

    private AbiValidator.Result validate(final Path plugin, final String provided, final String runtime) throws Exception {
        final Path providedFile = root.resolve("provided.cp");
        final Path runtimeFile = root.resolve("runtime.cp");
        Files.writeString(providedFile, provided);
        Files.writeString(runtimeFile, runtime);
        return AbiValidator.validate(AbiValidator.Options.parse(new String[] {"--jar", plugin.toString(),
                "--provided-classpath-file", providedFile.toString(), "--runtime-classpath-file", runtimeFile.toString(),
                "--service-api-prefix", "spi."}));
    }

    private Path jar(final String name, final String... types) throws IOException {
        final Path target = root.resolve(name);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(target))) {
            for (final String type : types) {
                final String entry = type.replace('.', '/') + ".class";
                jar.putNextEntry(new JarEntry(entry));
                Files.copy(classes.resolve(entry), jar);
                jar.closeEntry();
            }
        }
        return target;
    }
}

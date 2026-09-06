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

package org.killbill.billing.lpr.spi;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Decides, per class name, whether the parent ClassLoader or the plugin's own classpath wins.
 * <p>
 * The model is <b>API parent-first, dependencies child-first</b>:
 * <ul>
 *   <li>Runtime-owned packages resolve from the parent, so that core and plugin end up with the
 *       same {@code Class} object. Without this a plugin's copy of {@code PaymentPluginApi} is a
 *       different type from the core's and every call fails with {@code ClassCastException}.</li>
 *   <li>Everything else resolves from the plugin first, so two plugins can ship different versions
 *       of the same library.</li>
 * </ul>
 * This replaces the OSGi system-packages export list -- roughly two hundred lines of package names
 * in {@code OSGIConfig} -- with the handful of prefixes below. It is a smaller contract on purpose:
 * plugins declare nothing, so there is no per-plugin package manifest to keep in sync.
 * <p>
 * Instances are immutable.
 */
public final class ClassLoaderPolicy {

    /**
     * Prefixes that must never resolve from a plugin, whatever the configuration says. Letting a
     * plugin shadow {@code java.*} or the JDK's XML types produces {@code LinkageError} and
     * security surprises rather than useful isolation.
     */
    private static final List<String> ALWAYS_PARENT_FIRST = List.of(
            "java.",
            "javax.",
            "jakarta.",
            "sun.",
            "com.sun.",
            "jdk.",
            "org.w3c.dom.",
            "org.xml.sax.",
            "org.ietf.jgss."
    );

    /**
     * The runtime's own contract with plugins, plus the libraries whose types cross that boundary.
     * Distilled from the OSGi {@code system.bundle.export.packages.api} list, dropping everything
     * plugins never actually reference.
     */
    private static final List<String> DEFAULT_API_PARENT_FIRST = List.of(
            "org.killbill.",
            "org.joda.time.",
            "org.slf4j.",
            "org.apache.shiro."
    );

    /**
     * Carved back out of {@code org.killbill.} above, because that prefix covers both sides of the
     * boundary and only one of them belongs to the platform.
     * <p>
     * Kill Bill's plugin <i>SPI</i> lives in packages like
     * {@code org.killbill.billing.payment.plugin.api} -- platform-owned, and rightly parent-first.
     * Plugin <i>implementations</i> conventionally live in {@code org.killbill.billing.plugin.<name>},
     * and the shared plugin base classes they bundle live in {@code org.killbill.billing.plugin.api}
     * and {@code .plugin.core}. Those are the plugin's own code, shipped inside its jar, and must
     * resolve from it.
     * <p>
     * Without this exception a plugin's own classes are looked up in the platform first. That
     * usually still works, because the platform does not have them and the lookup falls through --
     * which is exactly what makes it dangerous: it works until some class name collides, and then
     * the plugin silently runs the platform's copy.
     */
    private static final List<String> DEFAULT_CHILD_FIRST_EXCEPTIONS = List.of(
            "org.killbill.billing.plugin."
    );

    private final List<String> parentFirstPrefixes;
    private final List<String> childFirstExceptions;

    private ClassLoaderPolicy(final List<String> parentFirstPrefixes, final List<String> childFirstExceptions) {
        this.parentFirstPrefixes = parentFirstPrefixes;
        this.childFirstExceptions = childFirstExceptions;
    }

    /**
     * The policy the runtime ships with.
     *
     * @return a policy covering the JDK and the Kill Bill plugin contract
     */
    public static ClassLoaderPolicy defaultPolicy() {
        return new ClassLoaderPolicy(concat(ALWAYS_PARENT_FIRST, DEFAULT_API_PARENT_FIRST),
                                     DEFAULT_CHILD_FIRST_EXCEPTIONS);
    }

    /**
     * The default policy plus extra parent-first prefixes, for deployments that share additional
     * types across the boundary.
     * <p>
     * Add sparingly. Every prefix added here is a library the plugin can no longer choose its own
     * version of.
     *
     * @param extraPrefixes additional package prefixes; a trailing dot is added if missing
     * @return a new policy; the receiver is unchanged
     */
    public ClassLoaderPolicy withParentFirst(final Collection<String> extraPrefixes) {
        Objects.requireNonNull(extraPrefixes, "extraPrefixes");
        final List<String> normalized = extraPrefixes.stream()
                                                     .map(ClassLoaderPolicy::normalize)
                                                     .toList();
        return new ClassLoaderPolicy(concat(parentFirstPrefixes, normalized), childFirstExceptions);
    }

    /**
     * Carves an extra sub-tree back out of the parent-first set, for a package that sits under a
     * platform-owned prefix but is really the plugin's own.
     *
     * @param exceptionPrefixes package prefixes that must resolve from the plugin
     * @return a new policy; the receiver is unchanged
     */
    public ClassLoaderPolicy withChildFirstException(final Collection<String> exceptionPrefixes) {
        Objects.requireNonNull(exceptionPrefixes, "exceptionPrefixes");
        final List<String> normalized = exceptionPrefixes.stream()
                                                         .map(ClassLoaderPolicy::normalize)
                                                         .toList();
        return new ClassLoaderPolicy(parentFirstPrefixes, concat(childFirstExceptions, normalized));
    }

    /**
     * @param className fully qualified class name
     * @return true if the parent ClassLoader must be consulted first
     */
    public boolean isParentFirst(final String className) {
        Objects.requireNonNull(className, "className");

        // Exceptions are checked first: they exist precisely to override a broader parent-first
        // prefix, so evaluating them second would make them unreachable.
        for (final String exception : childFirstExceptions) {
            if (className.startsWith(exception)) {
                return false;
            }
        }
        for (final String prefix : parentFirstPrefixes) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the package prefixes carved back out of the parent-first set; immutable
     */
    public List<String> childFirstExceptions() {
        return childFirstExceptions;
    }

    /**
     * Same decision for a resource lookup, so that {@code getResource} and {@code loadClass} cannot
     * disagree about where a package lives.
     *
     * @param resourceName slash-separated resource path
     * @return true if the parent ClassLoader must be consulted first
     */
    public boolean isParentFirstResource(final String resourceName) {
        Objects.requireNonNull(resourceName, "resourceName");
        final String asClassName = resourceName.replace('/', '.');
        return isParentFirst(asClassName.startsWith(".") ? asClassName.substring(1) : asClassName);
    }

    /**
     * @return the effective parent-first prefixes, in evaluation order; immutable
     */
    public List<String> parentFirstPrefixes() {
        return parentFirstPrefixes;
    }

    private static String normalize(final String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return prefix.endsWith(".") ? prefix : prefix + ".";
    }

    private static List<String> concat(final List<String> a, final List<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).distinct().toList();
    }
}

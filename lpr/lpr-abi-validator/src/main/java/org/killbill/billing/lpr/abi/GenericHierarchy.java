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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves only type variables used by service methods; private DAO bounds stay private. */
final class GenericHierarchy {
    record Parent(String name, List<String> arguments) { }
    record Signature(Map<String, String> parameters, List<Parent> parents) { }

    private GenericHierarchy() { }

    static Signature parse(final String signature) {
        final Map<String, String> parameters = new LinkedHashMap<>();
        final List<Parent> parents = new ArrayList<>();
        int cursor = 0;
        if (signature.startsWith("<")) {
            cursor++;
            while (signature.charAt(cursor) != '>') {
                final int colon = signature.indexOf(':', cursor);
                final String name = signature.substring(cursor, colon);
                cursor = colon + 1;
                final int start = cursor;
                if (signature.charAt(cursor) != ':') { cursor = end(signature, cursor); }
                while (signature.charAt(cursor) == ':') { cursor = end(signature, cursor + 1); }
                parameters.put(name, signature.substring(start, cursor));
            }
            cursor++;
        }
        while (cursor < signature.length()) {
            final int finish = end(signature, cursor);
            final String type = signature.substring(cursor, finish);
            final int generic = type.indexOf('<');
            final String name = type.substring(1, generic < 0 ? type.length() - 1 : generic).replace('/', '.');
            final List<String> args = new ArrayList<>();
            if (generic >= 0) {
                int arg = generic + 1;
                while (type.charAt(arg) != '>') {
                    final int next = end(type, arg);
                    args.add(type.substring(arg, next));
                    arg = next;
                }
            }
            parents.add(new Parent(name, args));
            cursor = finish;
        }
        return new Signature(parameters, parents);
    }

    static Map<String, Set<String>> bindings(final Signature signature, final List<String> arguments,
                                             final Map<String, Set<String>> inherited) {
        final Map<String, Set<String>> result = new LinkedHashMap<>();
        int index = 0;
        for (final var parameter : signature.parameters().entrySet()) {
            final String value = index < arguments.size() ? arguments.get(index) : parameter.getValue();
            result.put(parameter.getKey(), types(value, inherited));
            index++;
        }
        // Resolve bounds referring to another variable declared on this same type.
        for (final var parameter : signature.parameters().entrySet()) {
            if (arguments.isEmpty()) { result.get(parameter.getKey()).addAll(types(parameter.getValue(), result)); }
        }
        return result;
    }

    static Set<String> types(final String signature, final Map<String, Set<String>> bindings) {
        final Set<String> result = new LinkedHashSet<>(ClassFileParser.typesInSignature(signature));
        for (int cursor = 0; cursor < signature.length(); cursor++) {
            if (signature.charAt(cursor) != 'T') { continue; }
            final int finish = signature.indexOf(';', cursor);
            if (finish > cursor) {
                result.addAll(bindings.getOrDefault(signature.substring(cursor + 1, finish), Set.of()));
            }
        }
        return result;
    }

    private static int end(final String signature, final int start) {
        int cursor = start;
        while (signature.charAt(cursor) == '[' || signature.charAt(cursor) == '+' || signature.charAt(cursor) == '-') { cursor++; }
        final char token = signature.charAt(cursor++);
        if (token == 'T') { return signature.indexOf(';', cursor) + 1; }
        if (token != 'L') { return cursor; }
        int depth = 0;
        while (cursor < signature.length()) {
            final char part = signature.charAt(cursor++);
            if (part == '<') { depth++; }
            if (part == '>') { depth--; }
            if (part == ';' && depth == 0) { return cursor; }
        }
        return cursor;
    }
}

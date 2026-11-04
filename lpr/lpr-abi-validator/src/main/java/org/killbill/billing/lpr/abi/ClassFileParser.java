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

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the small part of a class file the ABI check needs: access flags, hierarchy, and method
 * descriptors. Deliberately dependency-free so the validator can run from a plain jar.
 */
final class ClassFileParser {

    private static final int MAGIC = 0xCAFEBABE;

    private ClassFileParser() {
    }

    static ClassInfo parse(final InputStream input) throws IOException {
        final DataInputStream in = new DataInputStream(input);
        if (in.readInt() != MAGIC) {
            throw new IOException("Not a class file");
        }
        in.readUnsignedShort(); // minor
        in.readUnsignedShort(); // major

        final int constantPoolCount = in.readUnsignedShort();
        final String[] utf8 = new String[constantPoolCount];
        final int[] classNames = new int[constantPoolCount];
        for (int i = 1; i < constantPoolCount; i++) {
            final int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 3, 4 -> in.skipNBytes(4);
                case 5, 6 -> {
                    in.skipNBytes(8);
                    i++;
                }
                case 7 -> classNames[i] = in.readUnsignedShort();
                case 8, 16, 19, 20 -> in.skipNBytes(2);
                case 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                case 15 -> in.skipNBytes(3);
                default -> throw new IOException("Unsupported constant pool tag " + tag);
            }
        }

        final int accessFlags = in.readUnsignedShort();
        final String thisClass = className(classNames, utf8, in.readUnsignedShort());
        final int superIndex = in.readUnsignedShort();
        final String superClass = superIndex == 0 ? null : className(classNames, utf8, superIndex);

        final int interfaceCount = in.readUnsignedShort();
        final List<String> interfaces = new ArrayList<>(interfaceCount);
        for (int i = 0; i < interfaceCount; i++) {
            interfaces.add(className(classNames, utf8, in.readUnsignedShort()));
        }

        skipMembers(in);
        final List<MethodInfo> methods = readMethods(in, utf8, classNames);

        return new ClassInfo(accessFlags, thisClass, superClass, List.copyOf(interfaces), List.copyOf(methods), readSignature(in, utf8));
    }

    private static List<MethodInfo> readMethods(final DataInputStream in,
                                                final String[] utf8,
                                                final int[] classNames) throws IOException {
        final int methodCount = in.readUnsignedShort();
        final List<MethodInfo> methods = new ArrayList<>(methodCount);
        for (int i = 0; i < methodCount; i++) {
            final int accessFlags = in.readUnsignedShort();
            final String name = utf8[in.readUnsignedShort()];
            final String descriptor = utf8[in.readUnsignedShort()];
            final List<String> exceptions = new ArrayList<>();
            String signature = "";
            final int attributeCount = in.readUnsignedShort();
            for (int a = 0; a < attributeCount; a++) {
                final String attributeName = utf8[in.readUnsignedShort()];
                final int length = in.readInt();
                if ("Exceptions".equals(attributeName)) {
                    final int exceptionCount = in.readUnsignedShort();
                    for (int e = 0; e < exceptionCount; e++) {
                        exceptions.add(className(classNames, utf8, in.readUnsignedShort()));
                    }
                } else if ("Signature".equals(attributeName)) {
                    signature = utf8[in.readUnsignedShort()];
                } else {
                    in.skipNBytes(length);
                }
            }
            methods.add(new MethodInfo(accessFlags, name, descriptor, List.copyOf(exceptions), signature));
        }
        return methods;
    }

    private static String readSignature(final DataInputStream in, final String[] utf8) throws IOException {
        String signature = "";
        final int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            final String name = utf8[in.readUnsignedShort()];
            final int length = in.readInt();
            if ("Signature".equals(name)) {
                signature = utf8[in.readUnsignedShort()];
            } else {
                in.skipNBytes(length);
            }
        }
        return signature;
    }

    static List<String> typesInSignature(final String signature) {
        final List<String> types = new ArrayList<>();
        int cursor = 0;
        if (signature.startsWith("<")) {
            cursor = 1;
            while (signature.charAt(cursor) != '>') {
                cursor = signature.indexOf(':', cursor) + 1;
                if (signature.charAt(cursor) != ':') {
                    cursor = signatureType(signature, cursor, types);
                }
                while (signature.charAt(cursor) == ':') {
                    cursor = signatureType(signature, cursor + 1, types);
                }
            }
            cursor++;
        }
        while (cursor < signature.length()) {
            cursor = signatureType(signature, cursor, types);
        }
        return types;
    }

    private static int signatureType(final String signature, final int start, final List<String> types) {
        int cursor = start + 1;
        final char token = signature.charAt(start);
        if (token == 'T') {
            return signature.indexOf(';', cursor) + 1;
        }
        if (token != 'L') { return cursor; }
        final StringBuilder name = new StringBuilder();
        while (cursor < signature.length()) {
            final char part = signature.charAt(cursor++);
            if (part == '<') {
                types.add(name.toString().replace('/', '.'));
                while (signature.charAt(cursor) != '>') {
                    cursor = signatureType(signature, cursor, types);
                }
                cursor++;
            } else if (part == ';') {
                types.add(name.toString().replace('/', '.'));
                return cursor;
            } else {
                name.append(part == '.' ? '$' : part);
            }
        }
        return cursor;
    }

    private static void skipMembers(final DataInputStream in) throws IOException {
        final int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.skipNBytes(6); // access_flags, name_index, descriptor_index
            final int attributeCount = in.readUnsignedShort();
            for (int a = 0; a < attributeCount; a++) {
                in.readUnsignedShort(); // name
                final int length = in.readInt();
                in.skipNBytes(length);
            }
        }
    }

    private static String className(final int[] classNames, final String[] utf8, final int classIndex) {
        return utf8[classNames[classIndex]].replace('/', '.');
    }

    static List<String> typesInDescriptor(final String descriptor) {
        final List<String> types = new ArrayList<>();
        for (int i = 0; i < descriptor.length(); i++) {
            if (descriptor.charAt(i) != 'L') {
                continue;
            }
            final int end = descriptor.indexOf(';', i);
            if (end < 0) {
                break;
            }
            types.add(descriptor.substring(i + 1, end).replace('/', '.'));
            i = end;
        }
        return types;
    }

    record ClassInfo(int accessFlags,
                     String name,
                     String superName,
                     List<String> interfaces,
                     List<MethodInfo> methods,
                     String signature) {
    }

    record MethodInfo(int accessFlags,
                      String name,
                      String descriptor,
                      List<String> exceptions,
                      String signature) {
    }
}

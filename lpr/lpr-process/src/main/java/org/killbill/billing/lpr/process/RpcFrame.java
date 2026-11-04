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

package org.killbill.billing.lpr.process;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Fixed, versioned data protocol; deliberately no object serialization or reflective wire types. */
record RpcFrame(long requestId, byte status, List<String> fields) {
    static final int MAGIC = 0x4c505231;
    static final int VERSION = 1;
    static final int HEADER_BYTES = 21;
    static final int MAX_FIELDS = 256;
    static final byte REQUEST = 0;
    static final byte SUCCESS = 1;
    static final byte FAILURE = 2;
    static final byte HELLO = 3;

    RpcFrame { fields = List.copyOf(fields); }

    byte[] encode(final int maxBytes) {
        if (fields.size() > MAX_FIELDS) { throw new IllegalArgumentException("Too many RPC fields"); }
        final List<byte[]> encoded = new ArrayList<>();
        int length = HEADER_BYTES;
        for (final String field : fields) {
            if (field.length() > maxBytes) { throw new IllegalArgumentException("RPC field exceeds budget"); }
            final byte[] bytes = encodeField(field);
            length += Integer.BYTES + bytes.length;
            if (length > maxBytes) { throw new IllegalArgumentException("RPC frame exceeds budget"); }
            encoded.add(bytes);
        }
        final ByteBuffer buffer = ByteBuffer.allocate(length);
        buffer.putInt(MAGIC).putInt(VERSION).putLong(requestId).put(status).putInt(fields.size());
        encoded.forEach(bytes -> buffer.putInt(bytes.length).put(bytes));
        return buffer.array();
    }

    private static byte[] encodeField(final String field) {
        try {
            final ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(field));
            final byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (final CharacterCodingException e) { throw new IllegalArgumentException("Invalid RPC UTF-16", e); }
    }

    static RpcFrame decode(final byte[] bytes, final int maxBytes) throws IOException {
        if (bytes.length < HEADER_BYTES || bytes.length > maxBytes) { throw new IOException("Invalid RPC frame length"); }
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.getInt() != MAGIC || buffer.getInt() != VERSION) { throw new IOException("Unsupported RPC protocol"); }
        final long id = buffer.getLong();
        final byte status = buffer.get();
        if (status < REQUEST || status > HELLO) { throw new IOException("Invalid RPC status"); }
        final int count = buffer.getInt();
        if (count < 0 || count > MAX_FIELDS) { throw new IOException("Invalid RPC field count"); }
        final List<String> fields = new ArrayList<>();
        for (int index = 0; index < count; index++) { fields.add(readField(buffer)); }
        if (buffer.hasRemaining()) { throw new IOException("Trailing RPC data"); }
        return new RpcFrame(id, status, fields);
    }

    private static String readField(final ByteBuffer buffer) throws IOException {
        if (buffer.remaining() < Integer.BYTES) { throw new IOException("Truncated RPC field"); }
        final int length = buffer.getInt();
        if (length < 0 || length > buffer.remaining()) { throw new IOException("Invalid RPC field length"); }
        final ByteBuffer field = buffer.slice();
        field.limit(length);
        buffer.position(buffer.position() + length);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(field).toString();
        } catch (final CharacterCodingException e) { throw new IOException("Invalid RPC UTF-8", e); }
    }
}

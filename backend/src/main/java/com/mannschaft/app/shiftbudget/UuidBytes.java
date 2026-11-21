package com.mannschaft.app.shiftbudget;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * UUID と BINARY(16) の相互変換（ネイティブ SQL の BINARY(16) 列の受け渡し用。CMP-260909-2152）。
 */
public final class UuidBytes {

    private UuidBytes() {
    }

    /** UUID → 16 バイト（Hibernate が UUID を BINARY(16) に書くのと同じ並び）。 */
    public static byte[] toBytes(UUID id) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(id.getMostSignificantBits());
        buffer.putLong(id.getLeastSignificantBits());
        return buffer.array();
    }

    /** 16 バイト → UUID。 */
    public static UUID fromBytes(byte[] raw) {
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}

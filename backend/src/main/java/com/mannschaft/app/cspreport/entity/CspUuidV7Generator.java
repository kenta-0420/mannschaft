package com.mannschaft.app.cspreport.entity;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.id.IdentifierGenerator;

import java.security.SecureRandom;
import java.util.UUID;

/** Hibernate の TIME スタイルは UUIDv1 なので、CSP 報告専用に UUIDv7 を生成する。 */
public class CspUuidV7Generator implements IdentifierGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public UUID generate(SharedSessionContractImplementor session, Object object) {
        long milliseconds = System.currentTimeMillis() & 0xFFFF_FFFF_FFFFL;
        long mostSignificantBits = (milliseconds << 16) | 0x7000L | RANDOM.nextInt(1 << 12);
        long leastSignificantBits = 0x8000_0000_0000_0000L
                | (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL);
        return new UUID(mostSignificantBits, leastSignificantBits);
    }
}

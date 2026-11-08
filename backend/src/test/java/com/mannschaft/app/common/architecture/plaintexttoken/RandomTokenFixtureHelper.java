package com.mannschaft.app.common.architecture.plaintexttoken;

import java.security.SecureRandom;
import java.util.HexFormat;

/** fixture（番人3 陰性）: Entity に触れない乱数ヘルパ（AuthTokenService#generateRefreshToken に相当）。 */
public final class RandomTokenFixtureHelper {

    private static final int RANDOM_BYTE_LENGTH = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private RandomTokenFixtureHelper() {
    }

    public static String generate() {
        byte[] bytes = new byte[RANDOM_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}

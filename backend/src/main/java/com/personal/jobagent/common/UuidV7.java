package com.personal.jobagent.common;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generates time-ordered UUIDv7 values (draft RFC 9562) in application code,
 * per the architecture decision that all PKs are app-generated UUIDv7 rather
 * than DB defaults — keeps the DB portable and PK generation testable.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate() {
        long timestampMs = System.currentTimeMillis();

        byte[] randomBytes = new byte[10];
        RANDOM.nextBytes(randomBytes);

        long msb = (timestampMs & 0xFFFFFFFFFFFFL) << 16;
        msb |= 0x7000L; // version 7
        msb |= ((randomBytes[0] & 0xFF) << 8 | (randomBytes[1] & 0xFF)) & 0x0FFF;

        long lsb = 0;
        lsb |= 0x8000000000000000L; // variant 10xxxxxx
        lsb |= ((long) (randomBytes[2] & 0x3F)) << 56;
        for (int i = 3; i < 10; i++) {
            lsb |= ((long) (randomBytes[i] & 0xFF)) << (8 * (9 - i));
        }

        return new UUID(msb, lsb);
    }
}

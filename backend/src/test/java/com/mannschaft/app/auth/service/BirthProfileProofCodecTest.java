package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.entity.BirthProfileConfirmationEntity;
import com.mannschaft.app.common.EncryptionService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** AC65: 実HMACと明示合成鍵で束縛を検証する。既存設定・実鍵は参照しない。 */
class BirthProfileProofCodecTest {
    private static final UUID REF = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ATTEMPT = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant EXPIRY = Instant.parse("2030-04-05T06:07:08.123456789Z");

    private EncryptionService encryption(byte marker) {
        byte[] syntheticEncryptionKey = new byte[32];
        byte[] syntheticHmacKey = new byte[32];
        Arrays.fill(syntheticEncryptionKey, (byte) 17);
        Arrays.fill(syntheticHmacKey, marker);
        return new EncryptionService(syntheticEncryptionKey, syntheticHmacKey);
    }

    private BirthProfileConfirmationEntity signed(BirthProfileProofCodec codec) {
        return BirthProfileConfirmationEntity.builder().id(REF).userId(41L).profileRevision(7L)
                .withdrawalAttemptId(ATTEMPT).fingerprint("fingerprint").purpose("BIRTH_STYLE")
                .expiresAt(EXPIRY).keyId(codec.keyId())
                .signature(codec.signature(REF, 41L, 7L, ATTEMPT, "fingerprint", "BIRTH_STYLE", EXPIRY))
                .createdAt(EXPIRY.minusSeconds(600)).updatedAt(EXPIRY.minusSeconds(600)).build();
    }

    @Test void 署名は独立した正準文字列とMICROSの期限を使う() {
        var encryption = encryption((byte) 23);
        var codec = new BirthProfileProofCodec(encryption);
        // フィールド長と順序を固定したgolden入力。被検体のcanonical helperから生成しない。
        String canonical = "birth-profile:confirmation:v1"
                + "|36:00000000-0000-0000-0000-000000000001|2:41|1:7"
                + "|36:00000000-0000-0000-0000-000000000002|11:fingerprint|11:BIRTH_STYLE"
                + "|27:2030-04-05T06:07:08.123456Z";
        assertThat(codec.signature(REF, 41L, 7L, ATTEMPT, "fingerprint", "BIRTH_STYLE", EXPIRY))
                .isEqualTo(encryption.hmac(canonical));
        assertThat(BirthProfileProofCodec.micros(EXPIRY))
                .isEqualTo(Instant.parse("2030-04-05T06:07:08.123456Z"));
        assertThat(codec.signature(REF, 41L, 7L, ATTEMPT, "fingerprint", "BIRTH_STYLE", EXPIRY))
                .isEqualTo(codec.signature(REF, 41L, 7L, ATTEMPT, "fingerprint", "BIRTH_STYLE",
                        Instant.parse("2030-04-05T06:07:08.123456000Z")));
    }

    @Test void 本人用途nonce版世代指紋期限鍵識別と署名の一項目変異を拒否する() {
        var codec = new BirthProfileProofCodec(encryption((byte) 23));
        var original = signed(codec);
        assertThat(codec.authentic(original)).isTrue();
        List<BirthProfileConfirmationEntity> variants = List.of(
                original.toBuilder().userId(42L).build(),
                original.toBuilder().purpose("DIAGNOSIS").build(),
                original.toBuilder().id(UUID.fromString("00000000-0000-0000-0000-000000000003")).build(),
                original.toBuilder().profileRevision(8L).build(),
                original.toBuilder().withdrawalAttemptId(null).build(),
                original.toBuilder().fingerprint("changed-fingerprint").build(),
                original.toBuilder().expiresAt(EXPIRY.plusNanos(1000)).build(),
                original.toBuilder().keyId("unrecognized-key-id").build(),
                original.toBuilder().signature("invalid-signature").build());
        List<String> fields = List.of("user", "purpose", "nonce/ref-id", "revision", "withdrawalAttemptId",
                "fingerprint", "expiry", "keyId", "signature");
        for (int index = 0; index < variants.size(); index++) {
            assertThat(codec.authentic(variants.get(index))).as(fields.get(index)).isFalse();
        }
    }

    @Test void 合成HMAC鍵の交代後は旧確認を拒否し新確認だけを認める() {
        var old = new BirthProfileProofCodec(encryption((byte) 23));
        var current = new BirthProfileProofCodec(encryption((byte) 29));
        var previousConfirmation = signed(old);
        assertThat(old.authentic(previousConfirmation)).isTrue();
        assertThat(current.authentic(previousConfirmation)).isFalse();
        assertThat(current.authentic(signed(current))).isTrue();
    }
}

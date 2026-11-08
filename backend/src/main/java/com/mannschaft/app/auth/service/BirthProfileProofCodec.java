package com.mannschaft.app.auth.service;

import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.auth.entity.BirthProfileConfirmationEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** auth内だけで原情報を署名証跡へ変換する。原情報や署名のログ出力は行わない。 */
@Service
@RequiredArgsConstructor
public class BirthProfileProofCodec {
    private final EncryptionService encryptionService;

    public String fingerprint(String lastName, String firstName, String lastNameKana,
            String firstNameKana, LocalDate birthDate) {
        if (birthDate == null) throw new IllegalArgumentException("出生情報が不足しています");
        return encryptionService.hmac(canonical("birth-profile:fingerprint:v1",
                lastName, firstName, lastNameKana, firstNameKana, birthDate.toString()));
    }

    public String keyId() {
        // 実鍵の値や設定を取り出さず、現在鍵の交代を判別する固定用途の識別値を得る。
        return encryptionService.hmac("birth-profile:key-id:v1");
    }

    public String signature(UUID id, Long userId, long revision, UUID withdrawalAttemptId,
            String fingerprint, String purpose, Instant expiresAt) {
        if (id == null || userId == null || revision < 0 || expiresAt == null) {
            throw new IllegalArgumentException("本人確認の指定が不正です");
        }
        return encryptionService.hmac(canonical("birth-profile:confirmation:v1", id.toString(),
                userId.toString(), Long.toString(revision),
                withdrawalAttemptId == null ? "none" : withdrawalAttemptId.toString(),
                fingerprint, purpose, micros(expiresAt).toString()));
    }

    boolean authentic(BirthProfileConfirmationEntity confirmation) {
        return equal(keyId(), confirmation.getKeyId()) && equal(signature(confirmation.getId(),
                confirmation.getUserId(), confirmation.getProfileRevision(),
                confirmation.getWithdrawalAttemptId(), confirmation.getFingerprint(),
                confirmation.getPurpose(), confirmation.getExpiresAt()), confirmation.getSignature());
    }

    public boolean sameFingerprint(String current, String confirmed) {
        return equal(current, confirmed);
    }

    public static Instant micros(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    private static String canonical(String domain, String... values) {
        StringBuilder encoded = new StringBuilder(domain);
        for (String value : values) {
            if (value == null) throw new IllegalArgumentException("出生情報が不足しています");
            // 長さを先置きし、区切り文字を含む氏名でも異なる入力が同じ列へ結合されないようにする。
            encoded.append('|').append(value.length()).append(':').append(value);
        }
        return encoded.toString();
    }

    private static boolean equal(String expected, String actual) {
        return expected != null && actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }
}

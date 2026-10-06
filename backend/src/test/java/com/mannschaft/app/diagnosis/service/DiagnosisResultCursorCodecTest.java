package com.mannschaft.app.diagnosis.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.diagnosis.DiagnosisErrorCode;
import com.mannschaft.app.diagnosis.DiagnosisMethod;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** synthetic鍵だけで本人/filter/版/MICROS/改ざんの境界を検証する。 */
class DiagnosisResultCursorCodecTest {
    private static final Instant AT = Instant.parse("2026-10-07T02:00:00.123456Z");
    private static final UUID ID = UUID.fromString("01234567-89ab-7def-8123-456789abcdef");
    private final EncryptionService encryption = new EncryptionService(new byte[32], new byte[32]);
    private final DiagnosisResultCursorCodec codec = new DiagnosisResultCursorCodec(encryption);

    private void invalid(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOf(BusinessException.class).satisfies(error -> {
            var rejected = (BusinessException) error;
            assertThat(rejected.getErrorCode()).isEqualTo(DiagnosisErrorCode.INVALID_CURSOR);
            assertThat(rejected.getHttpStatusOverride()).isEqualTo(HttpStatus.BAD_REQUEST);
        });
    }

    @Test
    void roundTripKeepsExactMicrosUuidAndExplicitAllOrMethodFilter() {
        for (DiagnosisMethod method : new DiagnosisMethod[]{null, DiagnosisMethod.DIAGNOSIS, DiagnosisMethod.BIRTH_STYLE}) {
            String encoded = codec.encode(17L, method, AT, ID);
            assertThat(encoded).hasSize(95).doesNotContain("=", "|");
            assertThat(codec.decode(17L, method, encoded))
                    .isEqualTo(new DiagnosisResultCursorCodec.Position(AT, ID));
        }
    }

    @Test
    void anotherOwnerOrDifferentExplicitMethodIncludingAllRejects() {
        String all = codec.encode(17L, null, AT, ID);
        String quiz = codec.encode(17L, DiagnosisMethod.DIAGNOSIS, AT, ID);
        String birth = codec.encode(17L, DiagnosisMethod.BIRTH_STYLE, AT, ID);
        invalid(() -> codec.decode(18L, null, all));
        invalid(() -> codec.decode(17L, DiagnosisMethod.DIAGNOSIS, all));
        invalid(() -> codec.decode(17L, null, quiz));
        invalid(() -> codec.decode(17L, DiagnosisMethod.BIRTH_STYLE, quiz));
        invalid(() -> codec.decode(17L, DiagnosisMethod.DIAGNOSIS, birth));
    }

    @Test
    void modifiedPayloadSignatureAndAnotherDomainRejectBeforeUse() {
        String encoded = codec.encode(17L, null, AT, ID);
        byte[] payload = Base64.getUrlDecoder().decode(encoded.split("\\.")[0]);
        payload[payload.length - 1] ^= 1;
        invalid(() -> codec.decode(17L, null, base64(payload) + encoded.substring(encoded.indexOf('.'))));
        byte[] original = Base64.getUrlDecoder().decode(encoded.split("\\.")[0]);
        byte[] signature = Base64.getUrlDecoder().decode(encoded.split("\\.")[1]);
        signature[0] ^= 1;
        invalid(() -> codec.decode(17L, null, base64(original) + "." + base64(signature)));
        invalid(() -> codec.decode(17L, null, signed(original, "ranch-records-v1")));
    }

    @Test
    void malformedOversizedPaddedAndOldUnsignedCursorsReject() {
        String encoded = codec.encode(17L, null, AT, ID);
        String legacy = base64((AT + "|" + ID).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        for (String bad : new String[]{"", " ", "A".repeat(129), legacy, encoded + ".extra",
                encoded + "=", "!.!", encoded.substring(0, encoded.indexOf('.'))}) {
            invalid(() -> codec.decode(17L, null, bad));
        }
        invalid(() -> codec.decode(null, null, encoded));
        invalid(() -> codec.decode(17L, null, null));
    }

    @Test
    void correctlySignedUnsupportedVersionMethodAndInvalidTimeReject() {
        byte[] original = Base64.getUrlDecoder().decode(codec.encode(17L, null, AT, ID).split("\\.")[0]);
        byte[] version = original.clone(); version[0] = 2;
        invalid(() -> codec.decode(17L, null, signed(version, "diagnosis-results-v1")));
        byte[] method = original.clone(); method[9] = 3;
        invalid(() -> codec.decode(17L, null, signed(method, "diagnosis-results-v1")));
        for (int nanos : new int[]{-1, 1_000_000_000, 123_456_001}) {
            byte[] payload = original.clone(); ByteBuffer.wrap(payload).putInt(18, nanos);
            invalid(() -> codec.decode(17L, null, signed(payload, "diagnosis-results-v1")));
        }
        byte[] seconds = original.clone(); ByteBuffer.wrap(seconds).putLong(10, Long.MAX_VALUE);
        invalid(() -> codec.decode(17L, null, signed(seconds, "diagnosis-results-v1")));
        invalid(() -> codec.encode(17L, null, AT.plusNanos(1), ID));
    }

    @Test
    void rotatedSyntheticSigningKeyRejectsOldCursor() {
        byte[] rotated = new byte[32]; Arrays.fill(rotated, (byte) 1);
        var changed = new DiagnosisResultCursorCodec(new EncryptionService(new byte[32], rotated));
        invalid(() -> changed.decode(17L, null, codec.encode(17L, null, AT, ID)));
    }

    private String signed(byte[] payload, String domain) {
        return base64(payload) + "." + base64(HexFormat.of().parseHex(encryption.hmac(domain + "." + base64(payload))));
    }

    private String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

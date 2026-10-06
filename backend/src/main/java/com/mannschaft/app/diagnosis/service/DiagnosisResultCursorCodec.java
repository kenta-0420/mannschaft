package com.mannschaft.app.diagnosis.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.diagnosis.DiagnosisErrorCode;
import com.mannschaft.app.diagnosis.DiagnosisMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/** 私的結果の版・本人・明示filter・UTC MICROSを束縛する有限署名cursor。 */
@Component
public class DiagnosisResultCursorCodec {
    private static final byte VERSION = 1;
    private static final String DOMAIN = "diagnosis-results-v1";
    private static final int PAYLOAD_BYTES = 2 + Long.BYTES + Long.BYTES
            + Integer.BYTES + Long.BYTES + Long.BYTES;
    private static final int MAX_ENCODED_LENGTH = 128;
    private final EncryptionService encryption;

    public DiagnosisResultCursorCodec(EncryptionService encryption) {
        this.encryption = encryption;
    }

    public String encode(Long userId, DiagnosisMethod method, Instant completedAt, UUID id) {
        if (userId == null || completedAt == null || id == null
                || completedAt.getNano() % 1000 != 0) throw invalid();
        byte[] payload = ByteBuffer.allocate(PAYLOAD_BYTES)
                .put(VERSION).putLong(userId).put(methodTag(method))
                .putLong(completedAt.getEpochSecond()).putInt(completedAt.getNano())
                .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
        return base64(payload) + "." + base64(sign(payload));
    }

    public Position decode(Long userId, DiagnosisMethod method, String encoded) {
        if (userId == null || encoded == null || encoded.isBlank()
                || encoded.length() > MAX_ENCODED_LENGTH) throw invalid();
        String[] parts = encoded.split("\\.", -1);
        if (parts.length != 2) throw invalid();
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            if (payload.length != PAYLOAD_BYTES || signature.length != 32
                    || !base64(payload).equals(parts[0]) || !base64(signature).equals(parts[1])
                    || !MessageDigest.isEqual(sign(payload), signature)) throw invalid();
            ByteBuffer data = ByteBuffer.wrap(payload);
            if (data.get() != VERSION || data.getLong() != userId
                    || data.get() != methodTag(method)) throw invalid();
            long seconds = data.getLong();
            int nanos = data.getInt();
            if (nanos < 0 || nanos >= 1_000_000_000 || nanos % 1000 != 0) throw invalid();
            Instant completedAt = Instant.ofEpochSecond(seconds, nanos);
            UUID id = new UUID(data.getLong(), data.getLong());
            return new Position(completedAt, id);
        } catch (IllegalArgumentException | DateTimeException error) {
            throw invalid();
        }
    }

    private static byte methodTag(DiagnosisMethod method) {
        if (method == null) return 0; // all is explicitly bound, not inferred from a result row.
        return switch (method) {
            case DIAGNOSIS -> 1;
            case BIRTH_STYLE -> 2;
        };
    }

    private byte[] sign(byte[] payload) {
        return HexFormat.of().parseHex(encryption.hmac(DOMAIN + "." + base64(payload)));
    }

    private static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static BusinessException invalid() {
        return new BusinessException(DiagnosisErrorCode.INVALID_CURSOR, HttpStatus.BAD_REQUEST);
    }

    public record Position(Instant completedAt, UUID id) { }
}

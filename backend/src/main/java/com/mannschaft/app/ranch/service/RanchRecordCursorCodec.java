package com.mannschaft.app.ranch.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.ranch.RanchErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/** 本人IDとUTC MICROSを結び付けた版付き・改ざん検出用の私的records cursor。 */
@Component
public class RanchRecordCursorCodec {
    private static final byte VERSION = 1;
    private static final String RECORDS_DOMAIN = "ranch-records-v1";
    private static final String INVENTORY_DOMAIN = "ranch-inventory-v1";
    private static final int PAYLOAD_BYTES = 1 + Long.BYTES + Long.BYTES
            + Integer.BYTES + Long.BYTES + Long.BYTES;
    private final EncryptionService encryption;

    public RanchRecordCursorCodec(EncryptionService encryption) {
        this.encryption = encryption;
    }

    public String encode(Long userId, Instant occurredAt, UUID id) {
        return encodeFor(userId, occurredAt, id, RECORDS_DOMAIN);
    }

    public String encodeInventory(Long userId, Instant awardedAt, UUID id) {
        return encodeFor(userId, awardedAt, id, INVENTORY_DOMAIN);
    }

    private String encodeFor(Long userId, Instant occurredAt, UUID id, String domain) {
        if (userId == null || occurredAt == null || id == null
                || occurredAt.getNano() % 1000 != 0) throw invalid();
        byte[] payload = ByteBuffer.allocate(PAYLOAD_BYTES)
                .put(VERSION).putLong(userId).putLong(occurredAt.getEpochSecond())
                .putInt(occurredAt.getNano()).putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits()).array();
        return base64(payload) + "." + base64(sign(payload, domain));
    }

    public Position decode(Long userId, String encoded) {
        return decodeFor(userId, encoded, RECORDS_DOMAIN);
    }

    public Position decodeInventory(Long userId, String encoded) {
        return decodeFor(userId, encoded, INVENTORY_DOMAIN);
    }

    private Position decodeFor(Long userId, String encoded, String domain) {
        if (userId == null || encoded == null || encoded.isBlank()) throw invalid();
        String[] parts = encoded.split("\\.", -1);
        if (parts.length != 2) throw invalid();
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            if (payload.length != PAYLOAD_BYTES || signature.length != 32
                    || !MessageDigest.isEqual(sign(payload, domain), signature)) throw invalid();
            ByteBuffer data = ByteBuffer.wrap(payload);
            if (data.get() != VERSION || data.getLong() != userId) throw invalid();
            long seconds = data.getLong();
            int nanos = data.getInt();
            if (nanos < 0 || nanos >= 1_000_000_000 || nanos % 1000 != 0) {
                throw invalid();
            }
            Instant instant = Instant.ofEpochSecond(seconds, nanos);
            UUID id = new UUID(data.getLong(), data.getLong());
            return new Position(instant, id);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw invalid();
        }
    }

    private byte[] sign(byte[] payload, String domain) {
        return HexFormat.of().parseHex(encryption.hmac(domain + "." + base64(payload)));
    }

    private String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private BusinessException invalid() {
        return new BusinessException(RanchErrorCode.RANCH_006, HttpStatus.BAD_REQUEST);
    }

    public record Position(Instant occurredAt, UUID id) { }
}

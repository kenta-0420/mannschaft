package com.mannschaft.app.loyalty;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.EncryptionService;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Instant;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import java.time.LocalTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** actor/TEAM/filter/並び順へ束縛した署名付きseek cursor。 */
@Component
public class LoyaltyCursorCodec {
    private static final byte VERSION = 1;
    private static final int PAYLOAD_BYTES = 48;
    private static final String DOMAIN = "team-loyalty-cursor-v1.";
    private final EncryptionService encryption;
    public LoyaltyCursorCodec(EncryptionService encryption) { this.encryption = encryption; }

    public String encode(Context context, Position position) {
        validate(context);
        boolean pending = context.stream() == Stream.STAFF_PENDING;
        if (position == null || position.id() == null || pending != (position.expiresAt() != null)
                || pending && position.expiresAt().getNano() != 0) throw invalid();
        var expiry = pending ? position.expiresAt().atZone(UserZoneLocalDateTimeParser.SERVER_ZONE) : null;
        byte[] payload = ByteBuffer.allocate(PAYLOAD_BYTES).put(VERSION).put((byte) context.stream().ordinal())
                .putLong(context.actorUserId()).put((byte) (context.teamId() == null ? 0 : 1))
                .putLong(context.teamId() == null ? 0 : context.teamId())
                .putLong(position.id().getMostSignificantBits()).putLong(position.id().getLeastSignificantBits())
                .put((byte) (pending ? 1 : 0)).putLong(pending ? expiry.toLocalDate().toEpochDay() : 0)
                .putInt(pending ? expiry.toLocalTime().toSecondOfDay() : 0).array();
        return base64(payload) + "." + base64(sign(payload));
    }

    public Position decode(Context context, String cursor) {
        validate(context);
        if (cursor == null || cursor.isBlank() || cursor.length() > 512) throw invalid();
        String[] parts = cursor.split("\\.", -1);
        if (parts.length != 2) throw invalid();
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            if (payload.length != PAYLOAD_BYTES || signature.length != 32
                    || !base64(payload).equals(parts[0]) || !base64(signature).equals(parts[1])
                    || !MessageDigest.isEqual(sign(payload), signature)) throw invalid();
            ByteBuffer data = ByteBuffer.wrap(payload);
            if (data.get() != VERSION || Byte.toUnsignedInt(data.get()) != context.stream().ordinal()
                    || data.getLong() != context.actorUserId()) throw invalid();
            byte teamMarker = data.get(); long team = data.getLong();
            if (teamMarker != (context.teamId() == null ? 0 : 1)
                    || team != (context.teamId() == null ? 0 : context.teamId())) throw invalid();
            UUID id = new UUID(data.getLong(), data.getLong());
            byte expiryMarker = data.get(); long day = data.getLong(); int second = data.getInt();
            boolean pending = context.stream() == Stream.STAFF_PENDING;
            if (expiryMarker != (pending ? 1 : 0) || !pending && (day != 0 || second != 0)) throw invalid();
            Instant expiry = pending ? LocalDate.ofEpochDay(day).atTime(LocalTime.ofSecondOfDay(second))
                    .atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant() : null;
            return new Position(id, expiry);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw invalid();
        }
    }

    private void validate(Context context) {
        if (context == null || context.stream() == null || context.actorUserId() == null
                || context.actorUserId() <= 0 || context.teamId() != null && context.teamId() <= 0) throw invalid();
        boolean personal = context.stream() == Stream.ACCOUNTS || context.stream() == Stream.MY_REQUESTS;
        if (personal != (context.teamId() == null)) throw invalid();
    }
    private byte[] sign(byte[] payload) {
        return HexFormat.of().parseHex(encryption.hmac(DOMAIN + base64(payload)));
    }
    private String base64(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private BusinessException invalid() { return new BusinessException(CommonErrorCode.COMMON_001); }

    /** v1のwire順序。並べ替える場合はcursor版も変更する。 */
    public enum Stream { ACCOUNTS, TRANSACTIONS, MY_REQUESTS, STAFF_ALL, STAFF_PENDING }
    public record Context(Stream stream, Long actorUserId, Long teamId) { }
    public record Position(UUID id, Instant expiresAt) { }
}

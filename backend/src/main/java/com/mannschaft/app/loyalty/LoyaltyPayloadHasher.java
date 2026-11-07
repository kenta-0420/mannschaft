package com.mannschaft.app.loyalty;

import com.mannschaft.app.loyalty.dto.ApproveRequest;
import com.mannschaft.app.loyalty.dto.RequestActionRequest;
import com.mannschaft.app.loyalty.dto.RedeemRequest;
import com.mannschaft.app.loyalty.dto.SelfAwardRequest;
import com.mannschaft.app.loyalty.dto.StaffAwardRequest;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** 再送照合用の型付きpayload。raw tokenの解析やQR再検証には依存しない。 */
@Component
public class LoyaltyPayloadHasher {
    public byte[] staffAward(Long team, StaffAwardRequest body) {
        return digest(RequestOperation.STAFF_AWARD, team, null, data -> {
            data.token(body.qrToken()); data.uuid(body.ruleId());
            data.number(body.expectedRuleVersion()); data.number(body.expectedProgramVersion());
        });
    }

    public byte[] selfSubmit(Long team, SelfAwardRequest body) {
        return digest(RequestOperation.SELF_SUBMIT, team, null, data -> {
            data.token(body.locationToken()); data.uuid(body.ruleId());
            data.number(body.expectedRuleVersion()); data.number(body.expectedProgramVersion());
        });
    }

    public byte[] redeem(Long team, RedeemRequest body) {
        return digest(RequestOperation.REDEEM, team, null, data -> {
            data.token(body.qrToken()); data.uuid(body.rewardId());
            data.number(body.expectedRewardVersion()); data.number(body.expectedProgramVersion());
        });
    }

    public byte[] approve(Long team, UUID request, ApproveRequest body) {
        return digest(RequestOperation.APPROVE, team, request, data -> {
            data.number(body.expectedRequestVersion()); data.text(body.verificationCode());
        });
    }

    public byte[] action(RequestOperation operation, Long team, UUID request, RequestActionRequest body) {
        if (operation != RequestOperation.CANCEL && operation != RequestOperation.REJECT) {
            throw new IllegalArgumentException("申請取消または却下の操作が必要です");
        }
        return digest(operation, team, request, data -> data.number(body.expectedRequestVersion()));
    }

    private byte[] digest(RequestOperation operation, Long team, UUID request, Consumer<Canonical> body) {
        Canonical data = new Canonical();
        data.text("team-loyalty-payload-v1"); data.text(operation.name());
        data.number(team); data.uuid(request); body.accept(data);
        return sha256(data.bytes.toByteArray());
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            // Java実装が必須とするSHA-256。入力値を例外messageへ含めない。
            throw new IllegalStateException("SHA-256を使用できません", exception);
        }
    }

    /** 型ごとにnull markerと固定幅/長さを付け、JSON整形や文字列連結の曖昧さを排除する。 */
    private static final class Canonical {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean present(Object value) { bytes.write(value == null ? 0 : 1); return value != null; }
        void number(Long value) {
            if (present(value)) bytes.writeBytes(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
        }
        void uuid(UUID value) {
            if (present(value)) bytes.writeBytes(ByteBuffer.allocate(16)
                    .putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array());
        }
        void text(String value) {
            if (present(value)) {
                byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
                bytes.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array());
                bytes.writeBytes(encoded);
            }
        }
        void token(String value) {
            if (present(value)) bytes.writeBytes(sha256(value.getBytes(StandardCharsets.UTF_8)));
        }
    }
}

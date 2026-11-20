package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.common.ErrorResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * F06.4.1 チーム定義の definitionToken（API 契約 §3）。
 *
 * <p>teamId・definitionUuid・revision・定義 hash・本人 ID・失効時刻を束ね、AES-256-GCM で暗号化・認証した
 * opaque 文字列（{@link EncryptionService}）。<b>組織 ID は束縛しない</b>（チームは親組織 0 件・複数件があり得る。
 * 2026-11-18 マスター裁定）。有効期間は共通 {@code utcClock} 基準で発行から {@value #TTL_MINUTES} 分。</p>
 *
 * <p>欠落・改竄・期限切れ・束縛不一致（他人・別定義・版不一致）はすべて {@code COMMON_001}（400）で
 * {@code fieldErrors[].field=definitionToken} を返す。token 自体はログ・応答メッセージへ載せない。</p>
 */
@Component
public class EffortDefinitionTokenCodec {

    /** 有効期間（分）。 */
    public static final long TTL_MINUTES = 10;
    private static final Duration TTL = Duration.ofMinutes(TTL_MINUTES);
    private static final int MAX_LENGTH = 2048;
    private static final String VERSION = "d1";
    private static final String SEPARATOR = "|";
    private static final String FIELD = "definitionToken";

    private final EncryptionService encryption;
    private final Clock clock;

    public EffortDefinitionTokenCodec(EncryptionService encryption, @Qualifier("utcClock") Clock clock) {
        this.encryption = encryption;
        this.clock = clock;
    }

    /** 発行した token と失効時刻。 */
    public record Issued(String token, Instant expiresAt) {
    }

    /** 検証済み token の束縛内容。 */
    public record Claims(Long userId, Long teamId, UUID definitionUuid, int revision, String definitionHash) {
    }

    public Issued issue(Long userId, Long teamId, UUID definitionUuid, int revision, String definitionHash) {
        Instant expiresAt = clock.instant().plus(TTL);
        String plain = String.join(SEPARATOR, VERSION, String.valueOf(userId), String.valueOf(teamId),
                definitionUuid.toString(), String.valueOf(revision), definitionHash,
                String.valueOf(expiresAt.toEpochMilli()));
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(encryption.encrypt(plain).getBytes(StandardCharsets.US_ASCII));
        return new Issued(token, Instant.ofEpochMilli(expiresAt.toEpochMilli()));
    }

    /**
     * token を復号し、本人・定義 UUID・revision の束縛と有効期限を検証する。
     * teamId と定義 hash の照合は呼び出し側が保存済みの定義と突き合わせて行う。
     */
    public Claims verify(String token, Long userId, UUID definitionUuid, int revision) {
        if (token == null || token.isBlank() || token.length() > MAX_LENGTH) {
            throw invalid();
        }
        Claims claims;
        try {
            String standard = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.US_ASCII);
            String[] parts = encryption.decrypt(standard).split("\\|", -1);
            if (parts.length != 7 || !VERSION.equals(parts[0])) {
                throw invalid();
            }
            if (Instant.ofEpochMilli(Long.parseLong(parts[6])).isBefore(clock.instant())) {
                throw invalid();
            }
            claims = new Claims(Long.valueOf(parts[1]), Long.valueOf(parts[2]), UUID.fromString(parts[3]),
                    Integer.parseInt(parts[4]), parts[5]);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            // 復号失敗（改竄・鍵違い）・書式不正・数値/UUID 解析失敗はすべて不正 token。入力値は記録しない。
            throw invalid();
        }
        if (!claims.userId().equals(userId) || !claims.definitionUuid().equals(definitionUuid)
                || claims.revision() != revision) {
            throw invalid();
        }
        return claims;
    }

    /** token 不正（欠落・改竄・期限切れ・束縛不一致を区別しない）。 */
    public static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.COMMON_001,
                List.of(new ErrorResponse.FieldError(FIELD, "定義の確認期限が切れたか、内容が一致しません。もう一度選び直してください")));
    }
}

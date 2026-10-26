package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.common.BusinessException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AdOpenPixelJwtService} 単体テスト（F09.17 Phase 11-b）。
 *
 * <p>重要観点:</p>
 * <ul>
 *   <li>generate → verify 成功</li>
 *   <li>改竄 → AD_OPEN_PIXEL_TOKEN_INVALID</li>
 *   <li>期限切れも AD_OPEN_PIXEL_TOKEN_INVALID で握り潰す（Controller が 200 GIF を返すため）</li>
 *   <li><strong>PII 保護: claims/OpenPixelClaims に user_id 関連フィールドが存在しないこと</strong></li>
 * </ul>
 */
@DisplayName("AdOpenPixelJwtService 単体テスト")
class AdOpenPixelJwtServiceTest {

    private static final String SECRET = "open-pixel-test-secret-256bit-minimum-1234567890";
    private AdOpenPixelJwtService service;

    @BeforeEach
    void setUp() {
        service = new AdOpenPixelJwtService(SECRET, "fallback-key-also-256bit-minimum-12345678");
    }

    @Test
    @DisplayName("generate → verify が成功し claims が復元できる")
    void generateAndVerifySuccess() {
        UUID deliveryId = UUID.randomUUID();
        String token = service.generate(deliveryId, "EMAIL");

        AdOpenPixelJwtService.OpenPixelClaims claims = service.verify(token);

        assertThat(claims.deliveryId()).isEqualTo(deliveryId);
        assertThat(claims.type()).isEqualTo("EMAIL");
    }

    @Test
    @DisplayName("改竄トークン → AD_OPEN_PIXEL_TOKEN_INVALID")
    void tamperedTokenRejected() {
        SecretKey wrongKey = Keys.hmacShaKeyFor(
                "wrong-key-256bit-minimum-1234567890-abcdefgh".getBytes(StandardCharsets.UTF_8));
        String tampered = Jwts.builder()
                .issuer("mannschaft-ads")
                .claim("did", UUID.randomUUID().toString())
                .claim("t", "EMAIL")
                .signWith(wrongKey)
                .compact();

        assertThatThrownBy(() -> service.verify(tampered))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AdCampaignErrorCode.AD_OPEN_PIXEL_TOKEN_INVALID);
    }

    @Test
    @DisplayName("空文字 → AD_OPEN_PIXEL_TOKEN_INVALID")
    void emptyTokenRejected() {
        assertThatThrownBy(() -> service.verify(""))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AdCampaignErrorCode.AD_OPEN_PIXEL_TOKEN_INVALID);
    }

    @Test
    @DisplayName("不正な type → 生成時に IllegalArgumentException")
    void invalidTypeRejected() {
        assertThatThrownBy(() -> service.generate(UUID.randomUUID(), "INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("4 種 type すべて受理可能")
    void allTypesAccepted() {
        UUID id = UUID.randomUUID();
        for (String t : new String[]{"ANNOUNCEMENT", "EMAIL", "PUSH", "BANNER"}) {
            String token = service.generate(id, t);
            assertThat(service.verify(token).type()).isEqualTo(t);
        }
    }

    @Test
    @DisplayName("【PII 保護】OpenPixelClaims に user_id 関連のフィールドが存在しない（反射確認）")
    void claimsDoNotContainUserId() {
        RecordComponent[] components = AdOpenPixelJwtService.OpenPixelClaims.class.getRecordComponents();
        List<String> names = Arrays.stream(components).map(RecordComponent::getName).toList();
        // user_id を匂わせる名前が一切無いこと
        assertThat(names).containsExactlyInAnyOrder("deliveryId", "type");
        assertThat(names).noneMatch(n ->
                n.toLowerCase().contains("user")
                || n.toLowerCase().contains("uid")
                || n.toLowerCase().contains("recipient")
                || n.toLowerCase().contains("email")
        );
    }

    @Test
    @DisplayName("【PII 保護】生成された JWT 本体の payload にも uid / user 系の claim が含まれない")
    void jwtPayloadDoesNotContainUserId() throws Exception {
        UUID deliveryId = UUID.randomUUID();
        String token = service.generate(deliveryId, "EMAIL");

        // 同じ署名鍵で payload を parse して claims を確認
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Claims parsed = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        // claim キーが did / t / iss / iat / exp 以外含まれていないこと（uid が無いこと）
        assertThat(parsed.keySet()).contains("did", "t");
        // PII を匂わせる名前の claim が一切存在しないこと
        assertThat(parsed.keySet()).noneMatch(k -> {
            String lower = k.toLowerCase();
            return lower.equals("uid")
                    || lower.equals("user_id")
                    || lower.equals("userid")
                    || lower.equals("sub")
                    || lower.contains("email")
                    || lower.contains("recipient")
                    || lower.contains("user");
        });
    }

    @Test
    @DisplayName("AdOpenPixelJwtService 本体にも user_id を受け取るメソッドが存在しない（API 設計レベルでの PII 保護）")
    void serviceMethodsDoNotAcceptUserId() {
        boolean hasUserIdParam = Arrays.stream(AdOpenPixelJwtService.class.getDeclaredMethods())
                .flatMap(m -> Arrays.stream(m.getParameters()))
                .anyMatch(p -> {
                    String pname = p.getName().toLowerCase();
                    return pname.contains("user") || pname.contains("uid") || pname.contains("recipient");
                });
        assertThat(hasUserIdParam)
                .as("AdOpenPixelJwtService の API には user_id / recipient を含む引数が存在してはならない")
                .isFalse();
    }

    @Test
    @DisplayName("delivery_id が UUID 形式でないと AD_OPEN_PIXEL_TOKEN_INVALID")
    void malformedDeliveryIdRejected() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .issuer("mannschaft-ads")
                .expiration(new java.util.Date(System.currentTimeMillis() + 60_000))
                .claim("did", "not-a-uuid")
                .claim("t", "EMAIL")
                .signWith(key)
                .compact();

        assertThatThrownBy(() -> service.verify(token))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AdCampaignErrorCode.AD_OPEN_PIXEL_TOKEN_INVALID);
    }
}

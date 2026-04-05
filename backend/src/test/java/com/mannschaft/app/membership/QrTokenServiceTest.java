package com.mannschaft.app.membership;

import com.mannschaft.app.membership.service.QrTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link QrTokenService} の単体テスト。
 * QRトークンの生成・パース・署名検証を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QrTokenService 単体テスト")
class QrTokenServiceTest {

    @InjectMocks
    private QrTokenService qrTokenService;

    // ========================================
    // テスト用定数
    // ========================================

    private static final String CARD_CODE = "card-uuid-001";
    private static final String QR_SECRET = "test-secret-64-chars-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String LOCATION_CODE = "loc-uuid-001";
    private static final String LOCATION_SECRET = "loc-secret-64-chars-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    // ========================================
    // generateMemberCardQrToken / parseMemberCardToken
    // ========================================

    @Nested
    @DisplayName("generateMemberCardQrToken")
    class GenerateMemberCardQrToken {

        @Test
        @DisplayName("正常系: トークンが4パートで生成される")
        void 生成_正常_4パート形式() {
            // When
            String token = qrTokenService.generateMemberCardQrToken(CARD_CODE, QR_SECRET);

            // Then
            assertThat(token).isNotBlank();
            String[] parts = token.split("\\.", 4);
            assertThat(parts).hasSize(4);
            assertThat(parts[0]).isEqualTo(CARD_CODE);
        }

        @Test
        @DisplayName("正常系: 生成されたトークンがパースできる")
        void 生成_正常_パース可能() {
            // Given
            String token = qrTokenService.generateMemberCardQrToken(CARD_CODE, QR_SECRET);

            // When
            QrTokenService.MemberCardTokenPayload payload = qrTokenService.parseMemberCardToken(token);

            // Then
            assertThat(payload).isNotNull();
            assertThat(payload.cardCode()).isEqualTo(CARD_CODE);
            assertThat(payload.expiresAt()).isGreaterThan(payload.issuedAt());
        }
    }

    // ========================================
    // parseMemberCardToken
    // ========================================

    @Nested
    @DisplayName("parseMemberCardToken")
    class ParseMemberCardToken {

        @Test
        @DisplayName("異常系: nullでnull返却")
        void パース_null_null返却() {
            // When
            QrTokenService.MemberCardTokenPayload payload = qrTokenService.parseMemberCardToken(null);

            // Then
            assertThat(payload).isNull();
        }

        @Test
        @DisplayName("異常系: 空文字でnull返却")
        void パース_空文字_null返却() {
            // When
            QrTokenService.MemberCardTokenPayload payload = qrTokenService.parseMemberCardToken("");

            // Then
            assertThat(payload).isNull();
        }

        @Test
        @DisplayName("異常系: パート数不足でnull返却")
        void パース_パート不足_null返却() {
            // When
            QrTokenService.MemberCardTokenPayload payload = qrTokenService.parseMemberCardToken("a.b.c");

            // Then
            assertThat(payload).isNull();
        }

        @Test
        @DisplayName("異常系: 数値パース不可でnull返却")
        void パース_数値不正_null返却() {
            // When
            QrTokenService.MemberCardTokenPayload payload =
                    qrTokenService.parseMemberCardToken("code.notanumber.notanumber.sig");

            // Then
            assertThat(payload).isNull();
        }

        @Test
        @DisplayName("正常系: 正しい形式でパースが成功する")
        void パース_正常_パース成功() {
            // When
            QrTokenService.MemberCardTokenPayload payload =
                    qrTokenService.parseMemberCardToken("card-code.1234567890.1234568190.signature");

            // Then
            assertThat(payload).isNotNull();
            assertThat(payload.cardCode()).isEqualTo("card-code");
            assertThat(payload.issuedAt()).isEqualTo(1234567890L);
            assertThat(payload.expiresAt()).isEqualTo(1234568190L);
            assertThat(payload.signature()).isEqualTo("signature");
        }
    }

    // ========================================
    // verifyMemberCardSignature
    // ========================================

    @Nested
    @DisplayName("verifyMemberCardSignature")
    class VerifyMemberCardSignature {

        @Test
        @DisplayName("正常系: 正しい署名が検証に成功する")
        void 検証_正しい署名_成功() {
            // Given
            String token = qrTokenService.generateMemberCardQrToken(CARD_CODE, QR_SECRET);
            QrTokenService.MemberCardTokenPayload payload = qrTokenService.parseMemberCardToken(token);

            // When
            boolean result = qrTokenService.verifyMemberCardSignature(payload, QR_SECRET);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("異常系: 異なるシークレットで検証が失敗する")
        void 検証_不正シークレット_失敗() {
            // Given
            String token = qrTokenService.generateMemberCardQrToken(CARD_CODE, QR_SECRET);
            QrTokenService.MemberCardTokenPayload payload = qrTokenService.parseMemberCardToken(token);

            // When
            boolean result = qrTokenService.verifyMemberCardSignature(payload, "wrong-secret");

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("異常系: 改ざんされた署名で検証が失敗する")
        void 検証_改ざん署名_失敗() {
            // Given
            QrTokenService.MemberCardTokenPayload payload =
                    new QrTokenService.MemberCardTokenPayload(CARD_CODE, 1234567890L, 1234568190L, "tampered-sig");

            // When
            boolean result = qrTokenService.verifyMemberCardSignature(payload, QR_SECRET);

            // Then
            assertThat(result).isFalse();
        }
    }

    // ========================================
    // isTokenExpired
    // ========================================

    @Nested
    @DisplayName("isTokenExpired")
    class IsTokenExpired {

        @Test
        @DisplayName("正常系: 生成直後のトークンは未期限切れ")
        void 検証_生成直後_未期限切れ() {
            // Given
            String token = qrTokenService.generateMemberCardQrToken(CARD_CODE, QR_SECRET);
            QrTokenService.MemberCardTokenPayload payload = qrTokenService.parseMemberCardToken(token);

            // When
            boolean expired = qrTokenService.isTokenExpired(payload);

            // Then
            assertThat(expired).isFalse();
        }

        @Test
        @DisplayName("異常系: 過去のexpiresAtで期限切れ")
        void 検証_過去_期限切れ() {
            // Given
            QrTokenService.MemberCardTokenPayload payload =
                    new QrTokenService.MemberCardTokenPayload(CARD_CODE, 1000000000L, 1000000300L, "sig");

            // When
            boolean expired = qrTokenService.isTokenExpired(payload);

            // Then
            assertThat(expired).isTrue();
        }
    }

    // ========================================
    // generateLocationQrToken / parseLocationToken
    // ========================================

    @Nested
    @DisplayName("generateLocationQrToken")
    class GenerateLocationQrToken {

        @Test
        @DisplayName("正常系: 拠点QRトークンが生成される")
        void 生成_正常_トークン生成() {
            // When
            String token = qrTokenService.generateLocationQrToken(LOCATION_CODE, LOCATION_SECRET);

            // Then
            assertThat(token).isNotBlank();
            assertThat(token).startsWith(LOCATION_CODE + ".");
        }

        @Test
        @DisplayName("正常系: 生成されたトークンがパースできる")
        void 生成_正常_パース可能() {
            // Given
            String token = qrTokenService.generateLocationQrToken(LOCATION_CODE, LOCATION_SECRET);

            // When
            QrTokenService.LocationTokenPayload payload = qrTokenService.parseLocationToken(token);

            // Then
            assertThat(payload).isNotNull();
            assertThat(payload.locationCode()).isEqualTo(LOCATION_CODE);
            assertThat(payload.signature()).isNotBlank();
        }
    }

    // ========================================
    // parseLocationToken
    // ========================================

    @Nested
    @DisplayName("parseLocationToken")
    class ParseLocationToken {

        @Test
        @DisplayName("異常系: nullでnull返却")
        void パース_null_null返却() {
            assertThat(qrTokenService.parseLocationToken(null)).isNull();
        }

        @Test
        @DisplayName("異常系: 空文字でnull返却")
        void パース_空文字_null返却() {
            assertThat(qrTokenService.parseLocationToken("")).isNull();
        }

        @Test
        @DisplayName("異常系: ドットなしでnull返却")
        void パース_ドットなし_null返却() {
            assertThat(qrTokenService.parseLocationToken("no-dot-token")).isNull();
        }

        @Test
        @DisplayName("異常系: ドットが先頭でnull返却")
        void パース_先頭ドット_null返却() {
            assertThat(qrTokenService.parseLocationToken(".signature-only")).isNull();
        }

        @Test
        @DisplayName("異常系: ドットが末尾でnull返却")
        void パース_末尾ドット_null返却() {
            assertThat(qrTokenService.parseLocationToken("code-only.")).isNull();
        }
    }

    // ========================================
    // verifyLocationSignature
    // ========================================

    @Nested
    @DisplayName("verifyLocationSignature")
    class VerifyLocationSignature {

        @Test
        @DisplayName("正常系: 正しい署名が検証に成功する")
        void 検証_正しい署名_成功() {
            // Given
            String token = qrTokenService.generateLocationQrToken(LOCATION_CODE, LOCATION_SECRET);
            QrTokenService.LocationTokenPayload payload = qrTokenService.parseLocationToken(token);

            // When
            boolean result = qrTokenService.verifyLocationSignature(payload, LOCATION_SECRET);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("異常系: 異なるシークレットで検証が失敗する")
        void 検証_不正シークレット_失敗() {
            // Given
            String token = qrTokenService.generateLocationQrToken(LOCATION_CODE, LOCATION_SECRET);
            QrTokenService.LocationTokenPayload payload = qrTokenService.parseLocationToken(token);

            // When
            boolean result = qrTokenService.verifyLocationSignature(payload, "wrong-secret");

            // Then
            assertThat(result).isFalse();
        }
    }

    // ========================================
    // generateSecret
    // ========================================

    @Nested
    @DisplayName("generateSecret")
    class GenerateSecret {

        @Test
        @DisplayName("正常系: 64文字のシークレットが生成される")
        void 生成_正常_64文字() {
            // When
            String secret = qrTokenService.generateSecret();

            // Then
            assertThat(secret).hasSize(64);
            assertThat(secret).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("正常系: 毎回異なるシークレットが生成される")
        void 生成_正常_ユニーク() {
            // When
            String secret1 = qrTokenService.generateSecret();
            String secret2 = qrTokenService.generateSecret();

            // Then
            assertThat(secret1).isNotEqualTo(secret2);
        }
    }

    // ========================================
    // getExpirySeconds
    // ========================================

    @Nested
    @DisplayName("getExpirySeconds")
    class GetExpirySeconds {

        @Test
        @DisplayName("正常系: 有効期限が300秒を返す")
        void 取得_正常_300秒() {
            // When
            int expiry = qrTokenService.getExpirySeconds();

            // Then
            assertThat(expiry).isEqualTo(300);
        }
    }
}

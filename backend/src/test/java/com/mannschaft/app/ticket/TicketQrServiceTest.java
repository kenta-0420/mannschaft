package com.mannschaft.app.ticket;

import com.mannschaft.app.ticket.service.TicketQrService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TicketQrService} の単体テスト。
 */
@DisplayName("TicketQrService 単体テスト")
class TicketQrServiceTest {

    private final TicketQrService service = new TicketQrService();

    @Nested
    @DisplayName("generateToken")
    class GenerateToken {

        @Test
        @DisplayName("正常系: ワンタイムトークンが正しい形式で生成される")
        void トークン生成成功() {
            TicketQrService.QrGenerateResult result = service.generateToken(1L);

            assertThat(result.qrPayload()).startsWith("tkt_1_otp_");
            assertThat(result.expiresAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("validateAndConsumeToken")
    class ValidateAndConsumeToken {

        @Test
        @DisplayName("正常系: 有効なトークンでbookIdが返される")
        void 有効なトークン() {
            TicketQrService.QrGenerateResult generated = service.generateToken(42L);
            Long bookId = service.validateAndConsumeToken(generated.qrPayload());

            assertThat(bookId).isEqualTo(42L);
        }

        @Test
        @DisplayName("異常系: 同じトークンの二重使用はエラー")
        void 二重使用エラー() {
            TicketQrService.QrGenerateResult generated = service.generateToken(42L);
            service.validateAndConsumeToken(generated.qrPayload());

            assertThatThrownBy(() -> service.validateAndConsumeToken(generated.qrPayload()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("異常系: null ペイロードはエラー")
        void nullペイロード() {
            assertThatThrownBy(() -> service.validateAndConsumeToken(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("異常系: 不正な形式のペイロードはエラー")
        void 不正形式() {
            assertThatThrownBy(() -> service.validateAndConsumeToken("invalid_format"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}

package com.mannschaft.app.line;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.line.entity.LineBotConfigEntity;
import com.mannschaft.app.line.entity.LineMessageLogEntity;
import com.mannschaft.app.line.repository.LineBotConfigRepository;
import com.mannschaft.app.line.repository.LineMessageLogRepository;
import com.mannschaft.app.line.service.LineMessagingApiClient;
import com.mannschaft.app.line.service.LineWebhookService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link LineWebhookService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LineWebhookService 単体テスト")
class LineWebhookServiceTest {

    /** テスト用の固定 channel secret（HMAC 鍵）。 */
    private static final String CHANNEL_SECRET = "test-channel-secret-0123456789";

    @Mock
    private LineBotConfigRepository lineBotConfigRepository;
    @Mock
    private LineMessageLogRepository lineMessageLogRepository;
    @Mock
    private LineMessagingApiClient lineMessagingApiClient;
    @Mock
    private EncryptionService encryptionService;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private LineWebhookService service;

    /** 署名検証モードを設定する。 */
    private void setMode(String mode) {
        ReflectionTestUtils.setField(service, "signatureVerifyMode", mode);
    }

    /** 既知の channel secret と body から LINE 互換の Base64(HMAC-SHA256) 署名を生成する。 */
    private static String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(CHANNEL_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Nested
    @DisplayName("handleWebhook")
    class HandleWebhook {

        @Test
        @DisplayName("異常系: webhookSecret不一致でLINE_003例外")
        void 処理_シークレット不一致_例外() {
            // Given
            setMode("off");
            given(lineBotConfigRepository.findByWebhookSecret("invalid"))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.handleWebhook("invalid", "sig", "{}"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("LINE_003"));
        }

        @Test
        @DisplayName("正常系: isActiveがfalseの場合処理をスキップ")
        void 処理_無効設定_スキップ() {
            // Given
            setMode("off");
            LineBotConfigEntity config = LineBotConfigEntity.builder()
                    .scopeType(ScopeType.TEAM).scopeId(1L)
                    .channelId("ch1").webhookSecret("ws1")
                    .notificationEnabled(true).isActive(false).build();
            given(lineBotConfigRepository.findByWebhookSecret("ws1"))
                    .willReturn(Optional.of(config));

            // When
            service.handleWebhook("ws1", "sig", "{}");

            // Then — ログが保存されない
            verify(lineMessageLogRepository, never()).save(any(LineMessageLogEntity.class));
        }

        @Test
        @DisplayName("正常系: eventsがnullのボディでもクラッシュしない")
        void 処理_eventsなし_ログのみ保存() throws Exception {
            // Given
            setMode("off");
            LineBotConfigEntity config = LineBotConfigEntity.builder()
                    .scopeType(ScopeType.TEAM).scopeId(1L)
                    .channelId("ch1")
                    .channelSecretEnc(new byte[]{1, 2})
                    .channelAccessTokenEnc(new byte[]{3, 4})
                    .webhookSecret("ws1")
                    .notificationEnabled(true).isActive(true).configuredBy(1L).build();
            given(lineBotConfigRepository.findByWebhookSecret("ws1"))
                    .willReturn(Optional.of(config));
            given(lineMessageLogRepository.save(any(LineMessageLogEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(encryptionService.decryptBytes(any())).willReturn("token".getBytes());

            String body = "{\"destination\":\"U123\",\"events\":[]}";

            // When
            service.handleWebhook("ws1", "sig", body);

            // Then — ログは保存される
            verify(lineMessageLogRepository).save(any(LineMessageLogEntity.class));
        }

        @Test
        @DisplayName("正常系: messageイベントが含まれている場合は自動返信する")
        void 処理_messageイベント_自動返信される() throws Exception {
            // Given
            setMode("off");
            LineBotConfigEntity config = LineBotConfigEntity.builder()
                    .scopeType(ScopeType.TEAM).scopeId(1L)
                    .channelId("ch1")
                    .channelSecretEnc(new byte[]{})
                    .channelAccessTokenEnc(new byte[]{})
                    .webhookSecret("ws2")
                    .notificationEnabled(true).isActive(true).configuredBy(1L).build();
            given(lineBotConfigRepository.findByWebhookSecret("ws2"))
                    .willReturn(Optional.of(config));
            given(lineMessageLogRepository.save(any(LineMessageLogEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(encryptionService.decryptBytes(any())).willReturn("access_token".getBytes());

            String body = """
                    {
                      "destination": "U123",
                      "events": [
                        {
                          "type": "message",
                          "replyToken": "reply123",
                          "source": {"userId": "line-user-001"},
                          "message": {"type": "text", "text": "こんにちは"}
                        }
                      ]
                    }""";

            // When
            service.handleWebhook("ws2", "sig", body);

            // Then
            verify(lineMessagingApiClient).replyMessage(any(), any(), any());
        }

        @Test
        @DisplayName("正常系: followイベントは例外なく処理される")
        void 処理_followイベント_例外なし() throws Exception {
            // Given
            setMode("off");
            LineBotConfigEntity config = LineBotConfigEntity.builder()
                    .scopeType(ScopeType.TEAM).scopeId(1L)
                    .channelId("ch1")
                    .channelSecretEnc(new byte[]{})
                    .channelAccessTokenEnc(new byte[]{})
                    .webhookSecret("ws3")
                    .notificationEnabled(false).isActive(true).configuredBy(1L).build();
            given(lineBotConfigRepository.findByWebhookSecret("ws3"))
                    .willReturn(Optional.of(config));
            given(lineMessageLogRepository.save(any(LineMessageLogEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(encryptionService.decryptBytes(any())).willReturn("token".getBytes());

            String body = """
                    {
                      "destination": "U123",
                      "events": [
                        {
                          "type": "follow",
                          "source": {"userId": "line-user-002"}
                        }
                      ]
                    }""";

            // When (例外がスローされないことを確認)
            service.handleWebhook("ws3", "sig", body);

            // Then — 自動返信なし
            verify(lineMessagingApiClient, never()).replyMessage(any(), any(), any());
        }

        private static org.assertj.core.api.AbstractAssert<?, ?> assertThat(String code) {
            return org.assertj.core.api.Assertions.assertThat(code);
        }
    }

    @Nested
    @DisplayName("X-Line-Signature 署名検証")
    class SignatureVerification {

        private LineBotConfigEntity activeConfig(String webhookSecret) {
            return LineBotConfigEntity.builder()
                    .scopeType(ScopeType.TEAM).scopeId(1L)
                    .channelId("ch1")
                    .channelSecretEnc(new byte[]{9, 9})
                    .channelAccessTokenEnc(new byte[]{8, 8})
                    .webhookSecret(webhookSecret)
                    .notificationEnabled(true).isActive(true).configuredBy(1L).build();
        }

        @Test
        @DisplayName("enforce: 有効署名なら処理を続行しログを保存する")
        void enforce_有効署名_処理続行() throws Exception {
            // Given
            setMode("enforce");
            String body = "{\"destination\":\"U123\",\"events\":[]}";
            given(lineBotConfigRepository.findByWebhookSecret("ws-valid"))
                    .willReturn(Optional.of(activeConfig("ws-valid")));
            given(lineMessageLogRepository.save(any(LineMessageLogEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            // channelSecretEnc の復号で channel secret 文字列のバイト列を返す
            given(encryptionService.decryptBytes(any()))
                    .willReturn(CHANNEL_SECRET.getBytes(StandardCharsets.UTF_8));

            // When
            service.handleWebhook("ws-valid", sign(body), body);

            // Then — ログが保存される（処理続行）
            verify(lineMessageLogRepository).save(any(LineMessageLogEntity.class));
        }

        @Test
        @DisplayName("enforce: 無効署名なら処理をスキップしログを保存しない（200相当）")
        void enforce_無効署名_スキップ() {
            // Given
            setMode("enforce");
            String body = "{\"destination\":\"U123\",\"events\":[]}";
            given(lineBotConfigRepository.findByWebhookSecret("ws-bad"))
                    .willReturn(Optional.of(activeConfig("ws-bad")));
            given(encryptionService.decryptBytes(any()))
                    .willReturn(CHANNEL_SECRET.getBytes(StandardCharsets.UTF_8));

            // When — 不正な署名（例外をスローせず正常 return すること＝200）
            service.handleWebhook("ws-bad", "INVALID_SIGNATURE", body);

            // Then — save は呼ばれない
            verify(lineMessageLogRepository, never()).save(any(LineMessageLogEntity.class));
        }

        @Test
        @DisplayName("enforce: ヘッダ欠落なら処理をスキップしログを保存しない")
        void enforce_ヘッダ欠落_スキップ() {
            // Given
            setMode("enforce");
            String body = "{\"destination\":\"U123\",\"events\":[]}";
            given(lineBotConfigRepository.findByWebhookSecret("ws-noheader"))
                    .willReturn(Optional.of(activeConfig("ws-noheader")));

            // When — signature = null
            service.handleWebhook("ws-noheader", null, body);

            // Then — save は呼ばれない（復号も行われない）
            verify(lineMessageLogRepository, never()).save(any(LineMessageLogEntity.class));
        }

        @Test
        @DisplayName("log-only: 無効署名でも処理を続行する（WARNログのみ）")
        void logOnly_無効署名_処理続行() throws Exception {
            // Given
            setMode("log-only");
            String body = "{\"destination\":\"U123\",\"events\":[]}";
            given(lineBotConfigRepository.findByWebhookSecret("ws-logonly"))
                    .willReturn(Optional.of(activeConfig("ws-logonly")));
            given(lineMessageLogRepository.save(any(LineMessageLogEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(encryptionService.decryptBytes(any()))
                    .willReturn(CHANNEL_SECRET.getBytes(StandardCharsets.UTF_8));

            // When — 不正署名でも log-only なら継続
            service.handleWebhook("ws-logonly", "INVALID_SIGNATURE", body);

            // Then — ログは保存される
            verify(lineMessageLogRepository).save(any(LineMessageLogEntity.class));
        }
    }
}

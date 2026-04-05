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

    @Nested
    @DisplayName("handleWebhook")
    class HandleWebhook {

        @Test
        @DisplayName("異常系: webhookSecret不一致でLINE_003例外")
        void 処理_シークレット不一致_例外() {
            // Given
            given(lineBotConfigRepository.findByWebhookSecret("invalid"))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.handleWebhook("invalid", "{}"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("LINE_003"));
        }

        @Test
        @DisplayName("正常系: isActiveがfalseの場合処理をスキップ")
        void 処理_無効設定_スキップ() {
            // Given
            LineBotConfigEntity config = LineBotConfigEntity.builder()
                    .scopeType(ScopeType.TEAM).scopeId(1L)
                    .channelId("ch1").webhookSecret("ws1")
                    .notificationEnabled(true).isActive(false).build();
            given(lineBotConfigRepository.findByWebhookSecret("ws1"))
                    .willReturn(Optional.of(config));

            // When
            service.handleWebhook("ws1", "{}");

            // Then — ログが保存されない
            verify(lineMessageLogRepository, never()).save(any(LineMessageLogEntity.class));
        }

        @Test
        @DisplayName("正常系: eventsがnullのボディでもクラッシュしない")
        void 処理_eventsなし_ログのみ保存() throws Exception {
            // Given
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
            service.handleWebhook("ws1", body);

            // Then — ログは保存される
            verify(lineMessageLogRepository).save(any(LineMessageLogEntity.class));
        }

        @Test
        @DisplayName("正常系: messageイベントが含まれている場合は自動返信する")
        void 処理_messageイベント_自動返信される() throws Exception {
            // Given
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
            service.handleWebhook("ws2", body);

            // Then
            verify(lineMessagingApiClient).replyMessage(any(), any(), any());
        }

        @Test
        @DisplayName("正常系: followイベントは例外なく処理される")
        void 処理_followイベント_例外なし() throws Exception {
            // Given
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
            service.handleWebhook("ws3", body);

            // Then — 自動返信なし
            verify(lineMessagingApiClient, never()).replyMessage(any(), any(), any());
        }

        private static org.assertj.core.api.AbstractAssert<?, ?> assertThat(String code) {
            return org.assertj.core.api.Assertions.assertThat(code);
        }
    }
}

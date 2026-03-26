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

        private static org.assertj.core.api.AbstractAssert<?, ?> assertThat(String code) {
            return org.assertj.core.api.Assertions.assertThat(code);
        }
    }
}

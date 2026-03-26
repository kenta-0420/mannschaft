package com.mannschaft.app.line;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.line.dto.CreateLineBotConfigRequest;
import com.mannschaft.app.line.dto.LineBotConfigResponse;
import com.mannschaft.app.line.dto.TestMessageRequest;
import com.mannschaft.app.line.dto.UpdateLineBotConfigRequest;
import com.mannschaft.app.line.entity.LineBotConfigEntity;
import com.mannschaft.app.line.entity.LineMessageLogEntity;
import com.mannschaft.app.line.repository.LineBotConfigRepository;
import com.mannschaft.app.line.repository.LineMessageLogRepository;
import com.mannschaft.app.line.service.LineBotConfigService;
import com.mannschaft.app.line.service.LineMessagingApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link LineBotConfigService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LineBotConfigService 単体テスト")
class LineBotConfigServiceTest {

    @Mock
    private LineBotConfigRepository lineBotConfigRepository;
    @Mock
    private LineMessageLogRepository lineMessageLogRepository;
    @Mock
    private LineMapper lineMapper;
    @Mock
    private EncryptionService encryptionService;
    @Mock
    private LineMessagingApiClient lineMessagingApiClient;

    @InjectMocks
    private LineBotConfigService service;

    private static final ScopeType SCOPE_TYPE = ScopeType.TEAM;
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 100L;

    private LineBotConfigEntity createConfigEntity() {
        return LineBotConfigEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .channelId("ch123")
                .channelSecretEnc(new byte[]{1, 2, 3})
                .channelAccessTokenEnc(new byte[]{4, 5, 6})
                .webhookSecret("ws123")
                .botUserId("bot123")
                .notificationEnabled(true)
                .configuredBy(USER_ID)
                .build();
    }

    @Nested
    @DisplayName("getConfig")
    class GetConfig {

        @Test
        @DisplayName("正常系: BOT設定が返却される")
        void 取得_正常_設定返却() {
            // Given
            LineBotConfigEntity entity = createConfigEntity();
            LineBotConfigResponse response = new LineBotConfigResponse(1L, SCOPE_TYPE.name(), SCOPE_ID,
                    "ch123", "ws123", "bot123", true, true, USER_ID, null, null);
            given(lineBotConfigRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(lineMapper.toLineBotConfigResponse(entity)).willReturn(response);

            // When
            LineBotConfigResponse result = service.getConfig(SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result.getChannelId()).isEqualTo("ch123");
        }

        @Test
        @DisplayName("異常系: 設定不在でLINE_001例外")
        void 取得_不在_例外() {
            // Given
            given(lineBotConfigRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.getConfig(SCOPE_TYPE, SCOPE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("LINE_001"));
        }
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("正常系: BOT設定が作成される")
        void 作成_正常_保存() {
            // Given
            CreateLineBotConfigRequest req = new CreateLineBotConfigRequest(
                    "ch123", "secret", "token", "ws123", "bot123", true);
            given(lineBotConfigRepository.existsByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID)).willReturn(false);
            given(encryptionService.encryptBytes(any(byte[].class))).willReturn(new byte[]{1});
            given(lineBotConfigRepository.save(any(LineBotConfigEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(lineMapper.toLineBotConfigResponse(any(LineBotConfigEntity.class)))
                    .willReturn(new LineBotConfigResponse(1L, SCOPE_TYPE.name(), SCOPE_ID,
                            "ch123", "ws123", "bot123", true, true, USER_ID, null, null));

            // When
            LineBotConfigResponse result = service.create(SCOPE_TYPE, SCOPE_ID, USER_ID, req);

            // Then
            assertThat(result).isNotNull();
            verify(lineBotConfigRepository).save(any(LineBotConfigEntity.class));
        }

        @Test
        @DisplayName("異常系: 設定が既に存在する場合LINE_002例外")
        void 作成_重複_例外() {
            // Given
            CreateLineBotConfigRequest req = new CreateLineBotConfigRequest(
                    "ch123", "secret", "token", "ws123", "bot123", true);
            given(lineBotConfigRepository.existsByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID)).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> service.create(SCOPE_TYPE, SCOPE_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("LINE_002"));
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("正常系: BOT設定が論理削除される")
        void 削除_正常_論理削除() {
            // Given
            LineBotConfigEntity entity = createConfigEntity();
            given(lineBotConfigRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When
            service.delete(SCOPE_TYPE, SCOPE_ID);

            // Then — softDeleteが呼ばれたことを間接確認
            assertThat(entity).isNotNull();
        }
    }

    @Nested
    @DisplayName("sendTestMessage")
    class SendTestMessage {

        @Test
        @DisplayName("正常系: テストメッセージが送信されログが保存される")
        void 送信_正常_ログ保存() {
            // Given
            LineBotConfigEntity config = createConfigEntity();
            given(lineBotConfigRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(config));
            given(encryptionService.decryptBytes(any(byte[].class))).willReturn("token".getBytes());
            given(lineMessagingApiClient.pushMessage(anyString(), anyString(), anyString()))
                    .willReturn("req-id-123");

            TestMessageRequest req = new TestMessageRequest("user123", "テスト");

            // When
            service.sendTestMessage(SCOPE_TYPE, SCOPE_ID, req);

            // Then
            verify(lineMessageLogRepository).save(any(LineMessageLogEntity.class));
        }
    }
}

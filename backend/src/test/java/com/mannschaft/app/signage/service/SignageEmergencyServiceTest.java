package com.mannschaft.app.signage.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.signage.entity.SignageScreenEntity;
import com.mannschaft.app.signage.repository.SignageEmergencyMessageRepository;
import com.mannschaft.app.signage.repository.SignageScreenRepository;
import com.mannschaft.app.signage.websocket.SignageWebSocketPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link SignageEmergencyService} 認可契約テスト（束5・AC-1-6）。
 *
 * <p>当該画面スコープの非ADMINが緊急メッセージをブロードキャストすると403（COMMON_002）で拒否され、
 * DB永続化・WebSocket配信のいずれも実行されない（偽の緊急告知の根治）ことを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SignageEmergencyService 認可契約テスト")
class SignageEmergencyServiceTest {

    @Mock
    private SignageEmergencyMessageRepository emergencyRepository;

    @Mock
    private SignageScreenRepository screenRepository;

    @Mock
    private SignageWebSocketPublisher webSocketPublisher;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private SignageEmergencyService emergencyService;

    private static final Long SCREEN_ID = 1L;
    private static final Long SCOPE_ID = 5L;
    private static final String SCOPE_TYPE = "TEAM";
    private static final Long SENT_BY = 999L;

    private SignageScreenEntity sampleScreen() {
        return SignageScreenEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .name("テスト画面")
                .createdBy(1L)
                .build();
    }

    @Nested
    @DisplayName("broadcastEmergency")
    class BroadcastEmergency {

        // AC-1-6: 当該screen scopeの非ADMINが緊急配信 → 403、DB保存・WebSocket配信いずれも非実行
        @Test
        @DisplayName("異常系: 非ADMINは403（COMMON_002）でDB保存・WebSocket配信されない")
        void broadcastEmergency_非ADMIN_403_配信されない() {
            given(screenRepository.findByIdAndDeletedAtIsNull(SCREEN_ID))
                    .willReturn(Optional.of(sampleScreen()));
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService)
                    .checkAdminOrAbove(SENT_BY, SCOPE_ID, SCOPE_TYPE);

            SignageEmergencyService.BroadcastEmergencyRequest req =
                    new SignageEmergencyService.BroadcastEmergencyRequest(
                            "偽の緊急告知", "#FF0000", "#FFFFFF", 30);

            assertThatThrownBy(() -> emergencyService.broadcastEmergency(SCREEN_ID, SENT_BY, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));

            verify(emergencyRepository, never()).save(any());
            verify(webSocketPublisher, never()).publishEmergency(anyLong(), anyString());
        }

        // 非回帰: 当該screen scopeのADMINは成功し、DB保存・WebSocket配信される
        @Test
        @DisplayName("正常系: ADMINは成功しDB保存・WebSocket配信される（非回帰）")
        void broadcastEmergency_ADMIN_成功() {
            given(screenRepository.findByIdAndDeletedAtIsNull(SCREEN_ID))
                    .willReturn(Optional.of(sampleScreen()));
            // checkAdminOrAbove は正常時 void で何もしない（ADMIN許可）

            SignageEmergencyService.BroadcastEmergencyRequest req =
                    new SignageEmergencyService.BroadcastEmergencyRequest(
                            "本当の緊急告知", "#FF0000", "#FFFFFF", 30);

            given(emergencyRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            SignageEmergencyService.EmergencyMessageResponse result =
                    emergencyService.broadcastEmergency(SCREEN_ID, SENT_BY, req);

            assertThat(result.message()).isEqualTo("本当の緊急告知");
            verify(accessControlService).checkAdminOrAbove(SENT_BY, SCOPE_ID, SCOPE_TYPE);
            verify(emergencyRepository).save(any());
            verify(webSocketPublisher).publishEmergency(eq(SCREEN_ID), eq("本当の緊急告知"));
        }
    }
}

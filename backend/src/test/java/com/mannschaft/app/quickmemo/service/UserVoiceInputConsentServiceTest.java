package com.mannschaft.app.quickmemo.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.quickmemo.dto.VoiceInputConsentRequest;
import com.mannschaft.app.quickmemo.dto.VoiceInputConsentResponse;
import com.mannschaft.app.quickmemo.entity.UserVoiceInputConsentEntity;
import com.mannschaft.app.quickmemo.repository.UserVoiceInputConsentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link UserVoiceInputConsentService} の単体テスト。
 * F02.5 ポイっとメモ機能の音声入力同意サービス層を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserVoiceInputConsentService 単体テスト")
class UserVoiceInputConsentServiceTest {

    @Mock
    private UserVoiceInputConsentRepository consentRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private UserVoiceInputConsentService consentService;

    // ========================================
    // テスト用定数
    // ========================================

    private static final Long USER_ID = 1L;
    private static final int CURRENT_VERSION = UserVoiceInputConsentService.CURRENT_VOICE_POLICY_VERSION;
    private static final String IP_ADDRESS = "192.168.1.1";
    private static final String USER_AGENT = "Mozilla/5.0 TestAgent";

    // ========================================
    // ヘルパーメソッド
    // ========================================

    /**
     * 有効な同意エンティティを生成する（revokedAt = null）。
     */
    private UserVoiceInputConsentEntity buildActiveConsent(Integer version) {
        return UserVoiceInputConsentEntity.builder()
                .userId(USER_ID)
                .version(version)
                .ipAddress(IP_ADDRESS)
                .userAgent(USER_AGENT)
                .build();
    }

    // ========================================
    // getActiveConsent
    // ========================================

    @Nested
    @DisplayName("getActiveConsent")
    class GetActiveConsent {

        @Test
        @DisplayName("getActiveConsent_有効な同意が存在する_同意情報が返る")
        void getActiveConsent_有効な同意が存在する_同意情報が返る() {
            // Given
            UserVoiceInputConsentEntity consent = buildActiveConsent(CURRENT_VERSION);
            given(consentRepository.findByUserIdAndVersionAndRevokedAtIsNull(USER_ID, CURRENT_VERSION))
                    .willReturn(Optional.of(consent));

            // When
            VoiceInputConsentResponse result = consentService.getActiveConsent(USER_ID, CURRENT_VERSION);

            // Then
            assertThat(result.hasConsent()).isTrue();
            assertThat(result.version()).isEqualTo(CURRENT_VERSION);
        }

        @Test
        @DisplayName("getActiveConsent_同意が存在しない_hasConsentがfalseのレスポンスが返る")
        void getActiveConsent_同意が存在しない_hasConsentがfalseのレスポンスが返る() {
            // Given
            given(consentRepository.findByUserIdAndVersionAndRevokedAtIsNull(USER_ID, CURRENT_VERSION))
                    .willReturn(Optional.empty());

            // When
            VoiceInputConsentResponse result = consentService.getActiveConsent(USER_ID, CURRENT_VERSION);

            // Then
            assertThat(result.hasConsent()).isFalse();
            assertThat(result.version()).isNull();
            assertThat(result.consentedAt()).isNull();
        }

        @Test
        @DisplayName("getActiveConsent_要求バージョンが現行より大きい_BusinessException(QM_032)が投げられる")
        void getActiveConsent_要求バージョンが現行より大きい_BusinessException_QM_032_が投げられる() {
            // Given
            int futureVersion = CURRENT_VERSION + 1;

            // When / Then
            assertThatThrownBy(() -> consentService.getActiveConsent(USER_ID, futureVersion))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException businessEx = (BusinessException) ex;
                        assertThat(businessEx.getErrorCode().getCode()).isEqualTo("QM_032");
                    });
            verify(consentRepository, never()).findByUserIdAndVersionAndRevokedAtIsNull(any(), any());
        }
    }

    // ========================================
    // grantConsent
    // ========================================

    @Nested
    @DisplayName("grantConsent")
    class GrantConsent {

        @Test
        @DisplayName("grantConsent_同バージョンの有効な同意が既存_冪等に既存を返す")
        void grantConsent_同バージョンの有効な同意が既存_冪等に既存を返す() {
            // Given
            UserVoiceInputConsentEntity existing = buildActiveConsent(CURRENT_VERSION);
            given(consentRepository.findByUserIdAndVersionAndRevokedAtIsNull(USER_ID, CURRENT_VERSION))
                    .willReturn(Optional.of(existing));

            VoiceInputConsentRequest req = new VoiceInputConsentRequest(CURRENT_VERSION);

            // When
            VoiceInputConsentResponse result = consentService.grantConsent(
                    USER_ID, req, IP_ADDRESS, USER_AGENT);

            // Then
            assertThat(result.hasConsent()).isTrue();
            assertThat(result.version()).isEqualTo(CURRENT_VERSION);
            // 既存がある場合は新規保存しない
            verify(consentRepository, never()).save(any());
        }

        @Test
        @DisplayName("grantConsent_同意が存在しない_新規同意が保存される")
        void grantConsent_同意が存在しない_新規同意が保存される() {
            // Given
            given(consentRepository.findByUserIdAndVersionAndRevokedAtIsNull(USER_ID, CURRENT_VERSION))
                    .willReturn(Optional.empty());
            UserVoiceInputConsentEntity saved = buildActiveConsent(CURRENT_VERSION);
            given(consentRepository.save(any(UserVoiceInputConsentEntity.class))).willReturn(saved);

            VoiceInputConsentRequest req = new VoiceInputConsentRequest(CURRENT_VERSION);

            // When
            VoiceInputConsentResponse result = consentService.grantConsent(
                    USER_ID, req, IP_ADDRESS, USER_AGENT);

            // Then
            assertThat(result.hasConsent()).isTrue();
            assertThat(result.version()).isEqualTo(CURRENT_VERSION);
            verify(consentRepository).save(any(UserVoiceInputConsentEntity.class));
        }
    }

    // ========================================
    // revokeConsent
    // ========================================

    @Nested
    @DisplayName("revokeConsent")
    class RevokeConsent {

        @Test
        @DisplayName("revokeConsent_有効な同意が存在する_取消される")
        void revokeConsent_有効な同意が存在する_取消される() {
            // Given
            UserVoiceInputConsentEntity consent = buildActiveConsent(CURRENT_VERSION);
            given(consentRepository.findByUserIdAndVersionAndRevokedAtIsNull(USER_ID, CURRENT_VERSION))
                    .willReturn(Optional.of(consent));
            given(consentRepository.save(any(UserVoiceInputConsentEntity.class))).willReturn(consent);

            // When
            consentService.revokeConsent(USER_ID);

            // Then
            // revoke() が呼ばれ revokedAt が設定されることを確認
            assertThat(consent.isActive()).isFalse();
            verify(consentRepository).save(consent);
        }

        @Test
        @DisplayName("revokeConsent_有効な同意が存在しない_BusinessException(QM_030)が投げられる")
        void revokeConsent_有効な同意が存在しない_BusinessException_QM_030_が投げられる() {
            // Given
            given(consentRepository.findByUserIdAndVersionAndRevokedAtIsNull(USER_ID, CURRENT_VERSION))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> consentService.revokeConsent(USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException businessEx = (BusinessException) ex;
                        assertThat(businessEx.getErrorCode().getCode()).isEqualTo("QM_030");
                    });
            verify(consentRepository, never()).save(any());
        }
    }
}

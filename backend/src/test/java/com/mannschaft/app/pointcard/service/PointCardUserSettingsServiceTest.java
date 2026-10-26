package com.mannschaft.app.pointcard.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.pointcard.dto.PointCardUserSettingsResponse;
import com.mannschaft.app.pointcard.dto.UpdateUserSettingsRequest;
import com.mannschaft.app.pointcard.entity.PointCardUserSettingsEntity;
import com.mannschaft.app.pointcard.error.PointCardErrorCode;
import com.mannschaft.app.pointcard.repository.PointCardUserSettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link PointCardUserSettingsService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PointCardUserSettingsService 単体テスト")
class PointCardUserSettingsServiceTest {

    private static final Long USER_ID = 42L;

    @Mock
    private PointCardUserSettingsRepository settingsRepository;

    @InjectMocks
    private PointCardUserSettingsService settingsService;

    @Test
    @DisplayName("getOrCreateSettings: 未登録ユーザーは default 値（オプトアウト）で新規作成して返す")
    void getOrCreateSettings_createsDefaultWhenAbsent() {
        given(settingsRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
        given(settingsRepository.save(any(PointCardUserSettingsEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        PointCardUserSettingsResponse response = settingsService.getOrCreateSettings(USER_ID);

        assertThat(response.isEnabled()).isFalse();
        assertThat(response.termsAcceptedAt()).isNull();
        assertThat(response.termsVersion()).isNull();
        assertThat(response.requireBiometricOnShow()).isFalse();
        verify(settingsRepository).save(any(PointCardUserSettingsEntity.class));
    }

    @Test
    @DisplayName("getOrCreateSettings: 既存設定があればそれを返す（save は呼ばない）")
    void getOrCreateSettings_returnsExistingWhenPresent() {
        OffsetDateTime acceptedAt = OffsetDateTime.now().minusDays(1);
        PointCardUserSettingsEntity existing = PointCardUserSettingsEntity.builder()
                .userId(USER_ID)
                .enabled(Boolean.TRUE)
                .termsAcceptedAt(acceptedAt)
                .termsVersion(PointCardUserSettingsService.CURRENT_TERMS_VERSION)
                .requireBiometricOnShow(Boolean.TRUE)
                .build();
        given(settingsRepository.findByUserId(USER_ID)).willReturn(Optional.of(existing));

        PointCardUserSettingsResponse response = settingsService.getOrCreateSettings(USER_ID);

        assertThat(response.isEnabled()).isTrue();
        assertThat(response.termsAcceptedAt()).isEqualTo(acceptedAt);
        assertThat(response.termsVersion()).isEqualTo(PointCardUserSettingsService.CURRENT_TERMS_VERSION);
        assertThat(response.requireBiometricOnShow()).isTrue();
    }

    @Test
    @DisplayName("updateSettings: termsVersion を送ると termsAcceptedAt が現在時刻で更新される")
    void updateSettings_recordsTermsAcceptedTimestampWhenVersionGiven() {
        PointCardUserSettingsEntity existing = PointCardUserSettingsEntity.builder()
                .userId(USER_ID)
                .enabled(Boolean.FALSE)
                .build();
        given(settingsRepository.findByUserId(USER_ID)).willReturn(Optional.of(existing));
        given(settingsRepository.save(any(PointCardUserSettingsEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        OffsetDateTime before = OffsetDateTime.now().minusSeconds(1);
        UpdateUserSettingsRequest req =
                new UpdateUserSettingsRequest(Boolean.TRUE, PointCardUserSettingsService.CURRENT_TERMS_VERSION, Boolean.FALSE);
        PointCardUserSettingsResponse response = settingsService.updateSettings(USER_ID, req);

        assertThat(response.isEnabled()).isTrue();
        assertThat(response.termsVersion()).isEqualTo(PointCardUserSettingsService.CURRENT_TERMS_VERSION);
        assertThat(response.termsAcceptedAt()).isNotNull();
        assertThat(response.termsAcceptedAt()).isAfter(before);
    }

    @Test
    @DisplayName("updateSettings: null フィールドは既存値を維持する（差分適用）")
    void updateSettings_keepsExistingValuesForNullFields() {
        OffsetDateTime existingAcceptedAt = OffsetDateTime.now().minusDays(7);
        PointCardUserSettingsEntity existing = PointCardUserSettingsEntity.builder()
                .userId(USER_ID)
                .enabled(Boolean.TRUE)
                .termsAcceptedAt(existingAcceptedAt)
                .termsVersion(PointCardUserSettingsService.CURRENT_TERMS_VERSION)
                .requireBiometricOnShow(Boolean.TRUE)
                .build();
        given(settingsRepository.findByUserId(USER_ID)).willReturn(Optional.of(existing));
        given(settingsRepository.save(any(PointCardUserSettingsEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        UpdateUserSettingsRequest req = new UpdateUserSettingsRequest(null, null, null);
        PointCardUserSettingsResponse response = settingsService.updateSettings(USER_ID, req);

        assertThat(response.isEnabled()).isTrue();
        assertThat(response.termsAcceptedAt()).isEqualTo(existingAcceptedAt);
        assertThat(response.termsVersion()).isEqualTo(PointCardUserSettingsService.CURRENT_TERMS_VERSION);
        assertThat(response.requireBiometricOnShow()).isTrue();
    }

    @Test
    @DisplayName("assertTermsAcceptedAndCurrent: 設定未作成は WALLET_NOT_ENABLED で例外")
    void assertTermsAcceptedAndCurrent_throwsWhenNoSettings() {
        given(settingsRepository.findByUserId(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> settingsService.assertTermsAcceptedAndCurrent(USER_ID, PointCardUserSettingsService.CURRENT_TERMS_VERSION))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.WALLET_NOT_ENABLED);
    }

    @Test
    @DisplayName("assertTermsAcceptedAndCurrent: enabled=false は WALLET_NOT_ENABLED で例外")
    void assertTermsAcceptedAndCurrent_throwsWhenDisabled() {
        PointCardUserSettingsEntity disabled = PointCardUserSettingsEntity.builder()
                .userId(USER_ID)
                .enabled(Boolean.FALSE)
                .termsAcceptedAt(OffsetDateTime.now())
                .termsVersion(PointCardUserSettingsService.CURRENT_TERMS_VERSION)
                .build();
        given(settingsRepository.findByUserId(USER_ID)).willReturn(Optional.of(disabled));

        assertThatThrownBy(() -> settingsService.assertTermsAcceptedAndCurrent(USER_ID, PointCardUserSettingsService.CURRENT_TERMS_VERSION))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("assertTermsAcceptedAndCurrent: 規約バージョン不一致は WALLET_NOT_ENABLED で例外")
    void assertTermsAcceptedAndCurrent_throwsWhenVersionMismatch() {
        PointCardUserSettingsEntity oldVersion = PointCardUserSettingsEntity.builder()
                .userId(USER_ID)
                .enabled(Boolean.TRUE)
                .termsAcceptedAt(OffsetDateTime.now().minusYears(1))
                .termsVersion("v0.9.0")
                .build();
        given(settingsRepository.findByUserId(USER_ID)).willReturn(Optional.of(oldVersion));

        assertThatThrownBy(() -> settingsService.assertTermsAcceptedAndCurrent(USER_ID, PointCardUserSettingsService.CURRENT_TERMS_VERSION))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.WALLET_NOT_ENABLED);
    }

    @Test
    @DisplayName("assertTermsAcceptedAndCurrent: 同意済かつバージョン一致なら例外を投げない")
    void assertTermsAcceptedAndCurrent_passesWhenAllGood() {
        PointCardUserSettingsEntity ok = PointCardUserSettingsEntity.builder()
                .userId(USER_ID)
                .enabled(Boolean.TRUE)
                .termsAcceptedAt(OffsetDateTime.now())
                .termsVersion(PointCardUserSettingsService.CURRENT_TERMS_VERSION)
                .build();
        given(settingsRepository.findByUserId(USER_ID)).willReturn(Optional.of(ok));

        // 例外が投げられないこと
        settingsService.assertTermsAcceptedAndCurrent(USER_ID, PointCardUserSettingsService.CURRENT_TERMS_VERSION);
    }
}

package com.mannschaft.app.moderation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.moderation.ModerationExtErrorCode;
import com.mannschaft.app.moderation.ModerationExtMapper;
import com.mannschaft.app.moderation.dto.ModerationSettingsResponse;
import com.mannschaft.app.moderation.entity.ModerationSettingsEntity;
import com.mannschaft.app.moderation.entity.ModerationSettingsHistoryEntity;
import com.mannschaft.app.moderation.repository.ModerationSettingsHistoryRepository;
import com.mannschaft.app.moderation.repository.ModerationSettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ModerationSettingsService} の単体テスト。
 * 設定一覧取得・更新・変更履歴記録を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModerationSettingsService 単体テスト")
class ModerationSettingsServiceTest {

    @Mock
    private ModerationSettingsRepository settingsRepository;

    @Mock
    private ModerationSettingsHistoryRepository historyRepository;

    @Mock
    private ModerationExtMapper mapper;

    @InjectMocks
    private ModerationSettingsService moderationSettingsService;

    private static final Long UPDATER_ID = 100L;

    // ========================================
    // getAllSettings
    // ========================================
    @Nested
    @DisplayName("getAllSettings")
    class GetAllSettings {

        @Test
        @DisplayName("正常系: 全設定一覧を取得できる")
        void 全設定一覧を取得できる() {
            // given
            List<ModerationSettingsEntity> entities = List.of(
                    ModerationSettingsEntity.builder()
                            .settingKey("key1").settingValue("val1").build());
            List<ModerationSettingsResponse> expected = List.of(
                    new ModerationSettingsResponse(1L, "key1", "val1", null, null, null));

            given(settingsRepository.findAll()).willReturn(entities);
            given(mapper.toSettingsResponseList(entities)).willReturn(expected);

            // when
            List<ModerationSettingsResponse> result = moderationSettingsService.getAllSettings();

            // then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // updateSetting
    // ========================================
    @Nested
    @DisplayName("updateSetting")
    class UpdateSetting {

        @Test
        @DisplayName("正常系: 設定を更新し変更履歴が記録される")
        void 設定を更新し変更履歴が記録される() {
            // given
            ModerationSettingsEntity setting = ModerationSettingsEntity.builder()
                    .settingKey("yabai_threshold").settingValue("3").build();
            ModerationSettingsResponse expected = new ModerationSettingsResponse(
                    1L, "yabai_threshold", "5", null, UPDATER_ID, null);

            given(settingsRepository.findBySettingKey("yabai_threshold"))
                    .willReturn(Optional.of(setting));
            given(historyRepository.save(any(ModerationSettingsHistoryEntity.class)))
                    .willReturn(ModerationSettingsHistoryEntity.builder().build());
            given(settingsRepository.save(any(ModerationSettingsEntity.class))).willReturn(setting);
            given(mapper.toSettingsResponse(any(ModerationSettingsEntity.class))).willReturn(expected);

            // when
            ModerationSettingsResponse result = moderationSettingsService.updateSetting(
                    "yabai_threshold", "5", UPDATER_ID);

            // then
            assertThat(result).isEqualTo(expected);
            verify(historyRepository).save(any(ModerationSettingsHistoryEntity.class));
            verify(settingsRepository).save(any(ModerationSettingsEntity.class));
        }

        @Test
        @DisplayName("異常系: 設定が見つからない場合はエラー")
        void 設定が見つからない場合はエラー() {
            // given
            given(settingsRepository.findBySettingKey("unknown_key")).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> moderationSettingsService.updateSetting(
                    "unknown_key", "value", UPDATER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.SETTING_NOT_FOUND));
        }
    }
}

package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.ActionMemoMetrics;
import com.mannschaft.app.actionmemo.dto.ActionMemoSettingsResponse;
import com.mannschaft.app.actionmemo.dto.UpdateActionMemoSettingsRequest;
import com.mannschaft.app.actionmemo.entity.UserActionMemoSettingsEntity;
import com.mannschaft.app.actionmemo.repository.UserActionMemoSettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ActionMemoSettingsService} 単体テスト。
 *
 * <p>設計書 §7.1 に従い以下を検証:</p>
 * <ul>
 *   <li>レコード未作成時: getMoodEnabled → false（デフォルト）</li>
 *   <li>UPSERT: 1度目の PATCH で INSERT、2度目の PATCH で UPDATE</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActionMemoSettingsService 単体テスト")
class ActionMemoSettingsServiceTest {

    @Mock
    private UserActionMemoSettingsRepository settingsRepository;

    @Mock
    private ActionMemoMetrics metrics;

    @InjectMocks
    private ActionMemoSettingsService settingsService;

    private static final Long USER_ID = 100L;

    @Test
    @DisplayName("getMoodEnabled: レコード未作成なら false（デフォルト）")
    void getMoodEnabled_noRecord_returnsFalse() {
        given(settingsRepository.findById(USER_ID)).willReturn(Optional.empty());

        boolean result = settingsService.getMoodEnabled(USER_ID);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("getMoodEnabled: レコードがあれば保存値を返す")
    void getMoodEnabled_recordExists_returnsStoredValue() {
        UserActionMemoSettingsEntity entity = UserActionMemoSettingsEntity.builder()
                .userId(USER_ID)
                .moodEnabled(true)
                .build();
        given(settingsRepository.findById(USER_ID)).willReturn(Optional.of(entity));

        boolean result = settingsService.getMoodEnabled(USER_ID);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("getSettings: レコード未作成ならデフォルト値で返す")
    void getSettings_noRecord_returnsDefault() {
        given(settingsRepository.findById(USER_ID)).willReturn(Optional.empty());

        ActionMemoSettingsResponse response = settingsService.getSettings(USER_ID);

        assertThat(response.isMoodEnabled()).isFalse();
    }

    @Test
    @DisplayName("updateSettings: 1度目の PATCH で INSERT")
    void updateSettings_firstTime_insert() {
        given(settingsRepository.findById(USER_ID)).willReturn(Optional.empty());
        given(settingsRepository.save(any(UserActionMemoSettingsEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        UpdateActionMemoSettingsRequest req = new UpdateActionMemoSettingsRequest();
        req.setMoodEnabled(true);

        ActionMemoSettingsResponse response = settingsService.updateSettings(USER_ID, req);

        assertThat(response.isMoodEnabled()).isTrue();
        verify(settingsRepository).save(any(UserActionMemoSettingsEntity.class));
        verify(metrics).refreshMoodEnabledUserCount();
    }

    @Test
    @DisplayName("updateSettings: 2度目の PATCH で UPDATE（既存レコードを更新）")
    void updateSettings_secondTime_update() {
        UserActionMemoSettingsEntity existing = UserActionMemoSettingsEntity.builder()
                .userId(USER_ID)
                .moodEnabled(true)
                .build();
        given(settingsRepository.findById(USER_ID)).willReturn(Optional.of(existing));
        given(settingsRepository.save(any(UserActionMemoSettingsEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        UpdateActionMemoSettingsRequest req = new UpdateActionMemoSettingsRequest();
        req.setMoodEnabled(false);

        ActionMemoSettingsResponse response = settingsService.updateSettings(USER_ID, req);

        assertThat(response.isMoodEnabled()).isFalse();
        verify(settingsRepository).save(any(UserActionMemoSettingsEntity.class));
    }
}

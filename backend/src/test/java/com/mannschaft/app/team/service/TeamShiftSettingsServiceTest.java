package com.mannschaft.app.team.service;

import com.mannschaft.app.team.dto.TeamShiftSettingsResponse;
import com.mannschaft.app.team.dto.UpdateTeamShiftSettingsRequest;
import com.mannschaft.app.team.entity.TeamShiftSettingsEntity;
import com.mannschaft.app.team.repository.TeamShiftSettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link TeamShiftSettingsService} 単体テスト。
 *
 * <p>F03.5 Phase 5 リマインド間隔カスタマイズの以下を検証:</p>
 * <ul>
 *   <li>getSettings: 設定が存在する場合・存在しない場合（自動作成）</li>
 *   <li>updateSettings: 正常更新</li>
 *   <li>initializeDefaultSettings: 既存なし→作成・既存あり→スキップ</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamShiftSettingsService 単体テスト")
class TeamShiftSettingsServiceTest {

    @Mock
    private TeamShiftSettingsRepository settingsRepository;

    @InjectMocks
    private TeamShiftSettingsService settingsService;

    private static final Long TEAM_ID = 1L;

    // ========================================================================
    // getSettings
    // ========================================================================

    @Test
    @DisplayName("getSettings: 設定が存在する場合は既存値を返す")
    void getSettings_existingSettings_returnsStoredValues() {
        TeamShiftSettingsEntity entity = TeamShiftSettingsEntity.createDefault(TEAM_ID);
        entity.update(true, false, true);
        given(settingsRepository.findByTeamId(TEAM_ID)).willReturn(Optional.of(entity));

        TeamShiftSettingsResponse response = settingsService.getSettings(TEAM_ID);

        assertThat(response.getTeamId()).isEqualTo(TEAM_ID);
        assertThat(response.isReminder48hEnabled()).isTrue();
        assertThat(response.isReminder24hEnabled()).isFalse();
        assertThat(response.isReminder12hEnabled()).isTrue();
        verify(settingsRepository, never()).save(any());
    }

    @Test
    @DisplayName("getSettings: 設定が存在しない場合はデフォルト値で自動作成する")
    void getSettings_noSettings_createsDefaultAndReturns() {
        TeamShiftSettingsEntity defaultEntity = TeamShiftSettingsEntity.createDefault(TEAM_ID);
        given(settingsRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());
        given(settingsRepository.save(any(TeamShiftSettingsEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        TeamShiftSettingsResponse response = settingsService.getSettings(TEAM_ID);

        assertThat(response.isReminder48hEnabled()).isTrue();
        assertThat(response.isReminder24hEnabled()).isTrue();
        assertThat(response.isReminder12hEnabled()).isFalse();
        verify(settingsRepository).save(any(TeamShiftSettingsEntity.class));
    }

    // ========================================================================
    // updateSettings
    // ========================================================================

    @Test
    @DisplayName("updateSettings: 設定が存在する場合は既存レコードを更新する")
    void updateSettings_existingSettings_updatesValues() {
        TeamShiftSettingsEntity entity = TeamShiftSettingsEntity.createDefault(TEAM_ID);
        given(settingsRepository.findByTeamId(TEAM_ID)).willReturn(Optional.of(entity));
        given(settingsRepository.save(any(TeamShiftSettingsEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        UpdateTeamShiftSettingsRequest request = new UpdateTeamShiftSettingsRequest() {
            @Override public boolean isReminder48hEnabled() { return false; }
            @Override public boolean isReminder24hEnabled() { return true; }
            @Override public boolean isReminder12hEnabled() { return false; }
        };

        TeamShiftSettingsResponse response = settingsService.updateSettings(TEAM_ID, request);

        assertThat(response.isReminder48hEnabled()).isFalse();
        assertThat(response.isReminder24hEnabled()).isTrue();
        assertThat(response.isReminder12hEnabled()).isFalse();
        verify(settingsRepository).save(any(TeamShiftSettingsEntity.class));
    }

    @Test
    @DisplayName("updateSettings: 設定が存在しない場合は新規作成して更新する")
    void updateSettings_noSettings_createsAndUpdates() {
        given(settingsRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());
        given(settingsRepository.save(any(TeamShiftSettingsEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        UpdateTeamShiftSettingsRequest request = new UpdateTeamShiftSettingsRequest() {
            @Override public boolean isReminder48hEnabled() { return true; }
            @Override public boolean isReminder24hEnabled() { return false; }
            @Override public boolean isReminder12hEnabled() { return false; }
        };

        TeamShiftSettingsResponse response = settingsService.updateSettings(TEAM_ID, request);

        assertThat(response.isReminder48hEnabled()).isTrue();
        assertThat(response.isReminder24hEnabled()).isFalse();
        verify(settingsRepository).save(any(TeamShiftSettingsEntity.class));
    }

    @Test
    @DisplayName("updateSettings: 設定内容がキャプチャされていること")
    void updateSettings_capturedSettingsMatchRequest() {
        given(settingsRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());
        given(settingsRepository.save(any(TeamShiftSettingsEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        UpdateTeamShiftSettingsRequest request = new UpdateTeamShiftSettingsRequest() {
            @Override public boolean isReminder48hEnabled() { return false; }
            @Override public boolean isReminder24hEnabled() { return false; }
            @Override public boolean isReminder12hEnabled() { return true; }
        };

        settingsService.updateSettings(TEAM_ID, request);

        ArgumentCaptor<TeamShiftSettingsEntity> captor =
                ArgumentCaptor.forClass(TeamShiftSettingsEntity.class);
        verify(settingsRepository).save(captor.capture());
        TeamShiftSettingsEntity saved = captor.getValue();
        assertThat(saved.isReminder48hEnabled()).isFalse();
        assertThat(saved.isReminder24hEnabled()).isFalse();
        assertThat(saved.isReminder12hEnabled()).isTrue();
    }

    // ========================================================================
    // initializeDefaultSettings
    // ========================================================================

    @Test
    @DisplayName("initializeDefaultSettings: 設定が存在しない場合はデフォルト設定を作成する")
    void initializeDefaultSettings_noSettings_creates() {
        given(settingsRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());
        given(settingsRepository.save(any(TeamShiftSettingsEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        settingsService.initializeDefaultSettings(TEAM_ID);

        ArgumentCaptor<TeamShiftSettingsEntity> captor =
                ArgumentCaptor.forClass(TeamShiftSettingsEntity.class);
        verify(settingsRepository).save(captor.capture());
        TeamShiftSettingsEntity saved = captor.getValue();
        assertThat(saved.getTeamId()).isEqualTo(TEAM_ID);
        assertThat(saved.isReminder48hEnabled()).isTrue();
        assertThat(saved.isReminder24hEnabled()).isTrue();
        assertThat(saved.isReminder12hEnabled()).isFalse();
    }

    @Test
    @DisplayName("initializeDefaultSettings: 設定が既に存在する場合はスキップする")
    void initializeDefaultSettings_existingSettings_skips() {
        TeamShiftSettingsEntity existing = TeamShiftSettingsEntity.createDefault(TEAM_ID);
        given(settingsRepository.findByTeamId(TEAM_ID)).willReturn(Optional.of(existing));

        settingsService.initializeDefaultSettings(TEAM_ID);

        verify(settingsRepository, never()).save(any());
    }
}

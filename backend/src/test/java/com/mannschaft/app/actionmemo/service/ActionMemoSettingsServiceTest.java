package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.ActionMemoErrorCode;
import com.mannschaft.app.actionmemo.ActionMemoMetrics;
import com.mannschaft.app.actionmemo.dto.ActionMemoSettingsResponse;
import com.mannschaft.app.actionmemo.dto.UpdateActionMemoSettingsRequest;
import com.mannschaft.app.actionmemo.entity.UserActionMemoSettingsEntity;
import com.mannschaft.app.actionmemo.enums.ActionMemoCategory;
import com.mannschaft.app.actionmemo.repository.UserActionMemoSettingsRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ActionMemoSettingsService} 単体テスト。
 *
 * <p>設計書 §7.1 / §10.1 に従い以下を検証:</p>
 * <ul>
 *   <li>レコード未作成時: getMoodEnabled → false（デフォルト）</li>
 *   <li>UPSERT: 1度目の PATCH で INSERT、2度目の PATCH で UPDATE</li>
 *   <li>Phase 3: defaultPostTeamId の所属チーム検証（IDOR 対策）</li>
 *   <li>Phase 3: defaultCategory の永続化</li>
 *   <li>Phase 3: getSettings がデフォルト値（PRIVATE）を返す</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActionMemoSettingsService 単体テスト")
class ActionMemoSettingsServiceTest {

    @Mock
    private UserActionMemoSettingsRepository settingsRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private ActionMemoMetrics metrics;

    @InjectMocks
    private ActionMemoSettingsService settingsService;

    private static final Long USER_ID = 100L;
    private static final Long TEAM_ID = 200L;

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

    // ==================================================================
    // Phase 3: defaultPostTeamId / defaultCategory のテスト（設計書 §10.1）
    // ==================================================================

    @Test
    @DisplayName("updateSettings: defaultPostTeamId が非所属チームなら INVALID_DEFAULT_TEAM 例外")
    void updateSettings_defaultPostTeamIdNotMember_throws_INVALID_DEFAULT_TEAM() {
        given(settingsRepository.findById(USER_ID)).willReturn(Optional.empty());
        given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, TEAM_ID)).willReturn(false);

        UpdateActionMemoSettingsRequest req = new UpdateActionMemoSettingsRequest();
        req.setDefaultPostTeamId(TEAM_ID);

        assertThatThrownBy(() -> settingsService.updateSettings(USER_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ActionMemoErrorCode.ACTION_MEMO_INVALID_DEFAULT_TEAM);

        verify(settingsRepository, never()).save(any(UserActionMemoSettingsEntity.class));
    }

    @Test
    @DisplayName("updateSettings: defaultPostTeamId が所属チームなら永続化される")
    void updateSettings_defaultPostTeamIdValid_persists() {
        given(settingsRepository.findById(USER_ID)).willReturn(Optional.empty());
        given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, TEAM_ID)).willReturn(true);
        given(settingsRepository.save(any(UserActionMemoSettingsEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        UpdateActionMemoSettingsRequest req = new UpdateActionMemoSettingsRequest();
        req.setDefaultPostTeamId(TEAM_ID);

        ActionMemoSettingsResponse response = settingsService.updateSettings(USER_ID, req);

        assertThat(response.getDefaultPostTeamId()).isEqualTo(TEAM_ID);

        ArgumentCaptor<UserActionMemoSettingsEntity> captor =
                ArgumentCaptor.forClass(UserActionMemoSettingsEntity.class);
        verify(settingsRepository).save(captor.capture());
        assertThat(captor.getValue().getDefaultPostTeamId()).isEqualTo(TEAM_ID);
    }

    @Test
    @DisplayName("updateSettings: defaultCategory = WORK が永続化される")
    void updateSettings_defaultCategoryWork_persists() {
        given(settingsRepository.findById(USER_ID)).willReturn(Optional.empty());
        given(settingsRepository.save(any(UserActionMemoSettingsEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        UpdateActionMemoSettingsRequest req = new UpdateActionMemoSettingsRequest();
        req.setDefaultCategory(ActionMemoCategory.WORK);

        ActionMemoSettingsResponse response = settingsService.updateSettings(USER_ID, req);

        assertThat(response.getDefaultCategory()).isEqualTo(ActionMemoCategory.WORK);

        ArgumentCaptor<UserActionMemoSettingsEntity> captor =
                ArgumentCaptor.forClass(UserActionMemoSettingsEntity.class);
        verify(settingsRepository).save(captor.capture());
        assertThat(captor.getValue().getDefaultCategory()).isEqualTo(ActionMemoCategory.WORK);
    }

    @Test
    @DisplayName("getSettings: Phase 3 フィールドのデフォルト値（defaultPostTeamId=null / defaultCategory=PRIVATE）を返す")
    void getSettings_returnsPhase3Fields_withDefaults() {
        given(settingsRepository.findById(USER_ID)).willReturn(Optional.empty());

        ActionMemoSettingsResponse response = settingsService.getSettings(USER_ID);

        assertThat(response.isMoodEnabled()).isFalse();
        assertThat(response.getDefaultPostTeamId()).isNull();
        assertThat(response.getDefaultCategory()).isEqualTo(ActionMemoCategory.PRIVATE);
    }
}
